package com.devomel.trckr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/** Foreground service that records one run: GPS + heart rate, 1 row per second. */
@SuppressLint("MissingPermission")
class RunService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.devomel.trckr.START"
        const val ACTION_STOP = "com.devomel.trckr.STOP"
        private const val CHANNEL = "run"
        private const val NOTIF_ID = 1

        // Live state read by MainActivity.
        @Volatile var running = false
        @Volatile var sessionId: String? = null
        @Volatile var startedAt = 0L
        @Volatile var distanceM = 0.0
        @Volatile var lastLocation: Location? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var writer: BufferedWriter? = null
    private var dir: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAccepted: Location? = null
    private var lastWrittenFixTime = 0L
    private var ticks = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_STOP -> end()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (running) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Тренування", NotificationManager.IMPORTANCE_LOW)
        )
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        startForeground(NOTIF_ID, buildNotification("Старт…"), types)

        val d = SessionStore.newSessionDir(this)
        dir = d
        writer = File(d, "track.csv").bufferedWriter().apply {
            write(SessionStore.CSV_HEADER + "\n")
            flush()
        }
        sessionId = d.name
        startedAt = System.currentTimeMillis()
        distanceM = 0.0
        lastAccepted = null
        lastWrittenFixTime = 0L
        ticks = 0
        running = true

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trckr:run").apply { acquire(6 * 60 * 60 * 1000L) }

        getSystemService(LocationManager::class.java)
            .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())

        getSharedPreferences("trckr", MODE_PRIVATE).getString("hr_address", null)?.let {
            HrMonitor.connect(this, it)
        }

        handler.post(tick)
    }

    private fun end() {
        if (running) {
            running = false
            handler.removeCallbacks(tick)
            getSystemService(LocationManager::class.java).removeUpdates(this)
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
            dir?.let { SessionStore.finalize(it) }
            sessionId = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // If the system kills us mid-run, keep what we have.
        if (running) end()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            writeRow()
            ticks++
            if (ticks % 5 == 0) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(statusLine()))
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun writeRow() {
        val now = System.currentTimeMillis()
        val loc = lastLocation
        val sb = StringBuilder().append(now).append(',')
        if (loc != null && loc.time != lastWrittenFixTime && now - loc.time < 5000) {
            lastWrittenFixTime = loc.time
            sb.append(String.format(Locale.US, "%.7f,%.7f,", loc.latitude, loc.longitude))
            sb.append(if (loc.hasAltitude()) String.format(Locale.US, "%.1f", loc.altitude) else "").append(',')
            sb.append(String.format(Locale.US, "%.1f,", loc.accuracy))
            sb.append(if (loc.hasSpeed()) String.format(Locale.US, "%.2f", loc.speed) else "").append(',')
        } else {
            sb.append(",,,,,")
        }
        val hr = HrMonitor.freshBpm()
        sb.append(if (hr > 0) hr.toString() else "").append(',')
        sb.append(String.format(Locale.US, "%.1f", distanceM)).append('\n')
        try {
            writer?.write(sb.toString())
            writer?.flush()
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(loc: Location) {
        lastLocation = loc
        if (!running || loc.accuracy > 25f) return
        val prev = lastAccepted
        if (prev == null) {
            lastAccepted = loc
            return
        }
        // Skip jitter: only count movement larger than the noise.
        val d = prev.distanceTo(loc)
        if (d >= maxOf(3f, loc.accuracy / 2f)) {
            distanceM += d
            lastAccepted = loc
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun statusLine(): String {
        val sec = (System.currentTimeMillis() - startedAt) / 1000
        val hr = HrMonitor.freshBpm()
        return String.format(
            Locale.US, "%s · %.2f км%s",
            Format.duration(sec), distanceM / 1000.0, if (hr > 0) " · ♥ $hr" else ""
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("trckr: біг записується")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
