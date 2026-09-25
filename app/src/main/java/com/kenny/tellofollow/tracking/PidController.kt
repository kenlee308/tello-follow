package com.kenny.tellofollow.tracking

/**
 * Three independent PIDs mapping subject bbox error to Tello `rc` axes.
 *
 * Mapping (standard Tello rc a b c d):
 * - **yaw (d)** <- horizontal center error
 * - **throttle (c)** <- vertical center error (inverted: below center => climb)
 * - **pitch (b)** <- size error vs target (too small => fly forward)
 * - **roll (a)** held at 0 in follow mode
 *
 * Target relative size is larger for BODY (full-frame torso bbox) than FACE.
 * All outputs clamped to [-100, 100]. Deadzone avoids twitchy hover.
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

class PidController(
    private val faceTargetSize: Float = 0.08f,
    private val bodyTargetSize: Float = 0.18f,
    private val deadzone: Float = 0.04f,
) {
    private val yawPid = AxisPid(kp = 120f, ki = 0.0f, kd = 40f)
    private val throttlePid = AxisPid(kp = 140f, ki = 0.0f, kd = 35f)
    private val pitchPid = AxisPid(kp = 90f, ki = 0.0f, kd = 25f)

    @Volatile
    var followEnabled: Boolean = false

    private var lastTs = 0L

    fun reset() {
        yawPid.reset()
        throttlePid.reset()
        pitchPid.reset()
        lastTs = 0L
    }

    /**
     * @param detection latest body/face, or null if lost
     * @return RC command; [RcCommand.HOVER] if follow disabled or no detection
     */
    fun update(detection: DetectionResult?): RcCommand {
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

        val errorX = applyDeadzone(detection.centerX - 0.5f)
        val errorY = applyDeadzone(detection.centerY - 0.5f)
        val errorSize = applyDeadzone(targetSize - detection.relativeSize)

        val yaw = yawPid.step(errorX, dt).toInt().coerceIn(-100, 100)
        val throttle = (-throttlePid.step(errorY, dt)).toInt().coerceIn(-100, 100)
        val pitch = pitchPid.step(errorSize, dt).toInt().coerceIn(-100, 100)

        return RcCommand(roll = 0, pitch = pitch, throttle = throttle, yaw = yaw)
    }

    private fun applyDeadzone(e: Float): Float =
        if (kotlin.math.abs(e) < deadzone) 0f else e
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
