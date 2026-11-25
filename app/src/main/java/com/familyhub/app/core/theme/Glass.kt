package com.familyhub.app.core.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.familyhub.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** iOS-style system gray background with slowly drifting blue, lavender and mint glows. Only the drawing updates. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val dark = LocalGlassDark.current
    val transition = rememberInfiniteTransition(label = "aurora")
    val t1 = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "aurora1"
    )
    val t2 = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "aurora2"
    )
    val base = if (dark) listOf(Color(0xFF07070A), Color(0xFF000000)) else listOf(Color(0xFFF6F6F9), Color(0xFFE8E8EE))
    val glows = if (dark) {
        listOf(Color(0x661A3A8A), Color(0x523A1E6B), Color(0x45104A48))
    } else {
        listOf(Color(0x70A8C8FF), Color(0x5CD2C6FF), Color(0x52B5EADF))
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(base))
        val w = size.width
        val h = size.height
        val r = maxOf(w, h)
        val a = t1.value
        val b = t2.value
        fun glow(color: Color, cx: Float, cy: Float, radius: Float) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = radius),
                radius = radius,
                center = Offset(cx, cy)
            )
        }
        glow(glows[0], w * (0.10f + 0.40f * a), h * (0.14f + 0.10f * b), r * 0.55f)
        glow(glows[1], w * (0.95f - 0.35f * b), h * (0.45f + 0.15f * a), r * 0.50f)
        glow(glows[2], w * (0.25f + 0.45f * b), h * (0.88f - 0.12f * a), r * 0.62f)
    }
}

/** Frosted glass: a see-through white fill, a bright edge and a soft shadow. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), strong: Boolean = false): Modifier = composed {
    val dark = LocalGlassDark.current
    val fill = if (dark) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (strong) 0.17f else 0.11f), Color.White.copy(alpha = if (strong) 0.10f else 0.05f))
        )
    } else {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (strong) 0.88f else 0.74f), Color.White.copy(alpha = if (strong) 0.70f else 0.50f))
        )
    }
    val edge = if (dark) {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f)))
    } else {
        Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.30f)))
    }
    this
        .shadow(
            elevation = if (dark) 0.dp else 6.dp,
            shape = shape,
            ambientColor = Color(0x141C2B4A),
            spotColor = Color(0x261C2B4A)
        )
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
}

/** Remembers a press state and returns a springy scale for "squishy" taps. */
@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "press"
    )
    return scale
}

/** A frosted glass card that gently shrinks when pressed. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glass(shape, strong)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        content = content
    )
}

/** iOS-blue glass for the important summaries and the main button. Text inside uses white. */
@Composable
fun AccentCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = LocalGlassDark.current
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    val fill = if (dark) {
        Brush.linearGradient(listOf(Color(0xFF1E7BEF), Color(0xFF0849A8)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF3B9CFF), Color(0xFF0062E0)))
    }
    val scheme = MaterialTheme.colorScheme.copy(onPrimary = Color.White)
    MaterialTheme(colorScheme = scheme) {
        Column(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(12.dp, shape, ambientColor = Color(0x33004AAD), spotColor = Color(0x55004AAD))
                .clip(shape)
                .background(fill)
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.05f))), shape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                    } else {
                        Modifier
                    }
                ),
            content = content
        )
    }
}

data class GlassTab(val label: String, val icon: ImageVector)

/** Floating frosted tab bar, like the iPhone dock. The selected tab turns blue and pops a little. */
@Composable
fun GlassTabBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(30.dp), strong = true)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val bg by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                    label = "tabBg"
                )
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    label = "tabTint"
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.15f else 1f,
                    animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
                    label = "tabScale"
                )
                val interaction = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(bg)
                        .clickable(interactionSource = interaction, indication = null) { onSelect(index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** The app icon (glass house with the family) as a rounded tile. */
@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = modifier
            .size(size)
            .shadow(18.dp, shape, ambientColor = Color(0x40004AAD), spotColor = Color(0x66004AAD))
            .clip(shape)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Family Hub",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.35f
                    scaleY = 1.35f
                }
        )
    }
}

/** Opening animation: the logo springs in over the glass background, then the app fades in. */
@Composable
fun EntryIntro(onFinished: () -> Unit) {
    val logoScale = remember { Animatable(0.55f) }
    val alpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, tween(300)) }
        launch {
            delay(250)
            textAlpha.animateTo(1f, tween(450))
        }
        logoScale.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 260f))
        delay(450)
        onFinished()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AuroraBackground()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppLogo(
                size = 116.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                    this.alpha = alpha.value
                }
            )
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "Family Hub",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.graphicsLayer { this.alpha = textAlpha.value }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your family, connected",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                modifier = Modifier.graphicsLayer { this.alpha = textAlpha.value }
            )
        }
    }
}

/** Fades and lifts an element into place the first time it appears; later items wait a little longer. */
fun Modifier.riseIn(index: Int = 0): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(70L * index)
        shown = true
    }
    val p by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "riseIn"
    )
    graphicsLayer {
        alpha = p
        translationY = (1f - p) * 48f
    }
}

/** iOS system colours used for the rounded app-style icon tiles (like the iPhone Settings app). */
private val tileColors = listOf(
    Color(0xFF4FA3FF) to Color(0xFF0A6CFF),
    Color(0xFF5BD97A) to Color(0xFF28A745),
    Color(0xFFFFB340) to Color(0xFFFF8A00),
    Color(0xFFFF6B61) to Color(0xFFE5352B),
    Color(0xFFC38BFF) to Color(0xFF8E44E8),
    Color(0xFFFF7AA2) to Color(0xFFE83E78),
    Color(0xFF5AD8E6) to Color(0xFF1BA8C4),
    Color(0xFF7B83FF) to Color(0xFF4B4FE0),
    Color(0xFFFFD54A) to Color(0xFFF2B600),
    Color(0xFF63E6BE) to Color(0xFF1FBF93),
    Color(0xFFC89B6D) to Color(0xFF9C6B3E),
    Color(0xFFA9A9B0) to Color(0xFF7C7C84)
)

/** A colourful gradient for icon tile number [index]; each tile gets a different colour. */
fun tileBrush(index: Int): Brush {
    val (top, bottom) = tileColors[((index % tileColors.size) + tileColors.size) % tileColors.size]
    return Brush.verticalGradient(listOf(top, bottom))
}
