package com.kenny.tellofollow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.kenny.tellofollow.tracking.DetectionResult
import com.kenny.tellofollow.tracking.DetectionSource

/**
 * Draws normalized body/face bbox, pose skeleton (when available), and center crosshair.
 */
@Composable
fun OverlayCanvas(
    detection: DetectionResult?,
    followEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val boxColor = if (followEnabled) Color(0xFF00E5A0) else Color(0xFFFFB300)
    val skeletonColor = if (followEnabled) Color(0xFF69F0AE) else Color(0xFFFFD54F)
    Canvas(modifier = modifier) {
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

            if (d.source == DetectionSource.BODY && d.landmarks.isNotEmpty()) {
                for ((ia, ib) in d.skeletonEdges) {
                    if (ia !in d.landmarks.indices || ib !in d.landmarks.indices) continue
                    val a = d.landmarks[ia]
                    val b = d.landmarks[ib]
                    drawLine(
                        color = skeletonColor.copy(alpha = 0.9f),
                        start = Offset(a.x * size.width, a.y * size.height),
                        end = Offset(b.x * size.width, b.y * size.height),
                        strokeWidth = 3.5f,
                    )
                }
                for (lm in d.landmarks) {
                    drawCircle(
                        color = skeletonColor,
                        radius = 5f,
                        center = Offset(lm.x * size.width, lm.y * size.height),
                    )
                }
            }

            val fcx = d.centerX * size.width
            val fcy = d.centerY * size.height
            drawCircle(boxColor, radius = 7f, center = Offset(fcx, fcy))
            drawCircle(Color.White, radius = 2.5f, center = Offset(fcx, fcy))
        }
    }
}
