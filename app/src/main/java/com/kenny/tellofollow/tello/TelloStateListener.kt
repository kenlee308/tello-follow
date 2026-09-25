package com.kenny.tellofollow.tello

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.charset.StandardCharsets

/**
 * Telemetry from Tello on UDP port 8890.
 * Example payload:
 * `pitch:0;roll:0;yaw:0;vgx:0;vgy:0;vgz:0;templ:50;temph:53;tof:10;h:0;bat:90;baro:0.00;time:0;agx:0.00;agy:0.00;agz:-1000.00;`
 */
data class TelloTelemetry(
    val pitch: Int = 0,
    val roll: Int = 0,
    val yaw: Int = 0,
    val vgx: Int = 0,
    val vgy: Int = 0,
    val vgz: Int = 0,
    val templ: Int = 0,
    val temph: Int = 0,
    val tof: Int = 0,
    val heightCm: Int = 0,
    val battery: Int = -1,
    val baro: Float = 0f,
    val time: Int = 0,
    val agx: Float = 0f,
    val agy: Float = 0f,
    val agz: Float = 0f,
    val wifiSnr: Int = -1,
    val raw: String = "",
    val timestampMs: Long = 0L,
)

class TelloStateListener {

    companion object {
        private const val TAG = "TelloState"
        const val STATE_PORT = 8890
        private const val BUF_SIZE = 2048
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _telemetry = MutableStateFlow(TelloTelemetry())
    val telemetry: StateFlow<TelloTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var socket: DatagramSocket? = null
    private var listenJob: Job? = null

    fun start() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            try {
                val s = DatagramSocket(STATE_PORT).apply {
                    reuseAddress = true
                    soTimeout = 0
                }
                socket = s
                Log.i(TAG, "listening on $STATE_PORT")
                val buf = ByteArray(BUF_SIZE)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    _telemetry.value = parse(text)
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "state listener error", e)
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    internal fun parse(raw: String): TelloTelemetry {
        val map = LinkedHashMap<String, String>()
        raw.split(';').forEach { part ->
            val idx = part.indexOf(':')
            if (idx > 0) {
                map[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
            }
        }
        fun intOf(k: String) = map[k]?.toIntOrNull() ?: 0
        fun floatOf(k: String) = map[k]?.toFloatOrNull() ?: 0f
        return TelloTelemetry(
            pitch = intOf("pitch"),
            roll = intOf("roll"),
            yaw = intOf("yaw"),
            vgx = intOf("vgx"),
            vgy = intOf("vgy"),
            vgz = intOf("vgz"),
            templ = intOf("templ"),
            temph = intOf("temph"),
            tof = intOf("tof"),
            heightCm = intOf("h"),
            battery = map["bat"]?.toIntOrNull() ?: -1,
            baro = floatOf("baro"),
            time = intOf("time"),
            agx = floatOf("agx"),
            agy = floatOf("agy"),
            agz = floatOf("agz"),
            wifiSnr = map["wifi"]?.toIntOrNull() ?: -1,
            raw = raw,
            timestampMs = System.currentTimeMillis(),
        )
    }
}
