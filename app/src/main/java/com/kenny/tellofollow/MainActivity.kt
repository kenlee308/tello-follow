package com.kenny.tellofollow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kenny.tellofollow.voice.GateContext
import com.kenny.tellofollow.voice.VoiceActionGate
import com.kenny.tellofollow.voice.VoiceController
import com.kenny.tellofollow.ui.FlightScreen
import com.kenny.tellofollow.ui.theme.TelloFollowTheme
import com.kenny.tellofollow.viewmodel.FlightViewModel

/**
 * Single-activity FPV host. Locked to landscape in the manifest.
 * Phone must be joined to the Tello Wi-Fi AP (TELLO-xxxx) before Connect.
 *
 * RECORD_AUDIO is requested when the user starts Record; if denied, video-only.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: FlightViewModel by viewModels()

    // v3 phase 1: dryRun = true -> voice never sends drone commands. Phases 2/3 flip this gate.
    private val voiceGate = VoiceActionGate(dryRun = true)
    private lateinit var voice: VoiceController

    private val voiceMicLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Permission dialog steals the press; user holds the mic again once granted.
            if (!granted) voice.engine.showResult("", "", "blocked: mic permission denied")
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // SSID becomes readable when Location (and Nearby Wi-Fi on 33+) is granted.
            // Connect itself keys off 192.168.10.x, not the SSID string.
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.toggleRecord(withAudio = granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWifiNamePermissionsIfNeeded()
        voice = VoiceController(
            context = this,
            contextProvider = {
                val ui = viewModel.ui.value
                GateContext(
                    connected = ui.connected,
                    flying = ui.flying,
                    followEnabled = ui.followEnabled,
                    battery = viewModel.telemetry.value.battery,
                )
            },
            gate = voiceGate,
        )
        enableEdgeToEdge()
        setContent {
            TelloFollowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val voiceState by voice.state.collectAsStateWithLifecycle()
                    FlightScreen(
                        viewModel = viewModel,
                        onRecordClick = { onRecordClicked() },
                        voiceState = voiceState,
                        voiceDryRun = voiceGate.dryRun,
                        onMicPress = { onMicPressed() },
                        onMicRelease = { voice.engine.stopListening() },
                    )
                }
            }
        }
    }

    private fun onRecordClicked() {
        if (viewModel.ui.value.recording) {
            viewModel.toggleRecord(withAudio = false) // stop path ignores withAudio
            return
        }
        if (hasMicPermission()) {
            viewModel.toggleRecord(withAudio = true)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onMicPressed() {
        if (hasMicPermission()) voice.engine.startListening()
        else voiceMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestWifiNamePermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onHostStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.destroy()
        if (isFinishing) {
            viewModel.shutdown()
        }
    }
}
