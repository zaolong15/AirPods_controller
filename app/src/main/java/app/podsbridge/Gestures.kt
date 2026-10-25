package app.podsbridge

import app.podsbridge.aap.HeadSample
import kotlin.math.max
import kotlin.math.min

/**
 * 点头 / 摇头识别。AirPods 回传的三个朝向数值的轴向与单位没有公开定义，
 * 所以这里不硬编码阈值，而是让用户各做 3 秒示范动作，自动选出变化最大的轴并按幅度设阈值。
 */
object Gestures {
    data class Profile(val axis: Int, val thr: Int)

    @Volatile var nod: Profile? = null
    @Volatile var shake: Profile? = null
    @Volatile var learning: String? = null
        private set

    private var recStart = 0L
    private val recMin = IntArray(3)
    private val recMax = IntArray(3)
    private class S(val t: Long, val v: IntArray)
    private val win = ArrayDeque<S>()
    private var cooldownUntil = 0L

    fun load() {
        nod = loadP("nod")
        shake = loadP("shake")
    }

    private fun loadP(k: String): Profile? {
        val a = Prefs.int("g_${k}_axis", -1)
        return if (a < 0) null else Profile(a, Prefs.int("g_${k}_thr", 1))
    }

    fun startLearn(kind: String, now: Long = System.currentTimeMillis()) {
        recStart = now
        for (i in 0..2) { recMin[i] = Int.MAX_VALUE; recMax[i] = Int.MIN_VALUE }
        learning = kind
    }

    fun clear() {
        nod = null; shake = null
        for (k in listOf("nod", "shake")) { Prefs.putInt("g_${k}_axis", -1) }
    }

    /** 返回 "nod" / "shake" / null。 */
    fun feed(s: HeadSample, now: Long = System.currentTimeMillis()): String? {
        val v = intArrayOf(s.o1, s.o2, s.o3)
        val kind = learning
        if (kind != null) {
            for (i in 0..2) { recMin[i] = min(recMin[i], v[i]); recMax[i] = max(recMax[i], v[i]) }
            if (now - recStart >= 3000) finishLearn(kind)
            return null
        }
        win.addLast(S(now, v))
        while (win.isNotEmpty() && now - win.first().t > 1500) win.removeFirst()
        if (now < cooldownUntil) return null
        val n = nod
        val sh = shake
        if (n != null && matches(n, sh)) { cooldownUntil = now + 2000; win.clear(); return "nod" }
        if (sh != null && matches(sh, n)) { cooldownUntil = now + 2000; win.clear(); return "shake" }
        return null
    }

    private fun finishLearn(kind: String) {
        val other = if (kind == "nod") shake else nod
        var best = -1
        var bestP = 0
        for (i in 0..2) {
            if (other != null && other.axis == i) continue
            val p = recMax[i] - recMin[i]
            if (p > bestP) { bestP = p; best = i }
        }
        learning = null
        if (best < 0 || bestP < 4) {
            PodsStore.log("手势录入失败：没有检测到足够大的头部运动")
            return
        }
        val p = Profile(best, max(2, bestP / 2))
        if (kind == "nod") nod = p else shake = p
        Prefs.putInt("g_${kind}_axis", p.axis)
        Prefs.putInt("g_${kind}_thr", p.thr)
        PodsStore.log("已录入${if (kind == "nod") "点头" else "摇头"}：轴 ${p.axis + 1}，阈值 ${p.thr}（示范幅度 $bestP）")
    }

    private fun p2p(axis: Int): Int {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (s in win) { lo = min(lo, s.v[axis]); hi = max(hi, s.v[axis]) }
        return if (win.isEmpty()) 0 else hi - lo
    }

    private fun reversals(axis: Int, thr: Int): Int {
        if (win.isEmpty()) return 0
        val h = max(1, thr / 2)
        var ext = win.first().v[axis]
        var dir = 0
        var count = 0
        for (s in win) {
            val x = s.v[axis]
            when {
                dir >= 0 && x > ext -> { ext = x; dir = 1 }
                dir > 0 && ext - x >= h -> { dir = -1; ext = x; count++ }
                dir <= 0 && x < ext -> { ext = x; dir = -1 }
                dir < 0 && x - ext >= h -> { dir = 1; ext = x; count++ }
            }
        }
        return count
    }

    private fun matches(p: Profile, other: Profile?): Boolean {
        if (p2p(p.axis) < p.thr) return false
        if (reversals(p.axis, p.thr) < 2) return false
        if (other != null && other.axis != p.axis && p2p(other.axis) >= other.thr * 0.7) return false
        return true
    }
}
