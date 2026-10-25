package app.podsbridge.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import app.podsbridge.PodsStore
import app.podsbridge.Prefs
import kotlin.math.ln

data class FxUi(
    val probed: Boolean = false,
    val enabled: Boolean = false,
    val freqsHz: List<Int> = emptyList(),
    val minMb: Int = -1500,
    val maxMb: Int = 1500,
    val bands: List<Int> = emptyList(),
    val bass: Int = 0,
    val virt: Int = 0,
    val sessions: Int = 0,
    val globalAttached: Boolean? = null,
    val preset: String = "平直",
)

/**
 * 系统级音效：对每个播放器广播的音频会话挂载 Equalizer / BassBoost / Virtualizer，并尝试全局会话 0。
 * 这是 Android 允许普通 App 做到的上限——只对会广播音频会话的播放器生效。
 */
object Effects {
    private class Chain(val eq: Equalizer?, val bass: BassBoost?, val virt: Virtualizer?)

    private val chains = HashMap<Int, Chain>()
    val ui = MutableStateFlow(FxUi())

    private val PRESETS = linkedMapOf(
        "平直" to listOf(0, 0, 0, 0, 0),
        "低音增强" to listOf(6, 4, 0, 0, 1),
        "人声清晰" to listOf(-2, 0, 4, 3, -1),
        "明亮" to listOf(-1, 0, 1, 4, 6),
        "舒缓" to listOf(3, 1, -1, -2, -3),
    )
    val presetNames: List<String> get() = PRESETS.keys.toList()
    private val REF = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)

    private fun interp(f: Double, g: List<Int>): Double {
        if (f <= REF.first()) return g.first().toDouble()
        if (f >= REF.last()) return g.last().toDouble()
        for (i in 0 until REF.size - 1) {
            if (f <= REF[i + 1]) {
                val t = (ln(f) - ln(REF[i])) / (ln(REF[i + 1]) - ln(REF[i]))
                return g[i] + (g[i + 1] - g[i]) * t
            }
        }
        return 0.0
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session < 0) return
            if (i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) attach(session)
            else release(session)
        }
    }

    fun load() {
        ui.update {
            it.copy(
                enabled = Prefs.bool("fx_on", false), bass = Prefs.int("fx_bass", 0),
                virt = Prefs.int("fx_virt", 0), preset = Prefs.str("fx_preset", "平直")
            )
        }
    }

    private fun save() {
        val s = ui.value
        Prefs.putBool("fx_on", s.enabled); Prefs.putInt("fx_bass", s.bass); Prefs.putInt("fx_virt", s.virt)
        Prefs.putStr("fx_preset", s.preset); Prefs.putStr("fx_bands", s.bands.joinToString(","))
    }

    fun register(ctx: Context) {
        val f = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        probe()
        if (ui.value.enabled) attach(0)
    }

    fun unregister(ctx: Context) {
        runCatching { ctx.unregisterReceiver(receiver) }
        synchronized(chains) { chains.keys.toList() }.forEach { release(it) }
    }

    /** 读取设备的均衡器规格（频段数、频率、增益范围）。 */
    fun probe() {
        if (ui.value.probed) return
        try {
            val e = Equalizer(0, 0)
            val n = e.numberOfBands.toInt()
            val freqs = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
            val r = e.bandLevelRange
            e.release()
            val saved = Prefs.str("fx_bands", "").split(",").mapNotNull { it.toIntOrNull() }
            ui.update {
                it.copy(
                    probed = true, freqsHz = freqs, minMb = r[0].toInt(), maxMb = r[1].toInt(),
                    bands = if (saved.size == n) saved else List(n) { 0 }
                )
            }
        } catch (t: Throwable) {
            PodsStore.log("均衡器探测失败: ${t.message}")
            ui.update { it.copy(probed = true, freqsHz = listOf(60, 230, 910, 3600, 14000), bands = List(5) { 0 }) }
        }
    }

    fun attach(session: Int) {
        synchronized(chains) {
            if (chains.containsKey(session)) return
            val eq = runCatching { Equalizer(0, session) }.getOrNull()
            val bass = runCatching { BassBoost(0, session) }.getOrNull()
            val virt = runCatching { Virtualizer(0, session) }.getOrNull()
            if (eq == null && bass == null && virt == null) {
                if (session == 0) ui.update { it.copy(globalAttached = false) }
                return
            }
            chains[session] = Chain(eq, bass, virt)
            if (session == 0) ui.update { it.copy(globalAttached = true) }
        }
        applyTo(session)
        ui.update { it.copy(sessions = synchronized(chains) { chains.size }) }
    }

    fun release(session: Int) {
        synchronized(chains) {
            chains.remove(session)?.let { c ->
                runCatching { c.eq?.release() }; runCatching { c.bass?.release() }; runCatching { c.virt?.release() }
            }
        }
        ui.update { it.copy(sessions = synchronized(chains) { chains.size }) }
    }

    private fun applyTo(session: Int) {
        val s = ui.value
        val c = synchronized(chains) { chains[session] } ?: return
        runCatching {
            c.eq?.let { eq ->
                eq.setEnabled(s.enabled)
                for (i in 0 until minOf(eq.numberOfBands.toInt(), s.bands.size)) {
                    eq.setBandLevel(i.toShort(), s.bands[i].coerceIn(s.minMb, s.maxMb).toShort())
                }
            }
        }
        runCatching {
            c.bass?.let {
                if (it.strengthSupported) it.setStrength(s.bass.toShort())
                it.setEnabled(s.enabled && s.bass > 0)
            }
        }
        runCatching {
            c.virt?.let {
                if (it.strengthSupported) it.setStrength(s.virt.toShort())
                it.setEnabled(s.enabled && s.virt > 0)
            }
        }
    }

    private fun applyAll() = synchronized(chains) { chains.keys.toList() }.forEach { applyTo(it) }

    fun setEnabled(on: Boolean) {
        ui.update { it.copy(enabled = on) }
        if (on && synchronized(chains) { !chains.containsKey(0) }) attach(0)
        applyAll(); save()
    }

    fun setBand(i: Int, mb: Int) {
        ui.update { s -> s.copy(bands = s.bands.toMutableList().also { if (i in it.indices) it[i] = mb }, preset = "自定义") }
        applyAll(); save()
    }

    fun setPreset(name: String) {
        val g = PRESETS[name] ?: return
        ui.update { s ->
            s.copy(
                preset = name,
                bands = s.freqsHz.map { f -> (interp(f.toDouble(), g) * 100).toInt().coerceIn(s.minMb, s.maxMb) }
            )
        }
        applyAll(); save()
    }

    fun setBass(v: Int) { ui.update { it.copy(bass = v) }; applyAll(); save() }
    fun setVirt(v: Int) { ui.update { it.copy(virt = v) }; applyAll(); save() }
}
