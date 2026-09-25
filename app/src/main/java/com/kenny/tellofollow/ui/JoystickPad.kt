package com.kenny.tellofollow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Virtual joystick. [onMove] receives normalized x/y in [-1, 1], released → (0,0).
 * Map to rc with `(value * 100).roundToInt()`.
 */
@Composable
fun JoystickPad(
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    baseColor: Color = Color.White.copy(alpha = 0.15f),
    stickColor: Color = Color(0xFF00E5A0),
    onMove: (x: Float, y: Float) -> Unit,
) {
    var stick by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                val radius = minOf(this.size.width, this.size.height) / 2f
                detectDragGestures(
                    onDragEnd = {
                        stick = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        stick = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val next = stick + dragAmount
                        val len = hypot(next.x, next.y)
                        stick = if (len > radius) next * (radius / len) else next
                        onMove(
                            (stick.x / radius).coerceIn(-1f, 1f),
                            // Screen Y down → stick Y positive down; invert for throttle/pitch up
                            (-stick.y / radius).coerceIn(-1f, 1f),
                        )
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            drawCircle(color = baseColor, radius = r, center = c)
            drawCircle(color = Color.White.copy(alpha = 0.25f), radius = r * 0.15f, center = c)
            drawCircle(
                color = stickColor,
                radius = r * 0.28f,
                center = c + stick,
            )
        }
    }
}

fun Float.toRc(): Int = (this * 100f).roundToInt().coerceIn(-100, 100)
