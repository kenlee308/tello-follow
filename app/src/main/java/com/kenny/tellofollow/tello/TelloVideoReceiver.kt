package com.kenny.tellofollow.tello

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Tello H.264 video stream on UDP 11111.
 *
 * Frame assembly pattern (used by TelloNO and discussed widely on StackOverflow):
 * - Tello sends 1460-byte UDP packets (MTU-ish chunks of an Annex-B H.264 bitstream)
 * - A packet with **length < 1460** marks the end of one encoded frame
 * - Concatenate packets until a short packet arrives, then hand the complete NAL
 *   sequence to [H264Decoder]
 *
 * Call [start] after `streamon`. Stop with [stop] / `streamoff`.
 */
class TelloVideoReceiver(
    private val onFrame: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "TelloVideo"
        const val VIDEO_PORT = 11111
        /** Tello chunk size; last packet of a frame is shorter. */
        const val PACKET_SIZE = 1460
        private const val RECV_BUF = 2048
        private const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(VIDEO_PORT))
                    soTimeout = 0
                    receiveBufferSize = 512 * 1024
                }
                socket = s
                isRunning = true
                Log.i(TAG, "receiving video on $VIDEO_PORT")

                val packetBuf = ByteArray(RECV_BUF)
                val frameBuf = ByteArrayOutputStream(256 * 1024)

                while (isActive) {
                    val packet = DatagramPacket(packetBuf, packetBuf.size)
                    s.receive(packet)
                    val len = packet.length
                    if (len <= 0) continue

                    // Append chunk
                    if (frameBuf.size() + len > MAX_FRAME_BYTES) {
                        Log.w(TAG, "frame overflow, reset")
                        frameBuf.reset()
                    }
                    frameBuf.write(packetBuf, 0, len)

                    // Short packet = end of frame (TelloNO / SO pattern)
                    if (len < PACKET_SIZE) {
                        val frame = frameBuf.toByteArray()
                        frameBuf.reset()
                        if (frame.isNotEmpty()) {
                            try {
                                onFrame(frame)
                            } catch (e: Exception) {
                                Log.e(TAG, "onFrame callback error", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "video receiver error", e)
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        isRunning = false
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
}
