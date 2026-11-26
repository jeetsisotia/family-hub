package com.familyhub.app.ui.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.LocalGlassDark
import com.familyhub.app.core.theme.glass

/** The assistant's colours: warm orange through pink and violet to cyan, like a soft glow. */
val AiColors = listOf(
    Color(0xFFFF9F0A), Color(0xFFFF375F), Color(0xFFBF5AF2), Color(0xFF7D7AFF), Color(0xFF64D2FF)
)

/** Text and icon accent that reads well on light and dark glass. */
val AiAccent = Color(0xFF8E5CF7)

fun aiBrush(): Brush = Brush.linearGradient(AiColors)

/** Gradient used for the person's own messages and the main buttons. */
fun aiFillBrush(): Brush = Brush.linearGradient(
    listOf(Color(0xFF7B61FF), Color(0xFFB15CF6), Color(0xFFF2609A))
)

/** A small glowing orb with a sparkle, slowly turning. */
@Composable
fun AiOrb(size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "orbAngle")
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .rotate(angle)
                .clip(CircleShape)
                .background(Brush.sweepGradient(AiColors + AiColors.first()))
        )
        Box(
            modifier = Modifier
                .size(size * 0.78f)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))))
        )
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A border that slowly shimmers through the assistant's colours. */
@Composable
fun Modifier.aiGlowBorder(shape: androidx.compose.ui.graphics.Shape, width: Dp = 1.5.dp, active: Boolean = true): Modifier {
    val t = rememberInfiniteTransition(label = "glow")
    val shift by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (active) 2_400 else 7_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "glowShift"
    )
    val brush = Brush.linearGradient(
        AiColors,
        start = Offset(-400f + 800f * shift, 0f),
        end = Offset(400f + 800f * shift, 300f)
    )
    return this.border(width, brush, shape)
}

/** A round button filled with the assistant gradient. */
@Composable
fun AiRoundButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(if (enabled) 8.dp else 0.dp, CircleShape, ambientColor = Color(0x557B61FF), spotColor = Color(0x667B61FF))
            .clip(CircleShape)
            .background(aiFillBrush())
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** A small pill filled with the assistant gradient, for Add / Open. */
@Composable
fun AiPillButton(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(aiFillBrush())
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** The floating glass bar where the person types or taps the mic. */
@Composable
fun AiInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onMic: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(28.dp)
    val textColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape, strong = true)
            .aiGlowBorder(shape, active = busy)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (value.isEmpty()) {
                Text(
                    if (busy) "Thinking…" else "Ask, or say what to add…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor.copy(alpha = 0.45f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = textColor, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                cursorBrush = SolidColor(AiAccent),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        if (value.isBlank()) {
            AiRoundButton(Icons.Rounded.Mic, "Speak", enabled = !busy, onClick = onMic)
        } else {
            AiRoundButton(Icons.Rounded.ArrowUpward, "Send", enabled = !busy, onClick = onSend)
        }
    }
}

/** A frosted capsule for one suggested request. */
@Composable
fun AiSuggestion(icon: ImageVector, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .glass(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape).background(aiFillBrush()),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Three softly pulsing dots while the assistant is working. */
@Composable
fun AiThinking() {
    val t = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
        AiOrb(size = 26.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Row(
            modifier = Modifier.glass(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(0, 1, 2).forEach { i ->
                val a by t.animateFloat(
                    0.25f, 1f,
                    infiniteRepeatable(tween(600, delayMillis = i * 180, easing = LinearEasing), RepeatMode.Reverse),
                    label = "dot$i"
                )
                Box(modifier = Modifier.size(8.dp).alpha(a).clip(CircleShape).background(AiColors[i + 1]))
            }
        }
    }
}

/** Bubble for the person's own message: gradient glass, white text, tail on the right. */
@Composable
fun UserBubble(text: String) {
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
    Box(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .shadow(6.dp, shape, ambientColor = Color(0x337B61FF), spotColor = Color(0x447B61FF))
            .clip(shape)
            .background(aiFillBrush())
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.05f))), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Bubble for the assistant's reply: clear glass with a small orb beside it. */
@Composable
fun AssistantBubble(text: String) {
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp)
    Row(verticalAlignment = Alignment.Bottom) {
        AiOrb(size = 26.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .glass(shape, strong = !LocalGlassDark.current)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
