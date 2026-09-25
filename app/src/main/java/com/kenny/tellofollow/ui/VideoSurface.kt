package com.kenny.tellofollow.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicReference

/**
 * Full-bleed H.264 preview via [SurfaceView]. MediaCodec renders here only.
 *
 * While [grabEnabled], [SurfaceFrameGrabber] samples the live Surface with
 * PixelCopy (~12 Hz) and delivers ARGB snapshots for photo / record / follow.
 * Grab failures are logged and skipped — they must never tear down decode.
 */
@Composable
fun VideoSurface(
    modifier: Modifier = Modifier,
    grabEnabled: Boolean = false,
    onSurfaceReady: (Surface?) -> Unit,
    onFrameBitmap: (Bitmap) -> Unit = {},
) {
    val onSurfaceState = rememberUpdatedState(onSurfaceReady)
    val onFrameState = rememberUpdatedState(onFrameBitmap)
    val grabberRef = remember { AtomicReference<SurfaceFrameGrabber?>(null) }

    DisposableEffect(grabEnabled) {
        grabberRef.get()?.setEnabled(grabEnabled)
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose {
            grabberRef.getAndSet(null)?.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                val surfaceView = SurfaceView(context)
                val grabber = SurfaceFrameGrabber(surfaceView) { bmp ->
                    onFrameState.value(bmp)
                }
                grabberRef.set(grabber)
                grabber.setEnabled(grabEnabled)

                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceState.value(holder.surface)
                        grabber.onSurfaceAlive()
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        onSurfaceState.value(holder.surface)
                        grabber.onSurfaceAlive()
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        grabber.onSurfaceGone()
                        onSurfaceState.value(null)
                    }
                })
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = {
            grabberRef.get()?.setEnabled(grabEnabled)
        },
    )
}

/**
 * Throttled PixelCopy from a [SurfaceView] into 960x720 ARGB bitmaps.
 * Reuses one dest buffer for the copy request; delivers an immutable snapshot.
 */
class SurfaceFrameGrabber(
    private val surfaceView: SurfaceView,
    private val onFrame: (Bitmap) -> Unit,
) {
    companion object {
        private const val TAG = "SurfaceFrameGrabber"
        private const val WIDTH = 960
        private const val HEIGHT = 720
        /** ~12.5 Hz — enough for photo / record / face detect without UI jank. */
        private const val PERIOD_MS = 80L
    }

    private val thread = HandlerThread("PixelCopyGrab").also { it.start() }
    private val handler = Handler(thread.looper)
    private val dest: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)

    /** User/UI wants grabs (streaming). Survives transient surface destroy/recreate. */
    @Volatile
    private var wantEnabled = false

    /** Loop currently armed. */
    @Volatile
    private var enabled = false

    @Volatile
    private var inFlight = false

    private val tick = object : Runnable {
        override fun run() {
            if (!enabled) return
            handler.postDelayed(this, PERIOD_MS)
            requestCopy()
        }
    }

    fun setEnabled(value: Boolean) {
        wantEnabled = value
        applyLoop()
    }

    /** Call after surfaceCreated / surfaceChanged so grabs resume if still streaming. */
    fun onSurfaceAlive() {
        applyLoop()
    }

    /** Pause loop without clearing [wantEnabled] (surfaceDestroyed). */
    fun onSurfaceGone() {
        handler.post {
            enabled = false
            handler.removeCallbacks(tick)
        }
    }

    private fun applyLoop() {
        handler.post {
            handler.removeCallbacks(tick)
            enabled = wantEnabled
            if (enabled) {
                handler.post(tick)
            }
        }
    }

    fun release() {
        wantEnabled = false
        enabled = false
        handler.post {
            handler.removeCallbacks(tick)
        }
        thread.quitSafely()
    }

    private fun requestCopy() {
        if (inFlight) return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) return
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return

        inFlight = true
        try {
            PixelCopy.request(surfaceView, dest, { result ->
                try {
                    if (result == PixelCopy.SUCCESS && enabled) {
                        // Snapshot so ML Kit / recorder never share the reusable dest.
                        val snapshot = dest.copy(Bitmap.Config.ARGB_8888, false)
                        try {
                            onFrame(snapshot)
                        } catch (e: Exception) {
                            Log.w(TAG, "onFrame failed", e)
                            if (!snapshot.isRecycled) snapshot.recycle()
                        }
                    } else if (result != PixelCopy.SUCCESS) {
                        Log.d(TAG, "PixelCopy result=$result (ignored)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PixelCopy callback error", e)
                } finally {
                    inFlight = false
                }
            }, handler)
        } catch (e: Exception) {
            // IllegalArgumentException / IllegalStateException if surface went away
            Log.w(TAG, "PixelCopy.request failed", e)
            inFlight = false
        }
    }
}
