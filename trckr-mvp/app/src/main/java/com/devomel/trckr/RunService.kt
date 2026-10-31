package com.devomel.trckr

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Foreground service that records one run: heart rate, 1 row per second.
 * GPS is deliberately not used (constant spoofing makes it useless).
 */
@SuppressLint("MissingPermission")
class RunService : Service() {

    companion object {
        const val ACTION_START = "com.devomel.trckr.START"
        const val ACTION_STOP = "com.devomel.trckr.STOP"
        private const val CHANNEL = "run"
        private const val NOTIF_ID = 1

        // Live state read by MainActivity.
        @Volatile var running = false
        @Volatile var sessionId: String? = null
        @Volatile var startedAt = 0L
        @Volatile var hrSum = 0L
        @Volatile var hrCount = 0
        @Volatile var hrMax = 0

        fun avgHr(): Int = if (hrCount == 0) 0 else (hrSum / hrCount).toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var writer: BufferedWriter? = null
    private var dir: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
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
        startForeground(NOTIF_ID, buildNotification("Старт…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val d = SessionStore.newSessionDir(this)
        dir = d
        writer = File(d, "track.csv").bufferedWriter().apply {
            write(SessionStore.CSV_HEADER + "\n")
            flush()
        }
        sessionId = d.name
        startedAt = System.currentTimeMillis()
        hrSum = 0
        hrCount = 0
        hrMax = 0
        ticks = 0
        running = true

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trckr:run").apply { acquire(6 * 60 * 60 * 1000L) }

        getSharedPreferences("trckr", MODE_PRIVATE).getString("hr_address", null)?.let {
            HrMonitor.connect(this, it)
        }

        handler.post(tick)
    }

    private fun end() {
        if (running) {
            running = false
            handler.removeCallbacks(tick)
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
        val hr = HrMonitor.freshBpm()
        if (hr > 0) {
            hrSum += hr
            hrCount++
            if (hr > hrMax) hrMax = hr
        }
        // Same CSV layout as before; GPS columns stay empty.
        val row = "${System.currentTimeMillis()},,,,,,${if (hr > 0) hr.toString() else ""},0.0\n"
        try {
            writer?.write(row)
            writer?.flush()
        } catch (_: Exception) {
        }
    }

    private fun statusLine(): String {
        val sec = (System.currentTimeMillis() - startedAt) / 1000
        val hr = HrMonitor.freshBpm()
        return String.format(Locale.US, "%s%s", Format.duration(sec), if (hr > 0) " · ♥ $hr" else " · пульсу немає")
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("trckr: біг записується")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
