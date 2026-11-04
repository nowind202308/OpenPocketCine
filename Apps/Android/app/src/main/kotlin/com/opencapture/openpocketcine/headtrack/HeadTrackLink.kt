package com.opencapture.openpocketcine.headtrack

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/**
 * Fork (feat/headtrack-f2): the Bluetooth side of the DIY head tracker (XIAO nRF52840 running
 * `headtrack_f2.ino`). Scans for its service, connects, subscribes to the pose characteristic and
 * keeps the latest [HeadPose]. Callbacks arrive on a binder thread; readers take [latest].
 */
@SuppressLint("MissingPermission")
class HeadTrackLink(private val context: Context, private val onState: (connected: Boolean) -> Unit) {
    @Volatile var latest: HeadPose? = null
        private set
    @Volatile var connected: Boolean = false
        private set

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var wanted = false

    private val manager get() = context.getSystemService(BluetoothManager::class.java)

    fun start() {
        wanted = true
        if (gatt != null || scanning) return
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    fun stop() {
        wanted = false
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        setConnected(false)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        if (!value) latest = null
        onState(value)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!wanted || gatt != null) return
            stopScan()
            connect(result.device)
        }
    }

    private fun connect(device: BluetoothDevice) {
        gatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            null
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                if (gatt === g) gatt = null
                setConnected(false)
                if (wanted) start()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(POSE_UUID) ?: run {
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID) ?: return
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable)
            } else {
                writeDescriptorLegacy(g, cccd, enable)
            }
            setConnected(true)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            legacyValue(ch)?.let(::accept)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            accept(value)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        d.value = value
        g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    private fun legacyValue(ch: BluetoothGattCharacteristic): ByteArray? = ch.value

    private fun accept(value: ByteArray) {
        HeadPose.parse(value, SystemClock.elapsedRealtime())?.let { latest = it }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e8a0001-4b1e-4a3c-9d5f-2f0c8a7b4e10")
        val POSE_UUID: UUID = UUID.fromString("6e8a0002-4b1e-4a3c-9d5f-2f0c8a7b4e10")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/**
 * One notification from the head tracker: yaw and pitch in degrees (yaw integrated from turning,
 * right positive; pitch absolute from gravity, look-up positive), the button's recenter count and
 * whether tracking is enabled on the device.
 */
data class HeadPose(
    val yawDeg: Double,
    val pitchDeg: Double,
    val recenterSeq: Int,
    val enabled: Boolean,
    val atMs: Long,
    val speedLevel: Int = 0,
    val intervalLevel: Int = 0,
    val modeLevel: Int = 0,
) {
    companion object {
        /** Wire: i16 yaw (0.1°), i16 pitch (0.1°), u8 recenter count, u8 flags (bit 0 enabled, bits 1–3 speed level, bits 4–6 send-interval level; 0 = default), optional u8 mode (1 angle, 2 stick, 3 hybrid). */
        fun parse(v: ByteArray, atMs: Long): HeadPose? {
            if (v.size < 6) return null
            fun i16(o: Int) = ((v[o].toInt() and 0xFF) or (v[o + 1].toInt() shl 8)).toShort().toInt()
            val flags = v[5].toInt() and 0xFF
            return HeadPose(i16(0) / 10.0, i16(2) / 10.0, v[4].toInt() and 0xFF, (flags and 1) != 0, atMs, (flags shr 1) and 0x07, (flags shr 4) and 0x07,
                if (v.size >= 7) v[6].toInt() and 0xFF else 0)
        }
    }
}
