package app.podsbridge

import app.podsbridge.aap.*
import app.podsbridge.ble.BleReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class PodsUi(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val status: String = "未连接",
    val deviceName: String = "",
    val deviceAddress: String = "",
    val battery: Map<Part, Battery> = emptyMap(),
    val ear: Pair<Ear, Ear> = Ear.UNKNOWN to Ear.UNKNOWN,
    val noise: NoiseMode? = null,
    val convAware: Boolean? = null,
    val adaptiveLevel: Int = 50,
    val meta: Meta? = null,
    val head: HeadSample? = null,
    val headTracking: Boolean = false,
    val logs: List<String> = emptyList(),
    val ble: BleReading? = null,
    // 持久化设置
    val autoPause: Boolean = true,
    val pauseOnAnyRemoval: Boolean = false,
    val caDuck: Boolean = true,
    val swapLeftRight: Boolean = false,
    val standby: Boolean = false,
    val bleFallback: Boolean = true,
    val gestureCalls: Boolean = false,
)

object PodsStore {
    val state = MutableStateFlow(PodsUi())

    /** 运行时状态更新（不落盘）。 */
    fun update(f: (PodsUi) -> PodsUi) = state.update(f)

    /** 设置类更新：同时写入本地存储。 */
    fun setting(f: (PodsUi) -> PodsUi) {
        state.update(f)
        val s = state.value
        Prefs.putBool("autoPause", s.autoPause)
        Prefs.putBool("pauseAny", s.pauseOnAnyRemoval)
        Prefs.putBool("caDuck", s.caDuck)
        Prefs.putBool("swapLR", s.swapLeftRight)
        Prefs.putBool("standby", s.standby)
        Prefs.putBool("bleFallback", s.bleFallback)
        Prefs.putBool("gestureCalls", s.gestureCalls)
    }

    fun load() {
        state.update {
            it.copy(
                autoPause = Prefs.bool("autoPause", true),
                pauseOnAnyRemoval = Prefs.bool("pauseAny", false),
                caDuck = Prefs.bool("caDuck", true),
                swapLeftRight = Prefs.bool("swapLR", false),
                standby = Prefs.bool("standby", false),
                bleFallback = Prefs.bool("bleFallback", true),
                gestureCalls = Prefs.bool("gestureCalls", false),
                deviceAddress = Prefs.str("addr", ""),
                deviceName = Prefs.str("name", ""),
            )
        }
    }

    fun rememberDevice(name: String, addr: String) {
        Prefs.putStr("addr", addr)
        Prefs.putStr("name", name)
    }

    fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        state.update { it.copy(logs = (it.logs + "[$t] $msg").takeLast(300)) }
    }
}

/** UI 与 Service 之间的轻量桥。 */
object PodsController {
    @Volatile var link: AapLink? = null

    fun setNoise(m: NoiseMode) {
        val active = link
        if (active?.isConnected != true) {
            PodsStore.log("未发送降噪指令：AAP 控制通道未连接")
            return
        }
        PodsStore.log("请求切换降噪模式：${m.label}（等待 AirPods 确认）")
        active.send(Aap.setNoiseMode(m))
    }

    fun cycleNoise() {
        val order = listOf(NoiseMode.ANC, NoiseMode.TRANSPARENCY, NoiseMode.ADAPTIVE, NoiseMode.OFF)
        val cur = PodsStore.state.value.noise
        val next = order[(order.indexOf(cur) + 1).mod(order.size)]
        setNoise(next)
    }

    fun setAdaptiveLevel(v: Int) {
        link?.send(Aap.setAdaptiveLevel(v))
        PodsStore.update { it.copy(adaptiveLevel = v) }
    }

    fun setConvAware(on: Boolean) {
        link?.send(Aap.setConversationAwareness(on))
        PodsStore.update { it.copy(convAware = on) }
    }

    fun rename(name: String) {
        if (name.isNotBlank()) link?.send(Aap.rename(name.trim()))
    }

    fun headTracking(on: Boolean) {
        link?.send(if (on) Aap.START_HEAD_TRACKING else Aap.STOP_HEAD_TRACKING)
        PodsStore.update { it.copy(headTracking = on) }
    }
}
