package com.kenny.tellofollow

import android.app.Application

/**
 * Application entry. Keep lightweight — heavy deps (MediaCodec, ML Kit) are created
 * lazily from [com.kenny.tellofollow.viewmodel.FlightViewModel].
 */
class TelloFollowApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
