package com.familyhub.app.core.theme

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Family Hub design system: one place for spacing, corners, the "Reduce transparency" setting and the
 * shared iPhone-style building blocks (segmented control, grouped lists, search field, loading, empty and
 * error states, and light haptics). Fonts and icons are Android's own (Roboto and Material icons, both
 * free to use); no Apple fonts, symbols, emoji or logos are used.
 */

/** Spacing steps used across screens. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner sizes used across screens. */
object Corner {
    val small = 12.dp
    val medium = 18.dp
    val card = 24.dp
    val sheet = 28.dp
    val pill = 30.dp
}

/**
 * "Reduce transparency" (Settings → Appearance): glass becomes solid and the moving background becomes a
 * plain colour. Helps readability and older phones. Kept on this phone only.
 */
object GlassPrefs {
    private const val PREFS = "family_hub_ui"
    private const val KEY = "reduce_transparency"
    val reduceTransparency = mutableStateOf(false)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        reduceTransparency.value = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun setReduceTransparency(ctx: Context, on: Boolean) {
        loaded = true
        reduceTransparency.value = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/** True when glass should be drawn solid ("Reduce transparency" is on). */
val LocalSolidGlass = staticCompositionLocalOf { false }

@Composable
fun solidGlassChosen(): Boolean {
    GlassPrefs.load(LocalContext.current)
    return GlassPrefs.reduceTransparency.value
}

/** A light tap feel for selections (tabs, segments, toggles). Uses the phone's own haptics setting. */
@Composable
fun rememberTick(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

/**
 * Large titles that shrink when the page scrolls. The app gives each page one of these; any list on the page
 * reports how far it has scrolled, and the title bar reads it.
 */
class TitleCollapse {
    var offsetPx by mutableFloatStateOf(0f)

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            offsetPx = (offsetPx - consumed.y).coerceAtLeast(0f)
            return Offset.Zero
        }
    }
}

val LocalTitleCollapse = staticCompositionLocalOf<TitleCollapse?> { null }

/** True once the page has scrolled far enough for the large title to shrink. */
@Composable
fun titleCollapsed(): Boolean {
    val c = LocalTitleCollapse.current ?: return false
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    val collapsed by remember(c, threshold) { derivedStateOf { c.offsetPx > threshold } }
    return collapsed
}

/** iPhone-style segmented control: one rounded track with a sliding highlighted segment. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    val tick = rememberTick()
    val dark = LocalGlassDark.current
    val track = if (dark) Color(0x33767680) else Color(0x1F767680)
    val thumb = if (dark) Color(0xFF636366) else Color.White
    val index = options.indexOf(selected).coerceAtLeast(0)
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val segmentDp = with(density) { (widthPx / options.size).toDp() }
    val thumbX by animateDpAsState(
        targetValue = segmentDp * index,
        animationSpec = if (reducedMotion()) spring(stiffness = 100_000f) else spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "segment"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(track)
            .padding(2.dp)
            .onSizeChanged { widthPx = it.width }
    ) {
        if (widthPx > 0) {
            Box(
                modifier = Modifier
                    .offset(x = thumbX)
                    .width(segmentDp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(thumb)
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clickable(interactionSource = interaction, indication = null) {
                            if (i != index) {
                                tick()
                                onSelect(label)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}

/** Grouped list section (like iPhone Settings): a small grey heading over one rounded glass card. */
@Composable
fun GroupedSection(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(Corner.medium))
                .padding(vertical = 2.dp),
            content = content
        )
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)
            )
        }
    }
}

/** One row in a [GroupedSection]: icon tile, title, optional note and value, then a chevron or a trailing control. */
@Composable
fun GroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    icon: ImageVector? = null,
    tile: Int = 0,
    value: String? = null,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tileBrush(tile)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (!note.isNullOrBlank()) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                }
            }
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            when {
                trailing != null -> {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailing()
                }
                onClick != null -> Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = if (icon != null) 56.dp else 14.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/** A grouped row with a switch, for on/off settings. */
@Composable
fun GroupedSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    note: String? = null,
    icon: ImageVector? = null,
    tile: Int = 0,
    divider: Boolean = true
) {
    val tick = rememberTick()
    GroupedRow(
        title = title,
        note = note,
        icon = icon,
        tile = tile,
        divider = divider,
        onClick = { tick(); onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = { tick(); onChange(it) }) }
    )
}

/** Rounded search field with a magnifier and a clear button. */
@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val dark = LocalGlassDark.current
    val fill = if (dark) Color(0x3D767680) else Color(0x1F767680)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear search",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onValueChange("") }
            )
        }
    }
}

/** A friendly empty page: icon, title, one line of help and an optional button. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(64.dp).glass(CircleShape, strong = true),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** A clear error with a Retry button; says plainly what went wrong. */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    EmptyState(
        icon = Icons.Rounded.CloudOff,
        title = "Couldn't load this",
        message = message,
        modifier = modifier,
        actionLabel = if (onRetry != null) "Try again" else null,
        onAction = onRetry
    )
}

/** Loading: a spinner with a short label, or grey placeholder rows that gently pulse (still when animations are off). */
@Composable
fun LoadingState(label: String = "Loading…", modifier: Modifier = Modifier, rows: Int = 0) {
    if (rows <= 0) {
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
        }
        return
    }
    val still = reducedMotion()
    var bright by remember { mutableStateOf(false) }
    LaunchedEffect(still) {
        if (!still) while (true) {
            kotlinx.coroutines.delay(700)
            bright = !bright
        }
    }
    val alpha by animateFloatAsState(if (bright) 0.55f else 0.3f, label = "skeleton")
    val dark = LocalGlassDark.current
    val bar = if (dark) Color.White else Color(0xFF8E8E93)
    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(Corner.medium)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).graphicsLayer { this.alpha = alpha }.background(bar.copy(alpha = 0.35f)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).graphicsLayer { this.alpha = alpha }.background(bar.copy(alpha = 0.35f)))
                    Box(Modifier.fillMaxWidth(0.9f).height(10.dp).clip(RoundedCornerShape(5.dp)).graphicsLayer { this.alpha = alpha }.background(bar.copy(alpha = 0.25f)))
                }
            }
        }
    }
}

/** A small red count bubble (unread messages and similar). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .heightIn(min = 18.dp)
            .widthIn(min = 18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .border(1.5.dp, if (LocalGlassDark.current) Color.Black else Color.White, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** Used by the glass helpers: true while Reduce transparency is on (kept here so the colours live in one file). */
@Composable
internal fun glassIsSolid(): Boolean = LocalSolidGlass.current
