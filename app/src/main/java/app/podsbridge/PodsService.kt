package app.podsbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.podsbridge.aap.*
import app.podsbridge.audio.Effects
import app.podsbridge.ble.BleBattery
import java.util.concurrent.Executors
import kotlin.math.max

@SuppressLint("MissingPermission")
class PodsService : Service() {
    companion object {
        const val ACTION_CONNECT = "app.podsbridge.CONNECT"
        const val ACTION_DISCONNECT = "app.podsbridge.DISCONNECT"
        const val ACTION_STANDBY = "app.podsbridge.STANDBY"
        const val ACTION_STOP_STANDBY = "app.podsbridge.STOP_STANDBY"
        const val ACTION_MODE = "app.podsbridge.MODE"
        const val EXTRA_ADDR = "addr"
        const val EXTRA_MODE = "mode"
        private const val CH = "pods_status"
        private const val NOTIF_ID = 1
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var nm: NotificationManager
    private lateinit var am: AudioManager
    private lateinit var link: AapLink
    private var duckedFrom: Int? = null
    private var pausedByUs = false
    private var lastHeadMs = 0L
    private var aclRegistered = false
    private var ringing = false
    private var callCb: Any? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        nm = getSystemService(NotificationManager::class.java)
        am = getSystemService(AudioManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "AirPods 状态", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        link = AapLink { PodsStore.log(it) }
        link.onEvent = ::handle
        link.onClosed = { reason ->
            PodsStore.log("连接断开: $reason")
            restoreVolume()
            PodsStore.update { it.copy(connected = false, connecting = false, status = "已断开：$reason", headTracking = false) }
            updateScan()
            refreshNotif()
        }
        PodsController.link = link
        Effects.register(this)
        registerCallListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent.getStringExtra(EXTRA_ADDR), attempts = 1)
            ACTION_DISCONNECT -> {
                link.close()
                PodsStore.update { it.copy(connected = false, connecting = false, status = "已断开") }
                if (!PodsStore.state.value.standby) stopSelf() else { updateScan(); refreshNotif() }
            }
            ACTION_STANDBY -> enterStandby()
            ACTION_STOP_STANDBY -> {
                PodsStore.setting { it.copy(standby = false) }
                unregisterAcl()
                link.close()
                BleBattery.stop(this)
                stopSelf()
            }
            ACTION_MODE -> NoiseMode.from(intent.getIntExtra(EXTRA_MODE, 0))?.let { PodsController.setNoise(it) }
        }
        return START_NOT_STICKY
    }

    // ---------- 待命 / 自动连接 ----------
    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val saved = PodsStore.state.value.deviceAddress
            if (saved.isEmpty() || !dev.address.equals(saved, true)) return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    PodsStore.log("检测到 AirPods 已连上手机，准备接管")
                    connect(dev.address, attempts = 3)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    PodsStore.log("AirPods 已与手机断开")
                    link.close()
                    PodsStore.update { it.copy(connected = false, connecting = false, status = "待命：AirPods 连上手机后自动接管", headTracking = false) }
                    updateScan(); refreshNotif()
                }
            }
        }
    }

    private fun enterStandby() {
        PodsStore.setting { it.copy(standby = true) }
        if (!aclRegistered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ContextCompat.registerReceiver(this, aclReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            aclRegistered = true
        }
        PodsStore.update { if (it.connected) it else it.copy(status = "待命：AirPods 连上手机后自动接管") }
        val addr = PodsStore.state.value.deviceAddress
        if (addr.isNotEmpty() && !PodsStore.state.value.connected && isSystemConnected(addr)) connect(addr, attempts = 3)
        updateScan()
        refreshNotif()
    }

    private fun unregisterAcl() {
        if (aclRegistered) { runCatching { unregisterReceiver(aclReceiver) }; aclRegistered = false }
    }

    private fun isSystemConnected(addr: String): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(addr, true)
        }

    private fun connect(addr: String?, attempts: Int) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (addr == null || adapter == null) {
            PodsStore.update { it.copy(status = "蓝牙不可用") }
            return
        }
        if (PodsStore.state.value.connecting || link.isConnected) return
        val dev = try { adapter.getRemoteDevice(addr) } catch (t: Throwable) {
            PodsStore.log("无效地址: ${t.message}"); return
        }
        val name = dev.name ?: "AirPods"
        registerCallListener()
        PodsStore.update { it.copy(connecting = true, status = "连接中…", deviceName = name, deviceAddress = addr) }
        refreshNotif()
        worker.execute {
            runCatching { adapter.cancelDiscovery() }
            var ok = false
            for (n in 1..attempts) {
                if (n > 1) Thread.sleep(2500)
                ok = link.connect(dev)
                if (ok) break
                PodsStore.log("第 $n/$attempts 次连接失败")
            }
            if (ok) PodsStore.rememberDevice(name, addr)
            else PodsStore.log("—— 接口探测 ——\n" + link.probe())
            PodsStore.update {
                it.copy(
                    connecting = false, connected = ok,
                    status = if (ok) "已连接" else "AACP 连接失败（已切换到 BLE 电量兜底，详情见「诊断」页）"
                )
            }
            updateScan()
            refreshNotif()
        }
    }

    /** 没连上 AACP 且开启兜底时，用 BLE 广播读取电量。 */
    private fun updateScan() {
        val s = PodsStore.state.value
        if (!s.connected && s.bleFallback) BleBattery.start(this) else BleBattery.stop(this)
    }

    // ---------- 事件处理 ----------
    private fun handle(e: AapEvent) {
        when (e) {
            is AapEvent.Batt -> { PodsStore.update { it.copy(battery = it.battery + e.parts) }; refreshNotif() }
            is AapEvent.Noise -> { PodsStore.update { it.copy(noise = e.mode ?: it.noise) }; refreshNotif() }
            is AapEvent.EarDet -> { PodsStore.update { it.copy(ear = e.primary to e.secondary) }; onEar(e) }
            is AapEvent.CaLevel -> onConversation(e.level)
            is AapEvent.CaEnabled -> PodsStore.update { it.copy(convAware = e.on) }
            is AapEvent.AdaptiveLvl -> PodsStore.update { it.copy(adaptiveLevel = e.level) }
            is AapEvent.Metadata -> PodsStore.update { it.copy(meta = e.meta) }
            is AapEvent.Head -> onHead(e.sample)
            is AapEvent.Other -> PodsStore.log("未识别包 op=0x%04X %s".format(e.opcode, e.hex))
        }
    }

    private fun media(key: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    private fun onEar(e: AapEvent.EarDet) {
        val s = PodsStore.state.value
        if (!s.autoPause) return
        val inEar = listOf(e.primary, e.secondary).count { it == Ear.IN_EAR }
        val shouldPause = if (s.pauseOnAnyRemoval) inEar < 2 else inEar == 0
        if (shouldPause && am.isMusicActive) {
            media(KeyEvent.KEYCODE_MEDIA_PAUSE); pausedByUs = true
        } else if (!shouldPause && pausedByUs) {
            media(KeyEvent.KEYCODE_MEDIA_PLAY); pausedByUs = false
        }
    }

    private fun onConversation(level: Int) {
        if (!PodsStore.state.value.caDuck) return
        if (level == 1 || level == 2) {
            if (duckedFrom == null) {
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                duckedFrom = cur
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max(1, (cur * 0.25).toInt()), 0)
            }
        } else if (level == 3 || level >= 8) {
            restoreVolume()
        }
    }

    private fun restoreVolume() {
        duckedFrom?.let { am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        duckedFrom = null
    }

    // ---------- 头部手势 / 来电 ----------
    private fun onHead(s: HeadSample) {
        val now = System.currentTimeMillis()
        if (now - lastHeadMs > 60) { lastHeadMs = now; PodsStore.update { it.copy(head = s) } }
        val g = Gestures.feed(s, now) ?: return
        PodsStore.log("识别到手势: ${if (g == "nod") "点头" else "摇头"}")
        if (!ringing || !PodsStore.state.value.gestureCalls) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return
        val tm = getSystemService(TelecomManager::class.java)
        runCatching {
            if (g == "nod") tm.acceptRingingCall() else endCallCompat(tm)
        }.onFailure { PodsStore.log("通话操作失败: ${it.message}") }
        PodsController.headTracking(false)
    }

    @Suppress("DEPRECATION")
    private fun endCallCompat(tm: TelecomManager) { tm.endCall() }

    private fun registerCallListener() {
        if (callCb != null) return
        if (Build.VERSION.SDK_INT < 31) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        registerCallListener31()
    }

    @RequiresApi(31)
    private fun registerCallListener31() {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                ringing = state == TelephonyManager.CALL_STATE_RINGING
                val s = PodsStore.state.value
                if (s.gestureCalls && s.connected && (Gestures.nod != null || Gestures.shake != null)) {
                    PodsController.headTracking(ringing)
                }
            }
        }
        callCb = cb
        runCatching { getSystemService(TelephonyManager::class.java).registerTelephonyCallback(mainExecutor, cb) }
            .onFailure { PodsStore.log("来电监听失败: ${it.message}") }
    }

    // ---------- 通知 ----------
    private fun refreshNotif() = nm.notify(NOTIF_ID, buildNotif())

    private fun buildNotif(): Notification {
        val ui = PodsStore.state.value
        val swap = ui.swapLeftRight
        fun pct(p: Part): String {
            val real = if (!swap) p else when (p) { Part.LEFT -> Part.RIGHT; Part.RIGHT -> Part.LEFT; else -> p }
            val v = ui.battery[real]?.level
                ?: if (!ui.connected) ui.ble?.let { b -> when (p) { Part.LEFT -> b.left; Part.RIGHT -> b.right; Part.CASE -> b.case } } else null
            return v?.let { "$it%" } ?: "--"
        }
        val hasBattery = ui.battery.isNotEmpty() || (!ui.connected && ui.ble != null)
        val text = when {
            ui.connected || hasBattery -> "左 ${pct(Part.LEFT)}  右 ${pct(Part.RIGHT)}  盒 ${pct(Part.CASE)}" + if (ui.connected) " · ${ui.noise?.label ?: "—"}" else " · BLE"
            else -> ui.status
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun act(m: NoiseMode) = NotificationCompat.Action(
            0, m.label,
            PendingIntent.getService(
                this, m.code,
                Intent(this, PodsService::class.java).setAction(ACTION_MODE).putExtra(EXTRA_MODE, m.code),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentTitle(ui.deviceName.ifEmpty { "PodsBridge" })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (ui.connected) b.addAction(act(NoiseMode.ANC)).addAction(act(NoiseMode.TRANSPARENCY)).addAction(act(NoiseMode.ADAPTIVE))
        return b.build()
    }

    override fun onDestroy() {
        restoreVolume()
        unregisterAcl()
        BleBattery.stop(this)
        link.close()
        PodsController.link = null
        Effects.unregister(this)
        if (Build.VERSION.SDK_INT >= 31) {
            (callCb as? TelephonyCallback)?.let { runCatching { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) } }
        }
        super.onDestroy()
    }
}
