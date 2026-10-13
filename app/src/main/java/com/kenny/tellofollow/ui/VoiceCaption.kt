package com.kenny.tellofollow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kenny.tellofollow.voice.VoiceUiState

/** Caption: Heard / -> ACTION / would do or blocked. Hidden when idle and empty. */
@Composable
fun VoiceCaption(state: VoiceUiState, dryRun: Boolean, modifier: Modifier = Modifier) {
    if (!state.listening && state.heard.isEmpty() && state.error.isEmpty() && state.action.isEmpty() && state.gateLine.isEmpty()) return
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        val heard = when {
            state.heard.isNotEmpty() -> "Heard: \"${state.heard}\"" + if (state.partial) " ..." else ""
            state.listening -> "Listening..."
            else -> ""
        }
        if (heard.isNotEmpty()) Text(heard, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        if (state.action.isNotEmpty()) {
            Text(
                state.action,
                color = if (state.action.contains("no match")) Color(0xFFFFB300) else Color(0xFF00E5A0),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (state.gateLine.isNotEmpty()) {
            Text(
                state.gateLine,
                color = if (state.gateLine.startsWith("blocked")) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (state.error.isNotEmpty()) Text(state.error, color = Color(0xFFFF8A80), fontSize = 12.sp)
        if (dryRun) Text("VOICE DRY RUN - no drone commands", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    }
}

/** Hold-to-talk mic button. */
@Composable
fun PushToTalkButton(
    listening: Boolean,
    available: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .background(
                when {
                    !available -> Color.Gray.copy(alpha = 0.4f)
                    listening -> Color(0xFFFF1744)
                    else -> Color.White.copy(alpha = 0.18f)
                },
                CircleShape,
            )
            .pointerInput(available) {
                if (!available) return@pointerInput
                detectTapGestures(onPress = {
                    onPress()
                    tryAwaitRelease()
                    onRelease()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (available) Icons.Default.Mic else Icons.Default.MicOff, contentDescription = "Hold to talk", tint = Color.White)
    }
}