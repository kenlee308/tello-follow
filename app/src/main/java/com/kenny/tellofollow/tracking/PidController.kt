package com.kenny.tellofollow.tracking

/**
 * Three independent PIDs mapping subject bbox error to Tello `rc` axes,
 * shaped by [FollowStyle] (flight personality — distinct from [FollowMode] body/face).
 *
 * Mapping (standard Tello rc a b c d):
 * - **yaw (d)** <- horizontal center error
 * - **throttle (c)** <- vertical center error (inverted: below center => climb)
 * - **pitch (b)** <- size error vs target (too small => fly forward); forced 0 in SPOTLIGHT
 * - **roll (a)** 0 in CHASE/SPOTLIGHT/CINEMATIC/SPORT; sticky strafe in ORBIT
 *
 * Target relative size is larger for BODY (full-frame torso bbox) than FACE.
 * Style outputs are soft-clamped here; [TelloCommandClient.MAX_RC] (=50) still caps on send.
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
    /** Default: yaw + throttle + pitch for distance; roll = 0. */
    CHASE,
    /** Tripod tracking: hover translation; yaw + throttle only. */
    SPOTLIGHT,
    /** Circle: sticky strafe roll while yaw keeps subject centered. */
    ORBIT,
    /** Softer CHASE: larger deadzone, lower max |rc|. */
    CINEMATIC,
    /** Snappier CHASE: higher gains, higher max |rc| (still soft-capped on send). */
    SPORT,
}

class PidController(
    private val faceTargetSize: Float = 0.08f,
    private val bodyTargetSize: Float = 0.18f,
    private val baseDeadzone: Float = 0.04f,
) {
    private val yawPid = AxisPid(kp = 120f, ki = 0.0f, kd = 40f)
    private val throttlePid = AxisPid(kp = 140f, ki = 0.0f, kd = 35f)
    private val pitchPid = AxisPid(kp = 90f, ki = 0.0f, kd = 25f)

    @Volatile
    var followEnabled: Boolean = false

    /** Sticky ORBIT strafe sign: +1 = right, -1 = left. Survives brief detection loss. */
    @Volatile
    var orbitRollSign: Int = 1
        private set

    private var lastTs = 0L

    fun reset() {
        yawPid.reset()
        throttlePid.reset()
        pitchPid.reset()
        lastTs = 0L
    }

    /** Flip ORBIT strafe direction (call from UI when re-tapping Orbit). */
    fun flipOrbitDirection() {
        orbitRollSign = if (orbitRollSign >= 0) -1 else 1
    }

    fun setOrbitDirection(sign: Int) {
        orbitRollSign = if (sign >= 0) 1 else -1
    }

    /**
     * @param detection latest body/face, or null if lost
     * @param style flight personality (default CHASE = legacy behavior)
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

        val targetSize = when (detection.source) {
            DetectionSource.BODY -> bodyTargetSize
            DetectionSource.FACE -> faceTargetSize
        }

        val cfg = styleConfig(style)
        val errorX = applyDeadzone(detection.centerX - 0.5f, cfg.deadzone)
        val errorY = applyDeadzone(detection.centerY - 0.5f, cfg.deadzone)
        val errorSize = applyDeadzone(targetSize - detection.relativeSize, cfg.deadzone)

        var yaw = (yawPid.step(errorX, dt) * cfg.gainScale).toInt()
        var throttle = (-throttlePid.step(errorY, dt) * cfg.gainScale).toInt()
        var pitch = (pitchPid.step(errorSize, dt) * cfg.gainScale).toInt()
        var roll = 0

        when (style) {
            FollowStyle.SPOTLIGHT -> {
                pitch = 0
                roll = 0
            }
            FollowStyle.ORBIT -> {
                roll = orbitRollSign * ORBIT_ROLL_MAG
                // Keep a light pitch for distance so orbit radius stays usable
            }
            FollowStyle.CHASE, FollowStyle.CINEMATIC, FollowStyle.SPORT -> {
                roll = 0
            }
        }

        yaw = yaw.coerceIn(-cfg.maxRc, cfg.maxRc)
        throttle = throttle.coerceIn(-cfg.maxRc, cfg.maxRc)
        pitch = pitch.coerceIn(-cfg.maxRc, cfg.maxRc)
        roll = roll.coerceIn(-cfg.maxRc, cfg.maxRc)

        return RcCommand(roll = roll, pitch = pitch, throttle = throttle, yaw = yaw)
    }

    private fun applyDeadzone(e: Float, deadzone: Float): Float =
        if (kotlin.math.abs(e) < deadzone) 0f else e

    private data class StyleConfig(
        val gainScale: Float,
        val deadzone: Float,
        val maxRc: Int,
    )

    private fun styleConfig(style: FollowStyle): StyleConfig = when (style) {
        FollowStyle.CHASE -> StyleConfig(gainScale = 1.0f, deadzone = baseDeadzone, maxRc = 50)
        FollowStyle.SPOTLIGHT -> StyleConfig(gainScale = 1.0f, deadzone = baseDeadzone, maxRc = 50)
        FollowStyle.ORBIT -> StyleConfig(gainScale = 1.0f, deadzone = baseDeadzone, maxRc = 50)
        FollowStyle.CINEMATIC -> StyleConfig(gainScale = 0.55f, deadzone = 0.08f, maxRc = 35)
        FollowStyle.SPORT -> StyleConfig(gainScale = 1.35f, deadzone = 0.025f, maxRc = 70)
    }

    companion object {
        /** Gentle constant strafe magnitude for ORBIT (within MAX_RC soft cap). */
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
