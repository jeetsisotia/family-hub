package com.familyhub.app.ui.money

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** iOS system colours, one per category or slice. */
val ChartColors: List<Color> = listOf(
    0xFF0A84FF, 0xFF34C759, 0xFFFF9500, 0xFFFF3B30, 0xFFAF52DE, 0xFFFF2D55,
    0xFF5AC8FA, 0xFF5856D6, 0xFFFFCC00, 0xFF30D158, 0xFFA2845E, 0xFF8E8E93
).map { Color(it) }

fun chartColor(i: Int): Color = ChartColors[((i % ChartColors.size) + ChartColors.size) % ChartColors.size]

private val inrFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }

/** ₹1,23,456 */
fun inr(v: Double): String = inrFormat.format(v)

/** ₹1.2 L, ₹45 k, ₹950 — for chart labels. */
fun inrShort(v: Double): String {
    val a = abs(v)
    val sign = if (v < 0) "-" else ""
    return when {
        a >= 10_000_000 -> sign + "₹%.1f Cr".format(a / 10_000_000)
        a >= 100_000 -> sign + "₹%.1f L".format(a / 100_000)
        a >= 1_000 -> sign + "₹%.0f k".format(a / 1_000)
        else -> sign + "₹%.0f".format(a)
    }
}

/** Ring chart with the total in the middle. Slices animate in. */
@Composable
fun DonutChart(
    slices: List<Pair<String, Double>>,
    centerTop: String,
    centerBottom: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp
) {
    val still = com.familyhub.app.core.theme.reducedMotion()
    val progress = remember(slices) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(slices) { if (still) progress.snapTo(1f) else progress.animateTo(1f, tween(700)) }
    val total = slices.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.15f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            var start = -90f
            slices.forEachIndexed { i, (_, v) ->
                val sweep = (v / total * 360.0).toFloat() * progress.value
                if (sweep > 0.4f) {
                    drawArc(
                        chartColor(i), start, (sweep - 1.2f).coerceAtLeast(0.4f), false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Butt)
                    )
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                centerBottom,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/** Legend rows for a donut: colour dot, name, amount and share. */
@Composable
fun DonutLegend(slices: List<Pair<String, Double>>, onClick: ((String) -> Unit)? = null, selected: String? = null) {
    val total = slices.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        slices.forEachIndexed { i, (name, v) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected == name) chartColor(i).copy(alpha = 0.14f) else Color.Transparent)
                    .then(if (onClick != null) Modifier.clickable { onClick(name) } else Modifier)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(chartColor(i)))
                Spacer(modifier = Modifier.width(8.dp))
                Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(inr(v), fontWeight = FontWeight.SemiBold)
                Text(
                    "  %d%%".format((v / total * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

/** Vertical bars, one per month or day. The selected bar is solid. */
@Composable
fun BarChart(
    labels: List<String>,
    values: List<Double>,
    selected: Int?,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    color: Color = Color(0xFF0A84FF),
    goal: Double? = null,
    onClick: ((Int) -> Unit)? = null
) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(goal ?: 0.0).coerceAtLeast(1.0)
    val still = com.familyhub.app.core.theme.reducedMotion()
    val progress = remember(values) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(values) { if (still) progress.snapTo(1f) else progress.animateTo(1f, tween(600)) }
    val barArea = height - 34.dp
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { i, v ->
            val isSel = selected == i
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (onClick != null) Modifier.clickable { onClick(i) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    if (isSel && v > 0) inrShortOrCount(v, goal) else "",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                val h = (barArea.value * (v / max).toFloat() * progress.value).coerceAtLeast(2f)
                val reached = goal != null && v >= goal
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth(0.62f)
                        .height(h.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(
                            when {
                                reached -> Color(0xFF34C759)
                                isSel || selected == null -> color
                                else -> color.copy(alpha = 0.35f)
                            }
                        )
                )
                Text(
                    labels.getOrElse(i) { "" },
                    fontSize = 9.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSel) 1f else 0.6f),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

private fun inrShortOrCount(v: Double, goal: Double?): String =
    if (goal != null) NumberFormat.getIntegerInstance().format(v.toLong()) else inrShort(v)

/** A labelled horizontal bar, for comparisons such as spend by member or payment method. */
@Composable
fun HBar(label: String, value: Double, max: Double, valueText: String, color: Color, modifier: Modifier = Modifier) {
    val still = com.familyhub.app.core.theme.reducedMotion()
    val progress = remember(value, max) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(value, max) { if (still) progress.snapTo(1f) else progress.animateTo(1f, tween(600)) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(valueText, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            val f = if (max <= 0) 0f else (value / max).toFloat().coerceIn(0f, 1f) * progress.value
            Box(
                modifier = Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

/** Circular progress ring, for steps and goals. */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    color: Color = Color(0xFF34C759),
    content: @Composable () -> Unit
) {
    val still = com.familyhub.app.core.theme.reducedMotion()
    val anim = remember { Animatable(if (still) fraction.coerceIn(0f, 1f) else 0f) }
    LaunchedEffect(fraction) { if (still) anim.snapTo(fraction.coerceIn(0f, 1f)) else anim.animateTo(fraction.coerceIn(0f, 1f), tween(800)) }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.11f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * anim.value, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

/** A small line chart scaled between the lowest and highest value, for trends such as weight. */
@Composable
fun LineChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFAF52DE),
    height: Dp = 110.dp,
    valueText: (Double) -> String = { "%.1f".format(it) }
) {
    if (values.isEmpty()) return
    val min = values.minOrNull() ?: 0.0
    val max = values.maxOrNull() ?: 0.0
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    val still = com.familyhub.app.core.theme.reducedMotion()
    val progress = remember(values) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(values) { if (still) progress.snapTo(1f) else progress.animateTo(1f, tween(700)) }
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val padX = this.size.width / (values.size * 2f)
            val padY = 12.dp.toPx()
            val w = this.size.width - 2 * padX
            val h = this.size.height - 2 * padY
            val pts = values.mapIndexed { i, v ->
                val x = padX + if (values.size == 1) w / 2 else w * i / (values.size - 1)
                val y = padY + h * (1f - ((v - min) / span).toFloat())
                Offset(x, y)
            }
            val shown = (pts.size * progress.value).toInt().coerceIn(1, pts.size)
            val path = androidx.compose.ui.graphics.Path().apply {
                pts.take(shown).forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            pts.take(shown).forEach { p ->
                drawCircle(color, 5.dp.toPx(), p)
                drawCircle(Color.White, 2.5.dp.toPx(), p)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            values.forEachIndexed { i, v ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(valueText(v), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        labels.getOrElse(i) { "" }, fontSize = 9.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
