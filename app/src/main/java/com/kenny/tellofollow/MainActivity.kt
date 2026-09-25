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
import androidx.core.content.ContextCompat
import com.kenny.tellofollow.ui.FlightScreen
import com.kenny.tellofollow.ui.theme.TelloFollowTheme
import com.kenny.tellofollow.viewmodel.FlightViewModel

/**
 * Single-activity FPV host. Locked to landscape in the manifest.
 * Phone must be joined to the Tello Wi-Fi AP (TELLO-xxxx) before Connect.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: FlightViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // SSID becomes readable when Location (and Nearby Wi-Fi on 33+) is granted.
            // Connect itself keys off 192.168.10.x, not the SSID string.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWifiNamePermissionsIfNeeded()
        enableEdgeToEdge()
        setContent {
            TelloFollowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FlightScreen(viewModel = viewModel)
                }
            }
        }
    }

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
        if (isFinishing) {
            viewModel.shutdown()
        }
    }
}
