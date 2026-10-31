package com.devomel.trckr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun duration(sec: Long): String =
        String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
}

/** Deliberately minimal UI: one screen with live time + heart rate, start/stop and history. */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("trckr", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvTime: TextView
    private lateinit var tvHr: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnHr: Button
    private lateinit var btnRun: Button
    private lateinit var listView: ListView
    private var sessions: List<SessionStore.Summary> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        tvTime = TextView(this).apply {
            textSize = 48f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            text = "0:00:00"
        }
        tvHr = TextView(this).apply {
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(200, 40, 40))
        }
        tvStats = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        }
        btnHr = Button(this).apply { setOnClickListener { scanHr() } }
        btnRun = Button(this).apply {
            textSize = 22f
            setOnClickListener { if (RunService.running) confirmStop() else startRun() }
        }
        val header = TextView(this).apply {
            text = "Історія (тап — деталі/експорт, довгий тап — видалити)"
            setPadding(0, pad, 0, pad / 4)
            setTypeface(typeface, Typeface.BOLD)
        }
        listView = ListView(this).apply {
            setOnItemClickListener { _, _, pos, _ -> showSession(sessions[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(sessions[pos]); true }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(tvTime, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(tvHr, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(tvStats, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(btnHr, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(btnRun, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestPermissions(neededPermissions(), 1)
    }

    override fun onResume() {
        super.onResume()
        prefs.getString("hr_address", null)?.let { HrMonitor.connect(this, it) }
        refreshHistory()
        handler.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uiTick)
    }

    override fun onDestroy() {
        if (!RunService.running) HrMonitor.disconnect()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        prefs.getString("hr_address", null)?.let { HrMonitor.connect(this, it) }
    }

    private fun neededPermissions(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // Android 10/11 only allow BLE scanning with location permission.
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetooth() = Build.VERSION.SDK_INT < 31 ||
        (has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT))

    // ---- live UI ----

    private val uiTick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private fun render() {
        val running = RunService.running
        val hr = HrMonitor.freshBpm()
        val hrName = prefs.getString("hr_name", null)

        tvHr.text = if (hr > 0) "♥ $hr" else "♥ --"
        val status = when {
            hrName == null -> "Пульсометр не вибрано"
            hr > 0 -> "уд/хв"
            HrMonitor.status.startsWith("пристрій без") -> "це не пульсометр — вибери інший"
            else -> HrMonitor.status
        }

        if (running) {
            val sec = (System.currentTimeMillis() - RunService.startedAt) / 1000
            tvTime.text = Format.duration(sec)
            val avg = RunService.avgHr()
            tvStats.text = "$status\nСередній: ${if (avg > 0) avg else "-"}   Макс: ${if (RunService.hrMax > 0) RunService.hrMax else "-"}"
            btnRun.text = "■ СТОП"
            btnRun.setBackgroundColor(Color.rgb(200, 40, 40))
        } else {
            tvTime.text = "0:00:00"
            tvStats.text = status
            btnRun.text = "▶ СТАРТ"
            btnRun.setBackgroundColor(Color.rgb(30, 150, 60))
        }
        btnRun.setTextColor(Color.WHITE)
        btnHr.text = "Пульсометр: ${hrName ?: "вибрати…"}"
    }

    // ---- run control ----

    private fun startRun() {
        if (!hasBluetooth()) {
            Toast.makeText(this, "Потрібен дозвіл на Bluetooth", Toast.LENGTH_LONG).show()
            requestPermissions(neededPermissions(), 1)
            return
        }
        askBatteryWhitelistOnce()
        startForegroundService(Intent(this, RunService::class.java).setAction(RunService.ACTION_START))
        handler.postDelayed({ render() }, 300)
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setMessage("Завершити і зберегти тренування?")
            .setPositiveButton("Зберегти") { _, _ ->
                startService(Intent(this, RunService::class.java).setAction(RunService.ACTION_STOP))
                handler.postDelayed({ refreshHistory(); render() }, 500)
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryWhitelistOnce() {
        if (prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    // ---- HR strap selection ----

    private fun scanHr() {
        if (!hasBluetooth()) {
            requestPermissions(neededPermissions(), 1)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Увімкни Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        val found = LinkedHashMap<String, Pair<String, Boolean>>() // address -> (name, hasHrService)
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                val isHr = r.scanRecord?.serviceUuids?.any { it.uuid == HrMonitor.HR_SERVICE } == true
                found[r.device.address] = name to (isHr || found[r.device.address]?.second == true)
            }
        }
        val progress = AlertDialog.Builder(this)
            .setMessage("Шукаю пульсометр… (одягни і змочи датчик)")
            .setCancelable(false)
            .show()
        scanner.startScan(
            null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb
        )
        handler.postDelayed({
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
            progress.dismiss()
            // Only real HR straps if any were found; otherwise everything with a name.
            val hrOnly = found.entries.filter { it.value.second }
            val items = hrOnly.ifEmpty { found.entries.toList() }
            if (items.isEmpty()) {
                Toast.makeText(this, "Нічого не знайдено", Toast.LENGTH_LONG).show()
                return@postDelayed
            }
            if (hrOnly.isEmpty()) {
                Toast.makeText(this, "Пульсометр не знайдено — змочи контакти, одягни і спробуй ще", Toast.LENGTH_LONG).show()
            }
            val labels = items.map { (addr, v) -> (if (v.second) "♥ " else "") + v.first + "  " + addr }
            AlertDialog.Builder(this)
                .setTitle(if (hrOnly.isEmpty()) "Пульсометрів не видно, усі пристрої:" else "Вибери пульсометр")
                .setItems(labels.toTypedArray()) { _, i ->
                    val (addr, v) = items[i]
                    prefs.edit().putString("hr_address", addr).putString("hr_name", v.first).apply()
                    HrMonitor.connect(this, addr)
                }
                .setNegativeButton("Скасувати", null)
                .show()
        }, 8000)
    }

    // ---- history ----

    private fun refreshHistory() {
        sessions = SessionStore.list(this, RunService.sessionId)
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
        val rows = sessions.map {
            df.format(Date(it.startMs)) + "   " + Format.duration(it.durationS) +
                (if (it.avgHr > 0) "   ♥ ${it.avgHr} / ${it.maxHr}" else "")
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun showSession(s: SessionStore.Summary) {
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
        val msg = "Старт: ${df.format(Date(s.startMs))}\n" +
            "Час: ${Format.duration(s.durationS)}\n" +
            "Пульс: середній ${if (s.avgHr > 0) s.avgHr else "-"}, макс ${if (s.maxHr > 0) s.maxHr else "-"}"
        AlertDialog.Builder(this)
            .setTitle("Тренування ${s.id}")
            .setMessage(msg)
            .setPositiveButton("Експорт") { _, _ ->
                try {
                    val where = SessionStore.export(this, s.id)
                    Toast.makeText(this, "Збережено: $where", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Помилка експорту: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Закрити", null)
            .show()
    }

    private fun confirmDelete(s: SessionStore.Summary) {
        AlertDialog.Builder(this)
            .setMessage("Видалити тренування ${s.id}?")
            .setPositiveButton("Видалити") { _, _ ->
                SessionStore.delete(this, s.id)
                refreshHistory()
            }
            .setNegativeButton("Ні", null)
            .show()
    }
}
