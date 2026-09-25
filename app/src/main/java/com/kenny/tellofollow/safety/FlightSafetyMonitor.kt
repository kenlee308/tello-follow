package com.kenny.tellofollow.safety

import com.kenny.tellofollow.tello.TelloTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SafetyLevel {
    OK,
    WARN,
    CRITICAL,
}

data class SafetyEvent(
    val level: SafetyLevel,
    val message: String,
    val action: SafetyAction = SafetyAction.NONE,
)

enum class SafetyAction {
    NONE,
    HOVER,
    LAND,
    STOP_FOLLOW,
}

/**
 * Reactive flight safety rules:
 * - bat ≤ 20% → warn
 * - bat ≤ 10% → auto land if flying
 * - Lost face for [lostFaceThreshold] frames while following → hover, optionally land
 * - Disconnect / socket error → stop follow, attempt land
 */
class FlightSafetyMonitor(
    private val lostFaceThreshold: Int = 45, // ~3s at 15 fps
    private val landAfterLostFace: Boolean = false,
) {
    private val _events = MutableStateFlow(
        SafetyEvent(SafetyLevel.OK, "Ready")
    )
    val events: StateFlow<SafetyEvent> = _events.asStateFlow()

    private var lostFaceStreak = 0
    private var lastBatteryWarnBand = 100

    fun onTelemetry(t: TelloTelemetry, isFlying: Boolean): SafetyAction {
        val bat = t.battery
        if (bat in 0..10 && isFlying) {
            emit(SafetyLevel.CRITICAL, "Battery $bat% — auto landing", SafetyAction.LAND)
            return SafetyAction.LAND
        }
        if (bat in 0..20) {
            if (bat < lastBatteryWarnBand) {
                emit(SafetyLevel.WARN, "Low battery $bat%", SafetyAction.NONE)
                lastBatteryWarnBand = bat
            }
        } else {
            lastBatteryWarnBand = 100
        }
        return SafetyAction.NONE
    }

    /**
     * Call each follow-loop iteration.
     * @param hasFace true if detector returned a face this frame
     */
    fun onFollowFrame(hasFace: Boolean, followEnabled: Boolean): SafetyAction {
        if (!followEnabled) {
            lostFaceStreak = 0
            return SafetyAction.NONE
        }
        if (hasFace) {
            lostFaceStreak = 0
            return SafetyAction.NONE
        }
        lostFaceStreak++
        if (lostFaceStreak >= lostFaceThreshold) {
            lostFaceStreak = 0
            return if (landAfterLostFace) {
                emit(SafetyLevel.CRITICAL, "Subject lost — landing", SafetyAction.LAND)
                SafetyAction.LAND
            } else {
                emit(SafetyLevel.WARN, "Subject lost — hovering", SafetyAction.HOVER)
                SafetyAction.HOVER
            }
        }
        return SafetyAction.NONE
    }

    fun onDisconnect(isFlying: Boolean): SafetyAction {
        emit(SafetyLevel.CRITICAL, "Link lost", SafetyAction.STOP_FOLLOW)
        return if (isFlying) SafetyAction.LAND else SafetyAction.STOP_FOLLOW
    }

    fun onSocketError(message: String, isFlying: Boolean): SafetyAction {
        emit(SafetyLevel.CRITICAL, "Socket error: $message", SafetyAction.STOP_FOLLOW)
        return if (isFlying) SafetyAction.LAND else SafetyAction.STOP_FOLLOW
    }

    fun clear() {
        lostFaceStreak = 0
        emit(SafetyLevel.OK, "Ready", SafetyAction.NONE)
    }

    private fun emit(level: SafetyLevel, message: String, action: SafetyAction) {
        _events.value = SafetyEvent(level, message, action)
    }
}
