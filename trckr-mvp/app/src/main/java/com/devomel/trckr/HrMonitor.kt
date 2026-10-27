package com.devomel.trckr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Connects to a BLE heart rate strap (standard Heart Rate Service 0x180D,
 * works with Magene H64/H303 and most other chest straps) and keeps the
 * latest bpm. Reconnects automatically while an address is set.
 */
@SuppressLint("MissingPermission")
object HrMonitor {
    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @Volatile var bpm = 0
        private set
    @Volatile var lastAt = 0L
        private set
    @Volatile var status = "не підключено"
        private set

    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Current bpm, or 0 if no fresh reading in the last 5 s. */
    fun freshBpm(): Int = if (System.currentTimeMillis() - lastAt < 5000) bpm else 0

    fun connect(ctx: Context, addr: String) {
        if (addr == address && gatt != null) return
        disconnect()
        appContext = ctx.applicationContext
        address = addr
        open(autoConnect = false)
    }

    fun disconnect() {
        address = null
        handler.removeCallbacksAndMessages(null)
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
        bpm = 0
        status = "не підключено"
    }

    private fun open(autoConnect: Boolean) {
        val ctx = appContext ?: return
        val addr = address ?: return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status = "Bluetooth вимкнено"
            scheduleReconnect()
            return
        }
        status = "підключення…"
        try {
            val device = adapter.getRemoteDevice(addr)
            gatt = device.connectGatt(ctx, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            status = "помилка: ${e.message}"
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (address != null && gatt == null) open(autoConnect = true)
        }, 3000)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status = "підключено"
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    try {
                        g.close()
                    } catch (_: Exception) {
                    }
                    if (gatt == g) gatt = null
                    bpm = 0
                    if (address != null) {
                        status = "з'єднання втрачено, перепідключення…"
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            handler.post {
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                if (ch == null) {
                    status = "пристрій без пульсового сервісу"
                    return@post
                }
                g.setCharacteristicNotification(ch, true)
                val desc = ch.getDescriptor(CCCD) ?: return@post
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
                status = "очікування пульсу…"
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parse(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                ch.value?.let { parse(it) }
            }
        }
    }

    private fun parse(v: ByteArray) {
        if (v.size < 2) return
        val flags = v[0].toInt()
        val value = if (flags and 0x01 == 0) {
            v[1].toInt() and 0xFF
        } else {
            if (v.size < 3) return
            (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        }
        if (value <= 0) return
        bpm = value
        lastAt = System.currentTimeMillis()
        status = "OK"
    }
}
