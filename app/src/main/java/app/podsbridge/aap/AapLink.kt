package app.podsbridge.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors

/** 通过 L2CAP PSM 0x1001 连接 AirPods。依次尝试多种建立 socket 的办法，并把每一步结果写进日志，便于在真机上诊断。 */
@SuppressLint("MissingPermission")
class AapLink(private val log: (String) -> Unit) {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false
    private val sender = Executors.newSingleThreadExecutor()

    var onEvent: (AapEvent) -> Unit = {}
    var onClosed: (String) -> Unit = {}

    val isConnected: Boolean get() = socket?.isConnected == true

    private fun unlockHiddenApi() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
            .onFailure { log("HiddenApiBypass 失败: ${it.message}") }
    }

    private fun hidden(d: BluetoothDevice, name: String): BluetoothSocket {
        val m = BluetoothDevice::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(d, Aap.PSM) as BluetoothSocket
    }

    /** 阻塞调用，请在后台线程执行。 */
    fun connect(device: BluetoothDevice): Boolean {
        close()
        closing = false
        unlockHiddenApi()
        val strategies = listOf<Pair<String, () -> BluetoothSocket>>(
            "公开 API createL2capChannel" to { device.createL2capChannel(Aap.PSM) },
            "公开 API createInsecureL2capChannel" to { device.createInsecureL2capChannel(Aap.PSM) },
            "隐藏 API createL2capSocket" to { hidden(device, "createL2capSocket") },
            "隐藏 API createInsecureL2capSocket" to { hidden(device, "createInsecureL2capSocket") },
        )
        for ((name, make) in strategies) {
            var s: BluetoothSocket? = null
            try {
                log("尝试: $name")
                s = make()
                s.connect()
                socket = s
                out = s.outputStream
                log("✓ $name 连接成功")
                startReader(s)
                handshake()
                return true
            } catch (t: Throwable) {
                val c = (t as? InvocationTargetException)?.targetException ?: t
                log("✗ $name: ${c.javaClass.simpleName}: ${c.message}")
                runCatching { s?.close() }
            }
        }
        return false
    }

    private fun handshake() {
        send(Aap.HANDSHAKE)
        Thread.sleep(150)
        send(Aap.ENABLE_FEATURES)
        Thread.sleep(100)
        send(Aap.REQUEST_NOTIFICATIONS)
    }

    fun send(b: ByteArray) {
        sender.execute {
            try {
                out?.write(b)
                out?.flush()
            } catch (t: Throwable) {
                log("发送失败: ${t.message}")
            }
        }
    }

    private fun startReader(s: BluetoothSocket) {
        Thread({
            val buf = ByteArray(4096)
            try {
                val inp = s.inputStream
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    Aap.parse(buf.copyOf(n))?.let(onEvent)
                }
                if (!closing) onClosed("对端关闭了连接")
            } catch (t: Throwable) {
                if (!closing) onClosed("读取中断: ${t.message}")
            }
        }, "aap-reader").start()
    }

    fun close() {
        closing = true
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    /** 列出系统里与 L2CAP 相关的隐藏接口签名，连接失败时把这段日志发回来就能针对性调整。 */
    fun probe(): String {
        unlockHiddenApi()
        return buildString {
            appendLine("== BluetoothSocket 构造器 ==")
            runCatching { BluetoothSocket::class.java.declaredConstructors.forEach { appendLine(it.toString()) } }
                .onFailure { appendLine("失败: ${it.message}") }
            appendLine("== BluetoothSocket TYPE_* ==")
            runCatching {
                BluetoothSocket::class.java.declaredFields.filter { it.name.startsWith("TYPE_") }.forEach {
                    it.isAccessible = true
                    appendLine("${it.name}=${runCatching { it.get(null) }.getOrNull()}")
                }
            }.onFailure { appendLine("失败: ${it.message}") }
            appendLine("== BluetoothDevice 中含 l2cap 的方法 ==")
            runCatching {
                BluetoothDevice::class.java.declaredMethods.filter { it.name.contains("l2cap", true) }
                    .forEach { appendLine(it.toString()) }
            }.onFailure { appendLine("失败: ${it.message}") }
        }
    }
}
