package com.kenny.tellofollow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kenny.tellofollow.safety.SafetyLevel
import com.kenny.tellofollow.tracking.FollowMode
import com.kenny.tellofollow.tracking.FollowStyle
import com.kenny.tellofollow.viewmodel.FlightViewModel

@Composable
fun FlightScreen(
    viewModel: FlightViewModel,
    onRecordClick: () -> Unit = { viewModel.toggleRecord() },
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val detection by viewModel.detection.collectAsStateWithLifecycle()
    val safety by viewModel.safetyEvents.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Always keep SurfaceView alive for MediaCodec preview + PixelCopy grabs.
        // Do NOT swap to Compose Image when bitmaps arrive — that would destroy the Surface.
        VideoSurface(
            modifier = Modifier.fillMaxSize(),
            grabEnabled = ui.streaming,
            onSurfaceReady = { viewModel.setPreviewSurface(it) },
            onFrameBitmap = { viewModel.onGrabbedFrame(it) },
        )
        OverlayCanvas(
            detection = detection,
            followEnabled = ui.followEnabled,
            followStyle = ui.followStyle,
            modifier = Modifier.fillMaxSize(),
        )

        // Top telemetry bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TelemetryChip("BAT", "${telemetry.battery}%", batColor(telemetry.battery))
            TelemetryChip("H", "${telemetry.heightCm}cm")
            TelemetryChip("YAW", "${telemetry.yaw}°")
            TelemetryChip("WIFI", if (telemetry.wifiSnr >= 0) "${telemetry.wifiSnr}" else "—")
            Text(
                text = ui.statusMessage,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            if (safety.level != SafetyLevel.OK) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (safety.level == SafetyLevel.CRITICAL) Color(0xFFFF1744) else Color(0xFFFFB300),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(safety.message, color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // Left stick: throttle (Y) + yaw (X)
        JoystickPad(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp),
            onMove = { x, y ->
                // Left stick: yaw (X) + throttle (Y); leave right-stick axes alone
                viewModel.onManualRc(
                    throttle = y.toRc(),
                    yaw = x.toRc(),
                )
            },
        )

        // Right stick: pitch (Y) + roll (X)
        JoystickPad(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            stickColor = Color(0xFF64B5F6),
            onMove = { x, y ->
                // Right stick: roll (X) + pitch (Y)
                viewModel.onManualRc(
                    roll = x.toRc(),
                    pitch = y.toRc(),
                )
            },
        )

        // Center control cluster
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ui.connected) {
                    FilledTonalButton(onClick = { viewModel.connect() }) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Connect")
                    }
                } else {
                    FilledTonalButton(onClick = { viewModel.disconnect() }) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Disconnect")
                    }
                }
                Button(
                    onClick = { viewModel.takeoff() },
                    enabled = ui.connected && !ui.flying,
                ) {
                    Icon(Icons.Default.FlightTakeoff, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Takeoff")
                }
                Button(
                    onClick = { viewModel.land() },
                    enabled = ui.connected, // always allow Land while linked — flying flag can lag
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                ) {
                    Icon(Icons.Default.FlightLand, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Land")
                }
                Button(
                    onClick = { viewModel.emergency() },
                    enabled = ui.connected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("EMERGENCY", fontWeight = FontWeight.Bold)
                }
            }
            // Follow flight styles (selectable anytime; active when Follow ON)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FollowStyleChip(
                    label = "Chase",
                    selected = ui.followStyle == FollowStyle.CHASE,
                    onClick = { viewModel.setFollowStyle(FollowStyle.CHASE) },
                )
                FollowStyleChip(
                    label = "Spot",
                    selected = ui.followStyle == FollowStyle.SPOTLIGHT,
                    onClick = { viewModel.setFollowStyle(FollowStyle.SPOTLIGHT) },
                )
                FollowStyleChip(
                    label = "Orbit",
                    selected = ui.followStyle == FollowStyle.ORBIT,
                    onClick = { viewModel.setFollowStyle(FollowStyle.ORBIT) },
                )
                FollowStyleChip(
                    label = "Sport",
                    selected = ui.followStyle == FollowStyle.SPORT,
                    onClick = { viewModel.setFollowStyle(FollowStyle.SPORT) },
                )
                FollowStyleChip(
                    label = "Fast",
                    selected = ui.fastSticks,
                    onClick = { viewModel.toggleFastSticks() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { viewModel.cycleFollowMode() },
                    enabled = ui.connected,
                ) {
                    Text(
                        when (ui.followMode) {
                            FollowMode.BODY -> "Body"
                            FollowMode.FACE -> "Face"
                        }
                    )
                }
                Button(
                    onClick = { viewModel.toggleFollow() },
                    enabled = ui.connected && ui.flying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ui.followEnabled) Color(0xFF00E5A0) else MaterialTheme.colorScheme.surface,
                        contentColor = if (ui.followEnabled) Color.Black else Color.White,
                    ),
                ) {
                    Icon(Icons.Default.PersonSearch, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (ui.followEnabled) "Follow ON" else "Follow OFF")
                }
                FilledTonalButton(
                    onClick = { viewModel.takePhoto() },
                    enabled = ui.streaming,
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Photo")
                }
                FilledTonalButton(
                    onClick = onRecordClick,
                    enabled = ui.streaming,
                ) {
                    Icon(
                        if (ui.recording) Icons.Default.Stop else Icons.Default.Videocam,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (ui.recording) "Stop" else "Record")
                }
            }
        }
    }
}


@Composable
private fun FollowStyleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.padding(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) Color(0xFF00E5A0) else Color.White.copy(alpha = 0.12f),
            contentColor = if (selected) Color.Black else Color.White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun TelemetryChip(label: String, value: String, valueColor: Color = Color(0xFF00E5A0)) {
    Column(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

private fun batColor(bat: Int): Color = when {
    bat < 0 -> Color.Gray
    bat <= 10 -> Color(0xFFFF1744)
    bat <= 20 -> Color(0xFFFFB300)
    else -> Color(0xFF00E5A0)
}
