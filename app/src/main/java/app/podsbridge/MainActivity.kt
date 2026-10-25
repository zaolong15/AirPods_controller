package app.podsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.podsbridge.aap.*
import app.podsbridge.audio.Effects

private val WarmLight = lightColorScheme(
    primary = Color(0xFFB4532A), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCD), onPrimaryContainer = Color(0xFF3B1100),
    secondaryContainer = Color(0xFFF3DED5),
    background = Color(0xFFFDF2EC), onBackground = Color(0xFF3B2A22),
    surface = Color(0xFFFFF8F4), onSurface = Color(0xFF3B2A22),
    surfaceVariant = Color(0xFFF3DED5), onSurfaceVariant = Color(0xFF52443D),
)
private val WarmDark = darkColorScheme(
    primary = Color(0xFFFFB59A), onPrimary = Color(0xFF5B1B00),
    primaryContainer = Color(0xFF813A15), onPrimaryContainer = Color(0xFFFFDBCD),
    background = Color(0xFF1F1612), onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF281D18), onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF52443D), onSurfaceVariant = Color(0xFFD7C2B9),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) WarmDark else WarmLight) { App() }
        }
    }
}

private fun hasPerm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.BLUETOOTH_CONNECT)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerm(ctx, Manifest.permission.BLUETOOTH_CONNECT)
    }
    LaunchedEffect(Unit) {
        Effects.probe()
        if (!granted) {
            val list = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            launcher.launch(list.toTypedArray())
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("控制", "音效", "头部", "诊断").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(label.take(1)) }, label = { Text(label) })
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!granted) {
                Card { Column(Modifier.padding(16.dp)) {
                    Text("需要「附近的设备」权限才能连接 AirPods")
                    Button(onClick = { launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) }) { Text("授予权限") }
                } }
            }
            when (tab) {
                0 -> ControlTab()
                1 -> EffectsTab()
                2 -> HeadTab()
                else -> DiagTab()
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(ctx: Context): List<Pair<String, String>> {
    val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return try {
        ad.bondedDevices.map { (it.name ?: "未知设备") to it.address }
            .sortedByDescending { it.first.contains("AirPods", true) }
    } catch (_: SecurityException) { emptyList() }
}

private fun startPods(ctx: Context, action: String, addr: String? = null) {
    if (!hasPerm(ctx, Manifest.permission.BLUETOOTH_CONNECT)) return
    val i = Intent(ctx, PodsService::class.java).setAction(action)
    if (addr != null) i.putExtra(PodsService.EXTRA_ADDR, addr)
    ContextCompat.startForegroundService(ctx, i)
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BatteryRow(label: String, b: Battery?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.padding(end = 4.dp))
        LinearProgressIndicator(progress = { (b?.level ?: 0) / 100f }, modifier = Modifier.weight(1f))
        Text(if (b == null) "--" else "${b.level}%" + if (b.charge == Charge.CHARGING) " ⚡" else "")
    }
}

@Composable
private fun ControlTab() {
    val ctx = LocalContext.current
    val ui by PodsStore.state.collectAsState()

    SectionCard(ui.deviceName.ifEmpty { "AirPods" }) {
        Text(ui.status)
        val swap = ui.swapLeftRight
        val l = ui.battery[if (swap) Part.RIGHT else Part.LEFT]
        val r = ui.battery[if (swap) Part.LEFT else Part.RIGHT]
        BatteryRow("左", l); BatteryRow("右", r); BatteryRow("盒", ui.battery[Part.CASE])
        ui.meta?.let { Text("${it.name ?: ""} · ${it.model ?: ""} · 固件 ${it.firmware ?: ""}", fontSize = 12.sp) }
        Text("佩戴：${ui.ear.first.name} / ${ui.ear.second.name}", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.connected || ui.connecting) {
                OutlinedButton(onClick = { startPods(ctx, PodsService.ACTION_DISCONNECT) }) { Text("断开") }
            }
        }
    }

    val ble = ui.ble
    if (!ui.connected && ble != null) {
        SectionCard("离线电量（BLE 广播，实验）") {
            fun f(v: Int?, c: Boolean) = (v?.let { "$it%" } ?: "--") + if (c) " ⚡" else ""
            val l = if (ui.swapLeftRight) f(ble.right, ble.chargeR) else f(ble.left, ble.chargeL)
            val r = if (ui.swapLeftRight) f(ble.left, ble.chargeL) else f(ble.right, ble.chargeR)
            Text("左 $l　右 $r　盒 ${f(ble.case, ble.chargeCase)}")
            Text("信号 ${ble.rssi} dBm。取的是附近信号最强的一副 AirPods，周围有别人的可能读到别人的。", fontSize = 12.sp)
        }
    }

    if (!ui.connected) {
        SectionCard("选择已配对的设备") {
            val devices = remember(ui.status) { bondedDevices(ctx) }
            if (devices.isEmpty()) Text("没有已配对设备，或还没授予蓝牙权限。请先在系统设置里把 AirPods 配对。")
            devices.forEach { (name, addr) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, Modifier.weight(1f))
                    Button(enabled = !ui.connecting, onClick = { startPods(ctx, PodsService.ACTION_CONNECT, addr) }) { Text("连接") }
                }
            }
        }
    }

    SectionCard("噪声控制") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoiseMode.entries.forEach { m ->
                FilterChip(selected = ui.noise == m, enabled = ui.connected, onClick = { PodsController.setNoise(m) }, label = { Text(m.label) })
            }
        }
        var lvl by remember(ui.adaptiveLevel) { mutableStateOf(ui.adaptiveLevel.toFloat()) }
        Text("自适应强度：${lvl.toInt()}（0=放进更多环境音，100=更强降噪，仅自适应模式有效）", fontSize = 12.sp)
        Slider(value = lvl, onValueChange = { lvl = it }, valueRange = 0f..100f, enabled = ui.connected,
            onValueChangeFinished = { PodsController.setAdaptiveLevel(lvl.toInt()) })
        ToggleRow("对话感知（AirPods 端）", ui.convAware == true) { if (ui.connected) PodsController.setConvAware(it) }
    }

    SectionCard("自动化") {
        ToggleRow("摘下耳机自动暂停 / 戴上恢复", ui.autoPause) { v -> PodsStore.setting { it.copy(autoPause = v) } }
        ToggleRow("摘下任意一只就暂停", ui.pauseOnAnyRemoval) { v -> PodsStore.setting { it.copy(pauseOnAnyRemoval = v) } }
        ToggleRow("你说话时自动降低媒体音量", ui.caDuck) { v -> PodsStore.setting { it.copy(caDuck = v) } }
        ToggleRow("交换左右电量显示（若与实际相反）", ui.swapLeftRight) { v -> PodsStore.setting { it.copy(swapLeftRight = v) } }
        ToggleRow("待命模式：AirPods 连上手机后自动接管（常驻通知）", ui.standby) { on ->
            startPods(ctx, if (on) PodsService.ACTION_STANDBY else PodsService.ACTION_STOP_STANDBY)
        }
        if (ui.standby && ui.deviceAddress.isEmpty()) Text("待命需要先手动连接成功一次，用来记住你的 AirPods。", fontSize = 12.sp)
        ToggleRow("连不上时用 BLE 广播读取电量（实验）", ui.bleFallback) { v -> PodsStore.setting { it.copy(bleFallback = v) } }
        Text("小米系统请把本 App 设为「无限制」省电策略并允许自启动，否则待命模式会被系统杀掉。", fontSize = 12.sp)
    }

    SectionCard("重命名") {
        var name by remember { mutableStateOf("") }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("新名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = ui.connected && name.isNotBlank(), onClick = { PodsController.rename(name) }) { Text("应用") }
        Text("改名后需要在系统里取消配对再重新配对，名字才会在安卓端生效。", fontSize = 12.sp)
    }
}

@Composable
private fun EffectsTab() {
    val ctx = LocalContext.current
    val fx by Effects.ui.collectAsState()

    SectionCard("均衡器 / 音效") {
        ToggleRow("启用", fx.enabled) { Effects.setEnabled(it) }
        Text("已挂载的播放器音频会话：${fx.sessions}　全局会话：${when (fx.globalAttached) { true -> "可用"; false -> "被系统拒绝"; null -> "未尝试" }}", fontSize = 12.sp)
        Text("只对会广播音频会话的播放器生效（Spotify、YouTube Music、多数本地播放器）。若系统自带的小米音效 / 杜比处于开启状态，可能与之冲突。", fontSize = 12.sp)
        Effects.presetNames.chunked(3).forEach { rowPresets ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowPresets.forEach { preset ->
                    FilterChip(selected = fx.preset == preset, onClick = { Effects.setPreset(preset) }, label = { Text(preset) })
                }
            }
        }
        fx.freqsHz.forEachIndexed { i, hz ->
            val v = fx.bands.getOrElse(i) { 0 }
            Text("${if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz"}：${"%+.1f".format(v / 100f)} dB", fontSize = 12.sp)
            Slider(value = v.toFloat(), onValueChange = { Effects.setBand(i, it.toInt()) }, valueRange = fx.minMb.toFloat()..fx.maxMb.toFloat())
        }
        Text("低音增强：${fx.bass / 10}%", fontSize = 12.sp)
        Slider(value = fx.bass.toFloat(), onValueChange = { Effects.setBass(it.toInt()) }, valueRange = 0f..1000f)
        Text("虚拟环绕：${fx.virt / 10}%", fontSize = 12.sp)
        Slider(value = fx.virt.toFloat(), onValueChange = { Effects.setVirt(it.toInt()) }, valueRange = 0f..1000f)
    }

    SectionCard("系统空间音频状态（只读）") {
        Text(spatializerInfo(ctx), fontSize = 12.sp)
        Text("Android 的空间音频由系统控制，普通 App 无法替你打开或喂入 AirPods 的头部追踪数据。可以去系统设置里查看。", fontSize = 12.sp)
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("打开系统声音设置") }
    }
}

private fun spatializerInfo(ctx: Context): String = try {
    val sp = ctx.getSystemService(AudioManager::class.java).spatializer
    "可用：${sp.isAvailable}　已开启：${sp.isEnabled}　沉浸级别：${sp.immersiveAudioLevel}　头部追踪器：${sp.isHeadTrackerAvailable}"
} catch (t: Throwable) { "读取失败：${t.message}" }

@Composable
private fun HeadTab() {
    val ctx = LocalContext.current
    val ui by PodsStore.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val nod = remember(ui.logs.size, ui.head) { Gestures.nod }
    val shake = remember(ui.logs.size, ui.head) { Gestures.shake }
    val learning = Gestures.learning

    SectionCard("头部传感器跟踪") {
        Text("此页显示 AirPods 回传的原始朝向与加速度，并用于手势识别；它不会开启 Android 系统空间音频。", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ui.connected && !ui.headTracking, onClick = { PodsController.headTracking(true) }) { Text("开始") }
            OutlinedButton(enabled = ui.connected && ui.headTracking, onClick = { PodsController.headTracking(false) }) { Text("停止") }
        }
        val h = ui.head
        val receivedAt = ui.headUpdatedAtMs?.let {
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))
        }
        val trackingStatus = when {
            !ui.connected -> "请先连接 AirPods。"
            !ui.headTracking -> "跟踪未启动，点击「开始」请求传感器数据。"
            h == null -> "已发送启动请求，尚未收到数据；若一直如此，请检查耳机型号/固件及 AAP 控制连接。"
            else -> "已收到传感器数据 · 最近更新 $receivedAt"
        }
        Text(trackingStatus, fontSize = 12.sp)
        Text(if (h == null) "朝向与加速度：等待数据" else
            "朝向 1/2/3：${h.o1} / ${h.o2} / ${h.o3}\n加速度 水平/垂直：${h.ax} / ${h.ay}")
    }

    SectionCard("点头 / 摇头手势（实验）") {
        Text("朝向数值的轴向和单位没有公开定义，所以需要先各示范一次：点「录入」后，在 3 秒内大幅度重复做动作。", fontSize = 12.sp)
        Text("点头：${if (nod == null) "未录入" else "已录入（轴 ${nod.axis + 1}）"}　摇头：${if (shake == null) "未录入" else "已录入（轴 ${shake.axis + 1}）"}")
        if (learning != null) Text("录入${if (learning == "nod") "点头" else "摇头"}中，请重复动作…")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ui.connected && learning == null, onClick = {
                if (!ui.headTracking) PodsController.headTracking(true)
                Gestures.startLearn("nod")
            }) { Text("录入点头") }
            Button(enabled = ui.connected && learning == null, onClick = {
                if (!ui.headTracking) PodsController.headTracking(true)
                Gestures.startLearn("shake")
            }) { Text("录入摇头") }
        }
        OutlinedButton(onClick = { Gestures.clear() }) { Text("清除录入") }
        ToggleRow("来电时点头接听 / 摇头拒接", ui.gestureCalls) { on ->
            PodsStore.setting { it.copy(gestureCalls = on) }
            if (on) launcher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS))
        }
        Text("授权电话权限后，如手势没反应，请断开再连接一次。来电响铃时才会自动开启头部追踪。", fontSize = 12.sp)
    }
}

@Composable
private fun DiagTab() {
    val ctx = LocalContext.current
    val ui by PodsStore.state.collectAsState()
    val info = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n补丁 ${Build.VERSION.SECURITY_PATCH}\n${Build.DISPLAY}"
    fun copy(text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", text))
    }
    SectionCard("设备信息") { Text(info, fontSize = 12.sp) }
    SectionCard("连接日志") {
        Button(onClick = {
            val probe = AapLink { }.probe()
            copy("PodsBridge 诊断报告\n$info\n状态：${ui.status}\n\n$probe\n\n" + ui.logs.joinToString("\n"))
        }) { Text("复制完整诊断报告") }
        OutlinedButton(onClick = { PodsStore.log(AapLink { PodsStore.log(it) }.probe()) }) { Text("探测蓝牙接口") }
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("打开系统蓝牙设置") }
        Text(if (ui.logs.isEmpty()) "暂无日志" else ui.logs.takeLast(80).joinToString("\n"), fontSize = 11.sp)
    }
}
