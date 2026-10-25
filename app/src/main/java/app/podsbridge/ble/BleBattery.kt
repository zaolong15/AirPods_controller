package app.podsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import app.podsbridge.PodsStore

data class BleReading(
    val left: Int?, val right: Int?, val case: Int?,
    val chargeL: Boolean, val chargeR: Boolean, val chargeCase: Boolean,
    val rssi: Int, val seenAt: Long,
)

/**
 * 不依赖 L2CAP 的兜底：扫描 AirPods 广播的 Apple 厂商数据（公司 ID 0x004C，类型 0x07，共 27 字节），
 * 读取电量。字段布局来自公开的逆向资料，属于实验性实现：只取信号最强的那一副。
 */
@SuppressLint("MissingPermission")
object BleBattery {
    private var cb: ScanCallback? = null

    fun start(ctx: Context) {
        if (cb != null) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf(0x07, 0x19), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onScanFailed(errorCode: Int) { PodsStore.log("BLE 扫描失败: $errorCode") }
        }
        cb = callback
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure { PodsStore.log("BLE 扫描无法启动: ${it.message}"); cb = null }
    }

    fun stop(ctx: Context) {
        val c = cb ?: return
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) }
        cb = null
    }

    private fun lv(n: Int): Int? = if (n in 0..10) n * 10 else null

    private fun handle(r: ScanResult) {
        val d = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
        if (d.size != 27 || d[0].toInt() != 0x07) return
        if (r.rssi < -75) return
        val now = System.currentTimeMillis()
        val old = PodsStore.state.value.ble
        if (old != null && now - old.seenAt < 4000 && old.rssi > r.rssi) return
        val flip = (((d[5].toInt() and 0xFF) shr 4) and 0x02) == 0
        val b6 = d[6].toInt() and 0xFF
        val b7 = d[7].toInt() and 0xFF
        val hi = b6 shr 4
        val lo = b6 and 0x0F
        val chg = b7 shr 4
        val reading = BleReading(
            left = lv(if (flip) hi else lo), right = lv(if (flip) lo else hi), case = lv(b7 and 0x0F),
            chargeL = (chg and (if (flip) 2 else 1)) != 0, chargeR = (chg and (if (flip) 1 else 2)) != 0,
            chargeCase = (chg and 4) != 0, rssi = r.rssi, seenAt = now,
        )
        PodsStore.update { it.copy(ble = reading) }
    }
}
