package com.kenny.tellofollow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.kenny.tellofollow.tracking.DetectionResult

/**
 * Draws normalized face bbox + center crosshair over the video.
 */
@Composable
fun OverlayCanvas(
    detection: DetectionResult?,
    followEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val boxColor = if (followEnabled) Color(0xFF00E5A0) else Color(0xFFFFB300)
    Canvas(modifier = modifier) {
        // Center reticle
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawLine(Color.White.copy(alpha = 0.4f), Offset(cx - 24f, cy), Offset(cx + 24f, cy), strokeWidth = 2f)
        drawLine(Color.White.copy(alpha = 0.4f), Offset(cx, cy - 24f), Offset(cx, cy + 24f), strokeWidth = 2f)

        detection?.let { d ->
            val left = d.box.left * size.width
            val top = d.box.top * size.height
            val w = (d.box.right - d.box.left) * size.width
            val h = (d.box.bottom - d.box.top) * size.height
            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = 3f),
            )
            val fcx = d.centerX * size.width
            val fcy = d.centerY * size.height
            drawCircle(boxColor, radius = 6f, center = Offset(fcx, fcy))
        }
    }
}
