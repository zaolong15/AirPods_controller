package app.podsbridge.aap

enum class NoiseMode(val code: Int, val label: String) {
    OFF(1, "关闭"), ANC(2, "降噪"), TRANSPARENCY(3, "通透"), ADAPTIVE(4, "自适应");

    companion object {
        fun from(code: Int): NoiseMode? = entries.firstOrNull { it.code == code }
    }
}

enum class Part { LEFT, RIGHT, CASE }
enum class Charge { UNKNOWN, CHARGING, DISCHARGING, DISCONNECTED }
enum class Ear { IN_EAR, OUT, IN_CASE, UNKNOWN }

data class Battery(val level: Int, val charge: Charge)
data class Meta(val name: String?, val model: String?, val manufacturer: String?, val serial: String?, val firmware: String?)
data class HeadSample(val o1: Int, val o2: Int, val o3: Int, val ax: Int, val ay: Int)

sealed interface AapEvent {
    data class Batt(val parts: Map<Part, Battery>) : AapEvent
    data class Noise(val mode: NoiseMode?, val raw: Int) : AapEvent
    data class EarDet(val primary: Ear, val secondary: Ear) : AapEvent
    data class CaLevel(val level: Int) : AapEvent
    data class CaEnabled(val on: Boolean) : AapEvent
    data class AdaptiveLvl(val level: Int) : AapEvent
    data class Metadata(val meta: Meta) : AapEvent
    data class Head(val sample: HeadSample) : AapEvent
    data class Other(val opcode: Int, val hex: String) : AapEvent
}

/** Apple Accessory Protocol：L2CAP PSM 0x1001。字节定义来自 LibrePods 的 AAP Definitions（AirPods Pro 2 USB-C 固件 7A305）。 */
object Aap {
    const val PSM = 0x1001

    private fun h(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    val HANDSHAKE = h("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")
    val ENABLE_FEATURES = h("04 00 04 00 4D 00 FF 00 00 00 00 00 00 00")
    val REQUEST_NOTIFICATIONS = h("04 00 04 00 0F 00 FF FF FF FF")
    val START_HEAD_TRACKING = h("04 00 04 00 17 00 00 00 10 00 10 00 08 A1 02 42 0B 08 0E 10 02 1A 05 01 40 9C 00 00")
    val STOP_HEAD_TRACKING = h("04 00 04 00 17 00 00 00 10 00 11 00 08 7E 10 02 42 0B 08 4E 10 02 1A 05 01 00 00 00 00")

    private fun control(id: Int, value: Int) =
        byteArrayOf(4, 0, 4, 0, 9, 0, id.toByte(), value.toByte(), 0, 0, 0)

    fun setNoiseMode(m: NoiseMode) = control(0x0D, m.code)
    fun setConversationAwareness(on: Boolean) = control(0x28, if (on) 1 else 2)
    fun setAdaptiveLevel(level: Int) = control(0x2E, level.coerceIn(0, 100))

    fun rename(name: String): ByteArray {
        val all = name.toByteArray(Charsets.UTF_8)
        val n = all.copyOf(minOf(all.size, 200))
        return byteArrayOf(4, 0, 4, 0, 0x1A, 0, 1, n.size.toByte(), 0) + n
    }

    private fun u(x: Byte) = x.toInt() and 0xFF
    private fun s16(b: ByteArray, i: Int) = ((u(b[i + 1]) shl 8) or u(b[i])).toShort().toInt()
    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private fun ear(x: Byte) = when (u(x)) {
        0 -> Ear.IN_EAR
        1 -> Ear.OUT
        2 -> Ear.IN_CASE
        else -> Ear.UNKNOWN
    }

    fun parse(b: ByteArray): AapEvent? {
        if (b.size < 6) return null
        if (b[0].toInt() != 4 || b[1].toInt() != 0 || b[2].toInt() != 4 || b[3].toInt() != 0)
            return AapEvent.Other(-1, hex(b))
        val op = u(b[4]) or (u(b[5]) shl 8)
        return when (op) {
            0x0004 -> parseBattery(b)
            0x0006 -> if (b.size >= 8) AapEvent.EarDet(ear(b[6]), ear(b[7])) else null
            0x0009 -> parseControl(b)
            0x004B -> if (b.size >= 10) AapEvent.CaLevel(u(b[9])) else null
            0x001D -> parseMeta(b)
            0x0017 -> if (b.size >= 55)
                AapEvent.Head(HeadSample(s16(b, 43), s16(b, 45), s16(b, 47), s16(b, 51), s16(b, 53))) else null
            else -> AapEvent.Other(op, hex(b.copyOf(minOf(b.size, 32))))
        }
    }

    private fun parseControl(b: ByteArray): AapEvent? {
        if (b.size < 8) return null
        val id = u(b[6])
        val v = u(b[7])
        return when (id) {
            0x0D -> AapEvent.Noise(NoiseMode.from(v), v)
            0x28 -> AapEvent.CaEnabled(v == 1)
            0x2E -> AapEvent.AdaptiveLvl(v.coerceIn(0, 100))
            else -> AapEvent.Other(0x0009, hex(b))
        }
    }

    private fun parseBattery(b: ByteArray): AapEvent? {
        if (b.size < 7) return null
        val count = u(b[6])
        val out = LinkedHashMap<Part, Battery>()
        var i = 7
        for (n in 0 until count) {
            if (i + 4 >= b.size) break
            val part = when (u(b[i])) {
                0x04 -> Part.LEFT
                0x02 -> Part.RIGHT
                0x08 -> Part.CASE
                else -> null
            }
            val charge = when (u(b[i + 3])) {
                1 -> Charge.CHARGING
                2 -> Charge.DISCHARGING
                4 -> Charge.DISCONNECTED
                else -> Charge.UNKNOWN
            }
            if (part != null) out[part] = Battery(u(b[i + 2]).coerceIn(0, 100), charge)
            i += 5
        }
        return AapEvent.Batt(out)
    }

    private fun parseMeta(b: ByteArray): AapEvent? {
        var i = 6
        while (i < b.size) {
            var j = i
            while (j < b.size && b[j].toInt() in 0x20..0x7E) j++
            if (j - i >= 4 && j < b.size && b[j].toInt() == 0) {
                val strs = ArrayList<String>()
                var k = i
                while (k < b.size && strs.size < 5) {
                    var e = k
                    while (e < b.size && b[e].toInt() != 0) e++
                    strs.add(String(b, k, e - k, Charsets.UTF_8))
                    k = e + 1
                }
                return AapEvent.Metadata(
                    Meta(strs.getOrNull(0), strs.getOrNull(1), strs.getOrNull(2), strs.getOrNull(3), strs.getOrNull(4))
                )
            }
            i = if (j > i) j else i + 1
        }
        return null
    }
}
