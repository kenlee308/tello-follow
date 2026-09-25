package com.kenny.tellofollow.tello

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

data class ConnectResult(
    val ok: Boolean,
    val detail: String,
)

/**
 * Tello text SDK.
 *
 * `rc` is fire-and-forget on a dedicated socket. Waiting for replies on the
 * command socket blocked sticks (~2s each) and made controls feel dead.
 * Keep-alive no longer forces rc 0 0 0 0 — the ViewModel 20 Hz loop owns that.
 */
class TelloCommandClient {

    companion object {
        private const val TAG = "TelloCommand"
        const val TELLO_IP = "192.168.10.1"
        const val COMMAND_PORT = 8889
        private const val REPLY_TIMEOUT_MS = 5_000
        private const val BUF_SIZE = 1024
        private const val CONNECT_ATTEMPTS = 8
        const val MAX_RC = 100
    }

    private val cmdMutex = Mutex()

    @Volatile private var cmdSocket: DatagramSocket? = null
    @Volatile private var rcSocket: DatagramSocket? = null
    @Volatile private var telloAddress: InetAddress? = null
    @Volatile var isConnected: Boolean = false
        private set

    private var socketBinder: ((DatagramSocket) -> Unit)? = null

    suspend fun connect(socketBinder: ((DatagramSocket) -> Unit)? = null): ConnectResult =
        withContext(Dispatchers.IO) {
            if (isConnected) return@withContext ConnectResult(true, "already connected")
            disconnect()
            this@TelloCommandClient.socketBinder = socketBinder
            val addr = try {
                InetAddress.getByName(TELLO_IP)
            } catch (e: Exception) {
                return@withContext ConnectResult(false, "DNS/IP fail: ${e.message}")
            }
            telloAddress = addr

            var lastDetail = "no attempt"
            repeat(CONNECT_ATTEMPTS) { attempt ->
                closeSockets()
                try {
                    cmdSocket = openSocket(replyTimeout = true)
                    rcSocket = openSocket(replyTimeout = false)
                    val reply = sendCommandLocked("command")
                    if (reply.equals("ok", ignoreCase = true)) {
                        isConnected = true
                        Log.i(TAG, "SDK mode on attempt ${attempt + 1}")
                        return@withContext ConnectResult(true, "ok (attempt ${attempt + 1})")
                    }
                    lastDetail = "reply='$reply' (attempt ${attempt + 1}/$CONNECT_ATTEMPTS)"
                    Log.w(TAG, lastDetail)
                } catch (e: SocketTimeoutException) {
                    lastDetail = "timeout (attempt ${attempt + 1}/$CONNECT_ATTEMPTS)"
                    Log.w(TAG, lastDetail)
                } catch (e: Exception) {
                    lastDetail = "${e.javaClass.simpleName}: ${e.message} (attempt ${attempt + 1})"
                    Log.e(TAG, lastDetail, e)
                }
                delay(300)
            }
            closeSockets()
            ConnectResult(false, lastDetail)
        }

    fun disconnect() {
        isConnected = false
        closeSockets()
    }

    private fun openSocket(replyTimeout: Boolean): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.soTimeout = if (replyTimeout) REPLY_TIMEOUT_MS else 1
        try {
            socketBinder?.invoke(s)
        } catch (e: Exception) {
            Log.w(TAG, "socketBinder failed", e)
        }
        s.bind(InetSocketAddress(0))
        return s
    }

    suspend fun takeoff(): Boolean = sendAndOk("takeoff")
    suspend fun land(): Boolean = sendAndOk("land")
    suspend fun streamOn(): Boolean = sendAndOk("streamon")
    suspend fun streamOff(): Boolean = sendAndOk("streamoff")

    suspend fun emergency(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (cmdSocket == null) {
                telloAddress = InetAddress.getByName(TELLO_IP)
                cmdSocket = openSocket(replyTimeout = true)
            }
            sendCommandLocked("emergency")
            true
        } catch (e: Exception) {
            Log.e(TAG, "emergency failed", e)
            try {
                val addr = telloAddress ?: InetAddress.getByName(TELLO_IP)
                val data = "emergency".toByteArray(StandardCharsets.UTF_8)
                DatagramSocket().use { s ->
                    s.send(DatagramPacket(data, data.size, addr, COMMAND_PORT))
                }
            } catch (e2: Exception) {
                Log.e(TAG, "emergency fallback failed", e2)
            }
            false
        } finally {
            isConnected = false
        }
    }

    /** Fire-and-forget. a=roll b=pitch c=throttle d=yaw. Soft-capped to MAX_RC (SDK +/-100). */
    fun sendRc(a: Int, b: Int, c: Int, d: Int) {
        val s = rcSocket ?: return
        val addr = telloAddress ?: return
        fun clamp(v: Int) = v.coerceIn(-MAX_RC, MAX_RC)
        val cmd = "rc ${clamp(a)} ${clamp(b)} ${clamp(c)} ${clamp(d)}"
        try {
            val data = cmd.toByteArray(StandardCharsets.UTF_8)
            s.send(DatagramPacket(data, data.size, addr, COMMAND_PORT))
        } catch (e: Exception) {
            Log.w(TAG, "sendRc failed: $cmd", e)
        }
    }

    suspend fun rc(a: Int, b: Int, c: Int, d: Int): Boolean {
        sendRc(a, b, c, d)
        return true
    }

    suspend fun queryBattery(): Int {
        val r = sendRaw("battery?")
        return r?.trim()?.toIntOrNull() ?: -1
    }

    suspend fun sendRaw(cmd: String): String? = withContext(Dispatchers.IO) {
        if (cmdSocket == null) return@withContext null
        try {
            sendCommandLocked(cmd)
        } catch (e: Exception) {
            Log.e(TAG, "sendRaw failed: $cmd", e)
            null
        }
    }

    private suspend fun sendAndOk(cmd: String): Boolean {
        val r = sendRaw(cmd) ?: return false
        return r.equals("ok", ignoreCase = true)
    }

    private suspend fun sendCommandLocked(cmd: String): String = cmdMutex.withLock {
        val s = cmdSocket ?: error("cmd socket closed")
        val addr = telloAddress ?: error("no address")
        drain(s)
        val data = cmd.toByteArray(StandardCharsets.UTF_8)
        s.send(DatagramPacket(data, data.size, addr, COMMAND_PORT))
        val buf = ByteArray(BUF_SIZE)
        val packet = DatagramPacket(buf, buf.size)
        s.receive(packet)
        String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
    }

    private fun drain(s: DatagramSocket) {
        val old = s.soTimeout
        try {
            s.soTimeout = 1
            val buf = ByteArray(BUF_SIZE)
            while (true) {
                try {
                    s.receive(DatagramPacket(buf, buf.size))
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try { s.soTimeout = old } catch (_: Exception) {}
        }
    }

    private fun closeSockets() {
        try { cmdSocket?.close() } catch (_: Exception) {}
        try { rcSocket?.close() } catch (_: Exception) {}
        cmdSocket = null
        rcSocket = null
    }

    fun shutdown() = disconnect()
}
