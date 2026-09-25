package com.kenny.tellofollow.tracking

/**
 * Three independent PIDs mapping subject bbox error to Tello rc axes,
 * shaped by [FollowStyle] (flight personality — distinct from [FollowMode] body/face).
 *
 * Mapping (standard Tello rc a b c d):
 * - **yaw (d)** <- horizontal center error
 * - **throttle (c)** <- vertical center error (inverted: below center => climb)
 * - **pitch (b)** <- size error vs locked/default target (too small => fly forward);
 *   forced 0 in SPOTLIGHT
 * - **roll (a)** 0 in CHASE/SPOTLIGHT/SPORT; sticky strafe in ORBIT
 *
 * Distance: [lockedTargetSize] when set (snapshot at Follow ON), else face/body defaults.
 * Style outputs are soft-clamped here; [TelloCommandClient.MAX_RC] (=100, SDK max) caps on send.
 * Manual sticks store full +/-100; soft (+/-50) vs Fast (+/-100) cap applied at send time.
 */
data class RcCommand(
    val roll: Int = 0,
    val pitch: Int = 0,
    val throttle: Int = 0,
    val yaw: Int = 0,
) {
    companion object {
        val HOVER = RcCommand()
    }
}

/**
 * Flight-style follow modes (v2.1). Orthogonal to [FollowMode] (BODY vs FACE detection).
 */
enum class FollowStyle {
    /** Soft cinematic: gentle gains, roomy deadzone, modest RC caps. */
    CHASE,
    /** Tripod tracking: hover translation; yaw + throttle only. */
    SPOTLIGHT,
    /** Circle: sticky strafe roll while yaw keeps subject centered. */
    ORBIT,
    /** Original 1x Sport: snappy yaw/throttle + aggressive pitch (maxRc/maxPitch 70). */
    SPORT,
}

class PidController(
    private val faceTargetSize: Float = 0.08f,
    private val bodyTargetSize: Float = 0.20f,
    private val baseDeadzone: Float = 0.04f,
) {
    private val yawPid = AxisPid(kp = 120f, ki = 0.0f, kd = 40f)
    private val throttlePid = AxisPid(kp = 140f, ki = 0.0f, kd = 35f)
    private val pitchPid = AxisPid(kp = 90f, ki = 0.0f, kd = 25f)

    @Volatile
    var followEnabled: Boolean = false

    /**
     * Sticky strafe sign for ORBIT: +1 = right, -1 = left.
     * Survives brief detection loss and PID reset.
     */
    @Volatile
    var strafeRollSign: Int = 1
        private set

    /** Snapshot of subject size at Follow ON (distance lock). Null = use mode defaults. */
    @Volatile
    private var lockedTargetSize: Float? = null

    private var lastTs = 0L

    fun reset() {
        yawPid.reset()
        throttlePid.reset()
        pitchPid.reset()
        lastTs = 0L
        // Keep strafeRollSign and lockedTargetSize across brief loss / reset.
    }

    /** Flip ORBIT strafe direction (call from UI when re-tapping Orbit). */
    fun flipOrbitDirection() {
        strafeRollSign = if (strafeRollSign >= 0) -1 else 1
    }

    fun flipStrafeDirection() = flipOrbitDirection()

    fun setOrbitDirection(sign: Int) {
        strafeRollSign = if (sign >= 0) 1 else -1
    }

    /**
     * Lock follow distance from the current detection size proxy
     * (BODY: shoulder-width / frame width; FACE: face box area).
     */
    fun lockDistanceFrom(detection: DetectionResult) {
        val metric = detection.distanceMetric
        if (metric > 0.01f) {
            lockedTargetSize = metric.coerceIn(0.02f, 0.85f)
        }
    }

    fun clearDistanceLock() {
        lockedTargetSize = null
    }

    fun hasDistanceLock(): Boolean = lockedTargetSize != null

    /**
     * @param detection latest body/face, or null if lost
     * @param style flight personality (default CHASE)
     * @return RC command; [RcCommand.HOVER] if follow disabled or no detection
     */
    fun update(
        detection: DetectionResult?,
        style: FollowStyle = FollowStyle.CHASE,
    ): RcCommand {
        if (!followEnabled || detection == null) {
            reset()
            return RcCommand.HOVER
        }
        val now = detection.timestampMs
        val dt = if (lastTs == 0L) 0.05f else ((now - lastTs).coerceIn(5L, 200L) / 1000f)
        lastTs = now

        val defaultSize = when (detection.source) {
            DetectionSource.BODY -> bodyTargetSize
            DetectionSource.FACE -> faceTargetSize
        }
        val targetSize = lockedTargetSize ?: defaultSize

        val cfg = styleConfig(style)
        val errorX = applyDeadzone(detection.centerX - 0.5f, cfg.deadzone)
        val errorY = applyDeadzone(detection.centerY - 0.5f, cfg.deadzone)
        val sizeProxy = detection.distanceMetric
        val errorSize = applyDeadzone(targetSize - sizeProxy, cfg.deadzone)

        var yaw = (yawPid.step(errorX, dt) * cfg.gainScale).toInt()
        var throttle = (-throttlePid.step(errorY, dt) * cfg.gainScale).toInt()
        var pitch = (pitchPid.step(errorSize, dt) * cfg.pitchGainScale).toInt()
        var roll = 0

        when (style) {
            FollowStyle.SPOTLIGHT -> {
                pitch = 0
                roll = 0
            }
            FollowStyle.ORBIT -> {
                roll = strafeRollSign * ORBIT_ROLL_MAG
                // Light pitch already via pitchGainScale in styleConfig
            }
            FollowStyle.CHASE, FollowStyle.SPORT -> {
                roll = 0
            }
        }

        yaw = yaw.coerceIn(-cfg.maxRc, cfg.maxRc)
        throttle = throttle.coerceIn(-cfg.maxRc, cfg.maxRc)
        pitch = pitch.coerceIn(-cfg.maxPitchRc, cfg.maxPitchRc)
        roll = roll.coerceIn(-cfg.maxRc, cfg.maxRc)

        return RcCommand(roll = roll, pitch = pitch, throttle = throttle, yaw = yaw)
    }

    private fun applyDeadzone(e: Float, deadzone: Float): Float =
        if (kotlin.math.abs(e) < deadzone) 0f else e

    private data class StyleConfig(
        /** Scale for yaw + throttle AxisPid outputs. */
        val gainScale: Float,
        /** Scale for pitch only (distance follow). */
        val pitchGainScale: Float,
        val deadzone: Float,
        /** Soft clamp for yaw / throttle / roll. */
        val maxRc: Int,
        /** Soft clamp for pitch (may exceed maxRc for CHASE distance snap). */
        val maxPitchRc: Int,
    )

    private fun styleConfig(style: FollowStyle): StyleConfig = when (style) {
        // Soft cinematic (pre-speed-tweak Chase, before Chase was remapped to Sport)
        FollowStyle.CHASE -> StyleConfig(
            gainScale = 0.55f,
            pitchGainScale = 1.8f,
            deadzone = 0.08f,
            maxRc = 35,
            maxPitchRc = 50,
        )
        FollowStyle.SPOTLIGHT -> StyleConfig(
            gainScale = 1.0f,
            pitchGainScale = 0f,
            deadzone = baseDeadzone,
            maxRc = 50,
            maxPitchRc = 0,
        )
        FollowStyle.ORBIT -> StyleConfig(
            gainScale = 1.0f,
            pitchGainScale = 0.85f,
            deadzone = baseDeadzone,
            maxRc = 50,
            maxPitchRc = 40,
        )
        // Original 1x Sport (not 1.5x/2x); sendRc allows up to SDK +/-100
        FollowStyle.SPORT -> StyleConfig(
            gainScale = 1.35f,
            pitchGainScale = 2.4f,
            deadzone = 0.025f,
            maxRc = 70,
            maxPitchRc = 70,
        )
    }

    companion object {
        /** Gentle constant strafe magnitude for ORBIT (within style maxRc soft cap). */
        const val ORBIT_ROLL_MAG = 25
    }
}

/** Classic parallel PID for one axis. */
class AxisPid(
    private val kp: Float,
    private val ki: Float,
    private val kd: Float,
    private val iLimit: Float = 50f,
) {
    private var integral = 0f
    private var prevError = 0f
    private var hasPrev = false

    fun reset() {
        integral = 0f
        prevError = 0f
        hasPrev = false
    }

    fun step(error: Float, dt: Float): Float {
        integral = (integral + error * dt).coerceIn(-iLimit, iLimit)
        val derivative = if (hasPrev && dt > 1e-4f) (error - prevError) / dt else 0f
        prevError = error
        hasPrev = true
        return kp * error + ki * integral + kd * derivative
    }
}
