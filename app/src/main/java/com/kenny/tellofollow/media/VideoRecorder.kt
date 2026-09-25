package com.kenny.tellofollow.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-encodes decoded [Bitmap] frames to H.264 MP4 via MediaCodec + MediaMuxer,
 * optionally muxing phone microphone AAC (Tello stream is silent).
 *
 * When [start] is called with audio and RECORD_AUDIO is granted, an AudioRecord
 * thread feeds an AAC encoder; both tracks share a wall-clock epoch so
 * presentationTimeUs stays aligned. If mic permission is missing or audio
 * setup fails, recording continues video-only.
 *
 * Files land in Movies/TelloFollow.
 */
class VideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val BITRATE = 4_000_000
        private const val FPS = 15
        private const val I_FRAME_INTERVAL = 1
        private const val WIDTH = 960
        private const val HEIGHT = 720
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 64_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_PCM = AudioFormat.ENCODING_PCM_16BIT
    }

    private val recording = AtomicBoolean(false)
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var videoFormatReady = false
    private var audioFormatReady = false
    private var includeAudio = false
    private var muxerStarted = false
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var frameIndex = 0L
    private var startTimeNs = 0L

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)
    private val muxerLock = Any()

    val isRecording: Boolean get() = recording.get()
    val isRecordingWithAudio: Boolean get() = recording.get() && includeAudio

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param withAudio request mic track; silently falls back to video-only if
     * permission missing or AudioRecord/AAC setup fails.
     */
    fun start(withAudio: Boolean = true): Boolean {
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

            val vFormat = MediaFormat.createVideoFormat(VIDEO_MIME, WIDTH, HEIGHT).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            val vc = MediaCodec.createEncoderByType(VIDEO_MIME)
            vc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            vc.start()
            videoCodec = vc

            videoTrackIndex = -1
            audioTrackIndex = -1
            videoFormatReady = false
            audioFormatReady = false
            muxerStarted = false
            frameIndex = 0
            startTimeNs = System.nanoTime()

            includeAudio = false
            if (withAudio && hasMicPermission()) {
                includeAudio = tryStartAudio()
                if (!includeAudio) {
                    Log.w(TAG, "audio setup failed - continuing video-only")
                }
            } else if (withAudio) {
                Log.w(TAG, "RECORD_AUDIO not granted - video-only")
            }

            recording.set(true)
            Log.i(TAG, "recording started audio=$includeAudio -> $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            releaseInternal(delete = true)
            false
        }
    }

    private fun tryStartAudio(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_PCM,
            )
            if (minBuf <= 0) return false
            val bufSize = minBuf * 2

            val aFormat = MediaFormat.createAudioFormat(
                AUDIO_MIME, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
            }
            val ac = MediaCodec.createEncoderByType(AUDIO_MIME)
            ac.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audioCodec = ac

            @Suppress("MissingPermission")
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_CONFIG,
                AUDIO_PCM,
                bufSize,
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                ac.stop()
                ac.release()
                audioCodec = null
                return false
            }
            audioRecord = ar
            audioRunning.set(true)
            ar.startRecording()
            audioThread = Thread({ audioLoop(bufSize) }, "TelloAudioRec").also {
                it.isDaemon = true
                it.start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "tryStartAudio failed", e)
            stopAudioInternal()
            false
        }
    }

    private fun audioLoop(readBufSize: Int) {
        val pcm = ByteArray(readBufSize)
        var totalBytes = 0L
        val ac = audioCodec ?: return
        val ar = audioRecord ?: return
        try {
            while (audioRunning.get()) {
                val n = ar.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val ptsUs = (totalBytes * 1_000_000L) / (AUDIO_SAMPLE_RATE * 2L) // 16-bit mono
                // Prefer wall clock aligned with video when available
                val wallPts = (System.nanoTime() - startTimeNs) / 1000L
                val usePts = if (startTimeNs > 0L) wallPts else ptsUs
                totalBytes += n

                val inIndex = ac.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = ac.getInputBuffer(inIndex) ?: continue
                    buf.clear()
                    buf.put(pcm, 0, n)
                    ac.queueInputBuffer(inIndex, 0, n, usePts, 0)
                }
                drainAudioEncoder(endOfStream = false)
            }
            // EOS
            val inIndex = ac.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val ptsUs = (System.nanoTime() - startTimeNs) / 1000L
                ac.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainAudioEncoder(endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "audioLoop error", e)
        }
    }

    fun offerFrame(bitmap: Bitmap) {
        if (!recording.get()) return
        val c = videoCodec ?: return
        try {
            val nv12 = argbToNv12(bitmap, WIDTH, HEIGHT)
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex) ?: return
                buf.clear()
                buf.put(nv12)
                val ptsUs = (System.nanoTime() - startTimeNs) / 1000L
                frameIndex++
                c.queueInputBuffer(inIndex, 0, nv12.size, ptsUs, 0)
            }
            drainVideoEncoder(endOfStream = false)
        } catch (e: Exception) {
            Log.e(TAG, "offerFrame error", e)
        }
    }

    fun stop(): Uri? {
        if (!recording.getAndSet(false)) return outputUri
        try {
            // Stop audio first so its EOS drains before muxer release
            stopAudioInternal()
            videoCodec?.let { c ->
                val inIndex = c.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val ptsUs = (System.nanoTime() - startTimeNs) / 1000L
                    c.queueInputBuffer(
                        inIndex, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                }
                drainVideoEncoder(endOfStream = true)
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
        Log.i(TAG, "recording stopped -> $uri")
        return uri
    }

    private fun stopAudioInternal() {
        audioRunning.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioThread?.join(1500)
        } catch (_: Exception) {
        }
        audioThread = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            audioCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            audioCodec?.release()
        } catch (_: Exception) {
        }
        audioCodec = null
    }

    private fun maybeStartMuxerLocked() {
        if (muxerStarted) return
        if (!videoFormatReady) return
        if (includeAudio && !audioFormatReady) return
        muxer?.start()
        muxerStarted = true
        Log.i(TAG, "muxer started videoTrack=$videoTrackIndex audioTrack=$audioTrackIndex")
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val c = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (videoFormatReady) throw RuntimeException("video format changed twice")
                        val m = muxer ?: return
                        videoTrackIndex = m.addTrack(c.outputFormat)
                        videoFormatReady = true
                        maybeStartMuxerLocked()
                    }
                }
                outIndex >= 0 -> {
                    val encoded: ByteBuffer = c.getOutputBuffer(outIndex) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size != 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer?.writeSampleData(videoTrackIndex, encoded, info)
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        val c = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (audioFormatReady) throw RuntimeException("audio format changed twice")
                        val m = muxer ?: return
                        audioTrackIndex = m.addTrack(c.outputFormat)
                        audioFormatReady = true
                        maybeStartMuxerLocked()
                    }
                }
                outIndex >= 0 -> {
                    val encoded: ByteBuffer = c.getOutputBuffer(outIndex) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size != 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer?.writeSampleData(audioTrackIndex, encoded, info)
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun releaseInternal(delete: Boolean) {
        stopAudioInternal()
        try {
            videoCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            videoCodec?.release()
        } catch (_: Exception) {
        }
        videoCodec = null
        synchronized(muxerLock) {
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
            videoFormatReady = false
            audioFormatReady = false
            includeAudio = false
        }
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

    /** Rough ARGB_8888 to NV12 (YUV420 semi-planar) scaled to [dstW]x[dstH]. */
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
                val color = argb[index++]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
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
