package com.devomel.trckr

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sessions live in files/sessions/<id>/:
 *  - track.csv    raw 1 Hz log, written live during the run (survives crashes)
 *  - summary.json computed on stop (or lazily, if the app died mid-run)
 *  - track.gpx    GPX 1.1 with Garmin HR extension (Strava/Garmin compatible)
 */
object SessionStore {
    const val CSV_HEADER = "t_ms,lat,lon,alt,acc,speed,hr,dist_m"

    data class Summary(
        val id: String,
        val startMs: Long,
        val durationS: Long,
        val distanceM: Double,
        val avgHr: Int,
        val maxHr: Int,
        val gpsPoints: Int,
    )

    fun root(ctx: Context) = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun newSessionDir(ctx: Context): File {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(root(ctx), id).apply { mkdirs() }
    }

    fun list(ctx: Context, activeId: String?): List<Summary> {
        val dirs = root(ctx).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { it.name }.mapNotNull { dir ->
            if (dir.name == activeId) return@mapNotNull null
            try {
                val f = File(dir, "summary.json")
                if (f.exists()) fromJson(JSONObject(f.readText())) else finalize(dir)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun delete(ctx: Context, id: String) {
        File(root(ctx), id).deleteRecursively()
    }

    /** Builds summary.json and track.gpx from track.csv. */
    fun finalize(dir: File): Summary? {
        val csv = File(dir, "track.csv")
        if (!csv.exists()) return null
        val rows = csv.readLines().drop(1).mapNotNull { parseRow(it) }
        if (rows.isEmpty()) return null

        val hrs = rows.mapNotNull { it.hr }
        val gps = rows.filter { it.lat != null && it.lon != null }
        val summary = Summary(
            id = dir.name,
            startMs = rows.first().t,
            durationS = (rows.last().t - rows.first().t) / 1000,
            distanceM = rows.last().dist,
            avgHr = if (hrs.isEmpty()) 0 else hrs.average().toInt(),
            maxHr = hrs.maxOrNull() ?: 0,
            gpsPoints = gps.size,
        )
        File(dir, "summary.json").writeText(toJson(summary).toString(2))
        writeGpx(File(dir, "track.gpx"), summary, rows)
        return summary
    }

    /** Copies CSV (and GPX for old sessions with GPS) to Downloads/trckr. Returns a human-readable location. */
    fun export(ctx: Context, id: String): String {
        val dir = File(root(ctx), id)
        val summary = finalize(dir)
        val files = mutableListOf("track.csv" to "text/csv")
        if ((summary?.gpsPoints ?: 0) > 0) files += "track.gpx" to "application/gpx+xml"
        for ((name, mime) in files) {
            val src = File(dir, name)
            if (!src.exists()) continue
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "run_${id}.${name.substringAfter('.')}")
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/trckr")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("не вдалося створити файл")
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        }
        return "Download/trckr/run_$id.csv"
    }

    private class Row(
        val t: Long, val lat: Double?, val lon: Double?, val alt: Double?,
        val hr: Int?, val dist: Double,
    )

    private fun parseRow(line: String): Row? {
        val p = line.split(',')
        if (p.size < 8) return null
        return try {
            Row(
                t = p[0].toLong(),
                lat = p[1].toDoubleOrNull(),
                lon = p[2].toDoubleOrNull(),
                alt = p[3].toDoubleOrNull(),
                hr = p[6].toIntOrNull()?.takeIf { it > 0 },
                dist = p[7].toDoubleOrNull() ?: 0.0,
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun isoUtc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    private fun writeGpx(file: File, s: Summary, rows: List<Row>) {
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<gpx version=\"1.1\" creator=\"trckr\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            w.write("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
            w.write("<metadata><time>${isoUtc(s.startMs)}</time></metadata>\n")
            w.write("<trk><name>Run ${s.id}</name><type>running</type><trkseg>\n")
            var lastHr: Int? = null
            for (r in rows) {
                if (r.hr != null) lastHr = r.hr
                if (r.lat == null || r.lon == null) continue
                w.write(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", r.lat, r.lon))
                if (r.alt != null) w.write(String.format(Locale.US, "<ele>%.1f</ele>", r.alt))
                w.write("<time>${isoUtc(r.t)}</time>")
                val hr = r.hr ?: lastHr
                if (hr != null) {
                    w.write("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>$hr</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
                }
                w.write("</trkpt>\n")
            }
            w.write("</trkseg></trk>\n</gpx>\n")
        }
    }

    private fun toJson(s: Summary) = JSONObject().apply {
        put("id", s.id)
        put("startMs", s.startMs)
        put("durationS", s.durationS)
        put("distanceM", s.distanceM)
        put("avgHr", s.avgHr)
        put("maxHr", s.maxHr)
        put("gpsPoints", s.gpsPoints)
    }

    private fun fromJson(o: JSONObject) = Summary(
        id = o.getString("id"),
        startMs = o.getLong("startMs"),
        durationS = o.getLong("durationS"),
        distanceM = o.getDouble("distanceM"),
        avgHr = o.optInt("avgHr"),
        maxHr = o.optInt("maxHr"),
        gpsPoints = o.optInt("gpsPoints"),
    )
}
