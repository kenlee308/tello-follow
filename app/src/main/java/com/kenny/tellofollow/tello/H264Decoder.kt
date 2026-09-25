package com.kenny.tellofollow.tello

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodes Tello Annex-B H.264 into a preview [Surface] only.
 *
 * ImageReader / YUV → Bitmap was removed after a hard JNI abort on some
 * Samsung devices (`NewDirectByteBuffer` on null plane). Display stays
 * Surface-driven; bitmaps for photo / record / follow come from PixelCopy
 * on the live SurfaceView (see [com.kenny.tellofollow.ui.SurfaceFrameGrabber]).
 */
class H264Decoder(
    private val onBitmap: ((Bitmap) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val WIDTH = 960
        private const val HEIGHT = 720
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var decoderThread: HandlerThread? = null
    private var handler: Handler? = null
    private val latestBitmap = AtomicReference<Bitmap?>(null)

    @Volatile
    private var previewSurface: Surface? = null

    @Volatile
    private var started = false

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        Log.d(TAG, "preview surface set=${surface != null}")
        // If already running, rebuild codec against the new surface
        if (started) {
            stop()
            if (surface != null && surface.isValid) {
                start()
            }
        }
    }

    /** Optional cache; primary frame source is PixelCopy → ViewModel. */
    fun getLatestBitmap(): Bitmap? = latestBitmap.get()

    fun start() {
        if (started) return
        val surface = previewSurface
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "start skipped - no valid preview Surface yet")
            return
        }
        try {
            decoderThread = HandlerThread("H264Decoder").also { it.start() }
            handler = Handler(decoderThread!!.looper)

            val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            }
            val c = MediaCodec.createDecoderByType(MIME)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            started = true
            Log.i(TAG, "decoder started (Surface preview only)")
            // onBitmap unused here — PixelCopy supplies frames to ViewModel
            if (onBitmap != null) {
                Log.d(TAG, "onBitmap present but unused (PixelCopy path)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stop()
        }
    }

    fun decode(frame: ByteArray) {
        val c = codec ?: return
        if (!started) return
        try {
            val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val input = c.getInputBuffer(inIndex) ?: return
                input.clear()
                if (frame.size > input.capacity()) {
                    Log.w(TAG, "frame ${frame.size} > buffer ${input.capacity()}, drop")
                    c.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    return
                }
                input.put(frame)
                c.queueInputBuffer(inIndex, 0, frame.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
            while (outIndex >= 0) {
                // true = render to configured Surface
                c.releaseOutputBuffer(outIndex, true)
                outIndex = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode error", e)
        }
    }

    fun stop() {
        started = false
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        decoderThread?.quitSafely()
        decoderThread = null
        handler = null
    }
}
