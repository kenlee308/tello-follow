package com.kenny.tellofollow.viewmodel

import android.app.Application
import android.util.Log
import android.graphics.Bitmap
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kenny.tellofollow.media.PhotoCapture
import com.kenny.tellofollow.media.VideoRecorder
import com.kenny.tellofollow.safety.FlightSafetyMonitor
import com.kenny.tellofollow.safety.SafetyAction
import com.kenny.tellofollow.safety.SafetyEvent
import com.kenny.tellofollow.tello.H264Decoder
import com.kenny.tellofollow.tello.TelloCommandClient
import com.kenny.tellofollow.tello.ConnectResult
import com.kenny.tellofollow.tello.TelloWifiBinder
import com.kenny.tellofollow.tello.TelloStateListener
import com.kenny.tellofollow.tello.TelloTelemetry
import com.kenny.tellofollow.tello.TelloVideoReceiver
import com.kenny.tellofollow.tracking.DetectionResult
import com.kenny.tellofollow.tracking.FollowMode
import com.kenny.tellofollow.tracking.FollowStyle
import com.kenny.tellofollow.tracking.PersonDetector
import com.kenny.tellofollow.tracking.PidController
import com.kenny.tellofollow.tracking.RcCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FlightUiState(
    val connected: Boolean = false,
    val flying: Boolean = false,
    val streaming: Boolean = false,
    val followEnabled: Boolean = false,
    /** Default Body; Face keeps v1 face-only tracking. */
    val followMode: FollowMode = FollowMode.BODY,
    /** Flight personality (v2.1); orthogonal to body/face detection. */
    val followStyle: FollowStyle = FollowStyle.CHASE,
    val recording: Boolean = false,
    val recordingWithAudio: Boolean = false,
    val statusMessage: String = "Join TELLO-xxxx Wi-Fi, then Connect",
    val lastPhotoOk: Boolean = false,
)

/**
 * Wires connect â†’ stream â†’ decode â†’ detect â†’ PID â†’ rc, plus media and safety.
 */
class FlightViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "FlightVM"
        private const val FOLLOW_HZ = 15L
        /** Stick / hover RC refresh. Tello expects continuous rc while flying. */
        private const val RC_PERIOD_MS = 50L
    }

    private val command = TelloCommandClient()
    private val wifiBinder = TelloWifiBinder(app)
    private val stateListener = TelloStateListener()
    private val detector = PersonDetector()
    private val pid = PidController()
    private val safety = FlightSafetyMonitor()
    private val photoCapture = PhotoCapture(app)
    private val videoRecorder = VideoRecorder(app)

    private val decoder = H264Decoder(onBitmap = { bmp -> onDecodedBitmap(bmp) })
    private val videoReceiver = TelloVideoReceiver(onFrame = { frame -> decoder.decode(frame) })

    private val _ui = MutableStateFlow(FlightUiState())
    val ui: StateFlow<FlightUiState> = _ui.asStateFlow()

    val telemetry: StateFlow<TelloTelemetry> = stateListener.telemetry
    val safetyEvents: StateFlow<SafetyEvent> = safety.events

    private val _detection = MutableStateFlow<DetectionResult?>(null)
    val detection: StateFlow<DetectionResult?> = _detection.asStateFlow()

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    /** Latest PixelCopy snapshot (debug / optional UI). FPV display uses SurfaceView. */
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val _manualRc = MutableStateFlow(RcCommand.HOVER)
    private var followJob: Job? = null
    private var safetyJob: Job? = null
    private var rcJob: Job? = null
    @Volatile private var latestBmp: Bitmap? = null

    init {
        safetyJob = viewModelScope.launch {
            telemetry.collect { t ->
                // Keep flying in sync with the drone — takeoff "ok" can miss / lag,
                // which previously left Land + Follow greyed out while airborne.
                val airborne = t.heightCm >= 20 || t.tof >= 30
                val grounded = t.heightCm <= 5 && t.tof <= 15
                val cur = _ui.value
                if (airborne && !cur.flying) {
                    _ui.value = cur.copy(flying = true)
                } else if (grounded && cur.flying && !cur.followEnabled) {
                    // Only clear when clearly on the ground and not mid-follow.
                    // Land()/Emergency still clear explicitly.
                }
                val action = safety.onTelemetry(t, _ui.value.flying)
                handleSafetyAction(action)
            }
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        decoder.setPreviewSurface(surface)
        if (surface != null && surface.isValid && _ui.value.streaming) {
            decoder.start()
        }
    }

    fun connect() {
        viewModelScope.launch {
            try {
                _ui.value = _ui.value.copy(statusMessage = "Binding to Wi-Fi…")
                val bindErr = wifiBinder.bindToWifi()
                if (bindErr != null) {
                    _ui.value = _ui.value.copy(connected = false, statusMessage = bindErr)
                    return@launch
                }
                val ssid = wifiBinder.currentSsid()
                val ip = wifiBinder.wifiIpv4()
                _ui.value = _ui.value.copy(statusMessage = "Phone IP $ip — sending command…")
                if (!wifiBinder.looksLikeTelloSubnet()) {
                    _ui.value = _ui.value.copy(
                        connected = false,
                        statusMessage = "Wrong network: IP $ip (want 192.168.10.x). Rejoin TELLO-xxxx; Mobile Data/VPN off.",
                    )
                    wifiBinder.unbind()
                    return@launch
                }

                // Telemetry listener is optional — don't block connect if port busy
                try {
                    stateListener.start()
                } catch (e: Exception) {
                    Log.w("FlightVM", "state listener failed", e)
                }

                val result = command.connect(socketBinder = wifiBinder::bindSocket)
                if (!result.ok) {
                    wifiBinder.unbind()
                    _ui.value = _ui.value.copy(
                        connected = false,
                        statusMessage = "Connect failed: ${result.detail}",
                    )
                    return@launch
                }

                // Mark connected BEFORE video — video must never crash the session
                _ui.value = _ui.value.copy(
                    connected = true,
                    statusMessage = "Connected ($ip). Starting stream…",
                )

                startRcLoop()

                var streamOk = false
                try {
                    streamOk = command.streamOn()
                    if (streamOk) {
                        decoder.start() // no-ops until preview Surface is set
                        videoReceiver.start()
                    }
                } catch (e: Exception) {
                    Log.e("FlightVM", "stream start failed", e)
                    streamOk = false
                }

                _ui.value = _ui.value.copy(
                    connected = true,
                    streaming = streamOk,
                    statusMessage = if (streamOk) {
                        "Connected + stream ($ssid / $ip)"
                    } else {
                        "Connected — controls OK, video deferred ($ip)"
                    },
                )
            } catch (e: Exception) {
                Log.e("FlightVM", "connect crashed", e)
                _ui.value = _ui.value.copy(
                    connected = false,
                    statusMessage = "Connect error: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            setFollow(false)
            stopRcLoop(hover = true)
            if (_ui.value.flying) {
                command.land()
            }
            videoReceiver.stop()
            decoder.stop()
            command.streamOff()
            command.disconnect()
            stateListener.stop()
            wifiBinder.unbind()
            _ui.value = FlightUiState(statusMessage = "Disconnected")
        }
    }

    fun takeoff() {
        viewModelScope.launch {
            if (!command.isConnected) return@launch
            // Hover sticks before climb so leftover throttle cannot fight takeoff.
            _manualRc.value = RcCommand.HOVER
            startRcLoop()
            // Optimistic: enable Land/Follow immediately; telemetry confirms height.
            _ui.value = _ui.value.copy(flying = true, statusMessage = "Taking off...")
            val ok = command.takeoff()
            if (ok) {
                _ui.value = _ui.value.copy(
                    flying = true,
                    statusMessage = "Airborne - sticks soft-capped (+/-50)",
                )
            } else {
                // Keep flying=true briefly — Tello sometimes climbs after a late/missing ok.
                // Telemetry will confirm; if still grounded after a moment, clear.
                _ui.value = _ui.value.copy(statusMessage = "Takeoff reply missing - check height; Land is available")
                launch {
                    delay(2500)
                    val t = telemetry.value
                    if (t.heightCm <= 5 && t.tof <= 15) {
                        _ui.value = _ui.value.copy(flying = false, statusMessage = "Takeoff failed")
                    }
                }
            }
        }
    }

    fun land() {
        viewModelScope.launch {
            setFollow(false)
            _manualRc.value = RcCommand.HOVER
            val ok = command.land()
            _ui.value = _ui.value.copy(
                flying = if (ok) false else _ui.value.flying,
                statusMessage = if (ok) "Landed" else "Land command failed",
            )
        }
    }

    fun emergency() {
        viewModelScope.launch {
            setFollow(false)
            _manualRc.value = RcCommand.HOVER
            command.sendRc(0, 0, 0, 0)
            command.emergency()
            _ui.value = _ui.value.copy(flying = false, statusMessage = "EMERGENCY — motors cut (will fall). Prefer Land next time.")
        }
    }

    fun setFollow(enabled: Boolean) {
        pid.followEnabled = enabled
        if (!enabled) {
            followJob?.cancel()
            followJob = null
            pid.reset()
            command.sendRc(0, 0, 0, 0)
        } else if (followJob == null) {
            followJob = viewModelScope.launch(Dispatchers.Default) { followLoop() }
        }
        _ui.value = _ui.value.copy(followEnabled = enabled)
    }

    fun toggleFollow() = setFollow(!_ui.value.followEnabled)

    fun setFollowMode(mode: FollowMode) {
        _ui.value = _ui.value.copy(followMode = mode)
    }

    fun cycleFollowMode() {
        val next = when (_ui.value.followMode) {
            FollowMode.BODY -> FollowMode.FACE
            FollowMode.FACE -> FollowMode.BODY
        }
        setFollowMode(next)
    }

    fun setFollowStyle(style: FollowStyle) {
        // Re-tapping Orbit flips strafe direction
        if (style == FollowStyle.ORBIT && _ui.value.followStyle == FollowStyle.ORBIT) {
            pid.flipOrbitDirection()
        }
        _ui.value = _ui.value.copy(followStyle = style)
    }

    fun cycleFollowStyle() {
        val order = FollowStyle.entries
        val idx = order.indexOf(_ui.value.followStyle)
        setFollowStyle(order[(idx + 1) % order.size])
    }

    /**
     * Dual-joystick update. Pass null to leave an axis unchanged so left/right
     * pads do not zero each other out.
     */
    fun onManualRc(
        roll: Int? = null,
        pitch: Int? = null,
        throttle: Int? = null,
        yaw: Int? = null,
    ) {
        // Only update desired sticks. The 20 Hz loop sends them - never await UDP here.
        val prev = _manualRc.value
        _manualRc.value = RcCommand(
            roll = roll ?: prev.roll,
            pitch = pitch ?: prev.pitch,
            throttle = throttle ?: prev.throttle,
            yaw = yaw ?: prev.yaw,
        )
    }

    fun takePhoto() {
        viewModelScope.launch {
            val bmp = latestBmp ?: decoder.getLatestBitmap()
            if (bmp == null) {
                _ui.value = _ui.value.copy(statusMessage = "No frame for photo")
                return@launch
            }
            val uri = photoCapture.saveJpeg(bmp)
            _ui.value = _ui.value.copy(
                lastPhotoOk = uri != null,
                statusMessage = if (uri != null) "Photo saved" else "Photo failed",
            )
        }
    }

    fun toggleRecord(withAudio: Boolean = true) {
        viewModelScope.launch {
            if (videoRecorder.isRecording) {
                videoRecorder.stop()
                _ui.value = _ui.value.copy(
                    recording = false,
                    recordingWithAudio = false,
                    statusMessage = "Recording saved",
                )
            } else {
                val ok = withContext(Dispatchers.IO) { videoRecorder.start(withAudio = withAudio) }
                val hasAudio = ok && videoRecorder.isRecordingWithAudio
                val msg = when {
                    !ok -> "Record failed"
                    hasAudio -> "Recording (mic on)..."
                    withAudio -> "Recording (video only - mic denied/unavailable)"
                    else -> "Recording (video only)..."
                }
                _ui.value = _ui.value.copy(
                    recording = ok,
                    recordingWithAudio = hasAudio,
                    statusMessage = msg,
                )
            }
        }
    }

    fun onHostStopped() {
        if (_ui.value.followEnabled) {
            setFollow(false)
        }
        _manualRc.value = RcCommand.HOVER
        command.sendRc(0, 0, 0, 0)
    }

    fun shutdown() {
        followJob?.cancel()
        safetyJob?.cancel()
        stopRcLoop(hover = true)
        viewModelScope.launch {
            if (_ui.value.flying) command.land()
            if (videoRecorder.isRecording) videoRecorder.stop()
            videoReceiver.shutdown()
            decoder.stop()
            command.shutdown()
            stateListener.shutdown()
            detector.close()
        }
    }


    private fun startRcLoop() {
        if (rcJob?.isActive == true) return
        rcJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && command.isConnected) {
                if (!_ui.value.followEnabled) {
                    val m = _manualRc.value
                    command.sendRc(m.roll, m.pitch, m.throttle, m.yaw)
                }
                delay(RC_PERIOD_MS)
            }
        }
    }

    private fun stopRcLoop(hover: Boolean) {
        rcJob?.cancel()
        rcJob = null
        _manualRc.value = RcCommand.HOVER
        if (hover) {
            try {
                command.sendRc(0, 0, 0, 0)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Entry from [com.kenny.tellofollow.ui.SurfaceFrameGrabber] PixelCopy.
     * Safe to call from the grabber HandlerThread.
     */
    fun onGrabbedFrame(bmp: Bitmap) {
        onDecodedBitmap(bmp)
    }

    private fun onDecodedBitmap(bmp: Bitmap) {
        latestBmp = bmp
        _previewBitmap.value = bmp
        // Do not recycle here: takePhoto / followLoop / StateFlow may still hold the prior frame.
        if (videoRecorder.isRecording) {
            try {
                videoRecorder.offerFrame(bmp)
            } catch (e: Exception) {
                Log.w(TAG, "offerFrame failed", e)
            }
        }
    }

    private suspend fun followLoop() {
        while (viewModelScope.isActive && pid.followEnabled) {
            val bmp = latestBmp
            var det: DetectionResult? = null
            if (bmp != null) {
                det = withContext(Dispatchers.Default) {
                    detector.detect(bmp, _ui.value.followMode)
                }
            }
            _detection.value = det
            val safetyAction = safety.onFollowFrame(det != null, pid.followEnabled)
            when (safetyAction) {
                SafetyAction.LAND -> {
                    setFollow(false)
                    command.land()
                    _ui.value = _ui.value.copy(flying = false)
                    break
                }
                SafetyAction.HOVER, SafetyAction.STOP_FOLLOW -> {
                    command.rc(0, 0, 0, 0)
                    delay(1000L / FOLLOW_HZ)
                    continue
                }
                SafetyAction.NONE -> Unit
            }
            val auto = pid.update(det, _ui.value.followStyle)
            // Manual sticks override auto when deflected; ORBIT auto-roll used when stick near zero
            val man = _manualRc.value
            val cmd = RcCommand(
                roll = if (kotlin.math.abs(man.roll) > 15) man.roll else auto.roll,
                pitch = if (kotlin.math.abs(man.pitch) > 15) man.pitch else auto.pitch,
                throttle = if (kotlin.math.abs(man.throttle) > 15) man.throttle else auto.throttle,
                yaw = if (kotlin.math.abs(man.yaw) > 15) man.yaw else auto.yaw,
            )
            command.rc(cmd.roll, cmd.pitch, cmd.throttle, cmd.yaw)
            delay(1000L / FOLLOW_HZ)
        }
    }

    private suspend fun handleSafetyAction(action: SafetyAction) {
        when (action) {
            SafetyAction.LAND -> {
                setFollow(false)
                command.land()
                _ui.value = _ui.value.copy(flying = false, statusMessage = "Auto-land (safety)")
            }
            SafetyAction.STOP_FOLLOW -> setFollow(false)
            SafetyAction.HOVER -> command.rc(0, 0, 0, 0)
            SafetyAction.NONE -> Unit
        }
    }

    override fun onCleared() {
        super.onCleared()
        shutdown()
    }
}
