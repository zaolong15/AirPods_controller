package app.podsbridge.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 通过 L2CAP PSM 0x1001 连接 AirPods。依次尝试多种建立 socket 的办法，并把每一步结果写进日志，便于在真机上诊断。 */
@SuppressLint("MissingPermission")
class AapLink(private val log: (String) -> Unit) {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false
    private val sender = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()

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

    /** 直接用 BluetoothSocket 的隐藏构造器建立经典蓝牙 L2CAP（type=3）连接。 */
    private fun ctor(d: BluetoothDevice, secure: Boolean): BluetoothSocket {
        val c = BluetoothSocket::class.java.getDeclaredConstructor(
            BluetoothDevice::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, ParcelUuid::class.java
        )
        c.isAccessible = true
        return c.newInstance(d, 3, secure, secure, Aap.PSM, null) as BluetoothSocket
    }

    /** 阻塞调用，请在后台线程执行。每种办法最多等 8 秒。 */
    fun connect(device: BluetoothDevice): Boolean {
        close()
        closing = false
        unlockHiddenApi()
        val strategies = listOf<Pair<String, () -> BluetoothSocket>>(
            "隐藏 API createL2capSocket（经典蓝牙）" to { hidden(device, "createL2capSocket") },
            "隐藏构造器 L2CAP 加密" to { ctor(device, true) },
            "隐藏构造器 L2CAP 不加密" to { ctor(device, false) },
            "隐藏 API createInsecureL2capSocket" to { hidden(device, "createInsecureL2capSocket") },
            "公开 API createL2capChannel（低功耗通道）" to { device.createL2capChannel(Aap.PSM) },
            "公开 API createInsecureL2capChannel（低功耗通道）" to { device.createInsecureL2capChannel(Aap.PSM) },
        )
        for ((name, make) in strategies) {
            var current: BluetoothSocket? = null
            val t0 = System.currentTimeMillis()
            val watchdog = timer.schedule(Runnable {
                log("  超过 8 秒无响应，中止本次尝试")
                runCatching { current?.close() }
            }, 8, TimeUnit.SECONDS)
            try {
                log("尝试: $name")
                val sock = make()
                current = sock
                sock.connect()
                watchdog.cancel(false)
                socket = sock
                out = sock.outputStream
                log("✓ $name 连接成功")
                startReader(sock)
                handshake()
                return true
            } catch (t: Throwable) {
                watchdog.cancel(false)
                val c = (t as? InvocationTargetException)?.targetException ?: t
                log("✗ $name（${System.currentTimeMillis() - t0}ms）: ${c.javaClass.simpleName}: ${c.message}")
                runCatching { current?.close() }
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
