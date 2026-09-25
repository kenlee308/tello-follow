package com.kenny.tellofollow.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MVP video recorder: re-encodes decoded [Bitmap] frames to H.264 MP4 via MediaCodec + MediaMuxer.
 *
 * Tradeoff vs muxing raw Tello NALs:
 * - **Re-encode (this path):** reliable timestamps, works even if Tello bitstream lacks
 *   clean access-unit boundaries for MediaMuxer; more CPU / slight quality loss.
 * - **Remux NALs:** lower CPU, needs careful SPS/PPS + Annex-B→AVCC conversion; brittle
 *   across Tello firmware. Deferred for a later iteration.
 *
 * Files land in Movies/TelloFollow.
 */
class VideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val BITRATE = 4_000_000
        private const val FPS = 15
        private const val I_FRAME_INTERVAL = 1
        private const val WIDTH = 960
        private const val HEIGHT = 720
    }

    private val recording = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var frameIndex = 0L

    val isRecording: Boolean get() = recording.get()

    fun start(): Boolean {
        if (recording.get()) return true
        return try {
            val name = "Tello_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TelloFollow")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            outputUri = uri
            pfd = resolver.openFileDescriptor(uri, "rw") ?: return false

            muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            trackIndex = -1
            muxerStarted = false
            frameIndex = 0
            recording.set(true)
            Log.i(TAG, "recording started → $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            releaseInternal(delete = true)
            false
        }
    }

    /**
     * Encode one ARGB bitmap. Call from a background thread at ~FPS.
     * Scales/crops naively if size mismatches.
     */
    fun offerFrame(bitmap: Bitmap) {
        if (!recording.get()) return
        val c = codec ?: return
        try {
            val nv12 = argbToNv12(bitmap, WIDTH, HEIGHT)
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex) ?: return
                buf.clear()
                buf.put(nv12)
                val ptsUs = frameIndex * 1_000_000L / FPS
                frameIndex++
                c.queueInputBuffer(inIndex, 0, nv12.size, ptsUs, 0)
            }
            drainEncoder(endOfStream = false)
        } catch (e: Exception) {
            Log.e(TAG, "offerFrame error", e)
        }
    }

    fun stop(): Uri? {
        if (!recording.getAndSet(false)) return outputUri
        try {
            codec?.let { c ->
                val inIndex = c.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    c.queueInputBuffer(
                        inIndex, 0, 0, frameIndex * 1_000_000L / FPS,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                drainEncoder(endOfStream = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop drain error", e)
        }
        val uri = outputUri
        releaseInternal(delete = false)
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        }
        Log.i(TAG, "recording stopped → $uri")
        return uri
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw RuntimeException("format changed twice")
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encoded: ByteBuffer = c.getOutputBuffer(outIndex)
                        ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size != 0 && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        m.writeSampleData(trackIndex, encoded, info)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun releaseInternal(delete: Boolean) {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        if (delete) {
            outputUri?.let { context.contentResolver.delete(it, null, null) }
            outputUri = null
        }
    }

    /** Rough ARGB_8888 → NV12 (YUV420 semi-planar) scaled to [dstW]x[dstH]. */
    private fun argbToNv12(src: Bitmap, dstW: Int, dstH: Int): ByteArray {
        val scaled = if (src.width != dstW || src.height != dstH) {
            Bitmap.createScaledBitmap(src, dstW, dstH, true)
        } else src
        val argb = IntArray(dstW * dstH)
        scaled.getPixels(argb, 0, dstW, 0, 0, dstW, dstH)
        if (scaled !== src) scaled.recycle()
        val ySize = dstW * dstH
        val out = ByteArray(ySize + ySize / 2)
        var yIndex = 0
        var uvIndex = ySize
        var index = 0
        for (j in 0 until dstH) {
            for (i in 0 until dstW) {
                val c = argb[index++]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }
}
