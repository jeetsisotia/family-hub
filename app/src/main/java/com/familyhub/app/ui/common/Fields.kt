package com.familyhub.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.glass
import com.familyhub.app.ui.modules.localNoon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

fun formatDate(millis: Long?): String = if (millis == null) "" else dateFmt.format(Date(millis))

/**
 * Big iPhone-style title with a round glass back button and optional actions on the right. The large title
 * shrinks into a slim frosted bar once the page scrolls ([compact] keeps it slim, as in a conversation).
 */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)?,
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val collapsed = compact || com.familyhub.app.core.theme.titleCollapsed()
    val barAlpha by androidx.compose.animation.core.animateFloatAsState(if (collapsed && !compact) 1f else 0f, label = "bar")
    val dark = com.familyhub.app.core.theme.LocalGlassDark.current
    val solid = com.familyhub.app.core.theme.LocalSolidGlass.current
    val barColor = when {
        solid && dark -> androidx.compose.ui.graphics.Color(0xFF1C1C1E)
        solid -> androidx.compose.ui.graphics.Color(0xFFF9F9F9)
        dark -> androidx.compose.ui.graphics.Color(0xB31C1C1E)
        else -> androidx.compose.ui.graphics.Color(0xB8FFFFFF)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor.copy(alpha = barColor.alpha * barAlpha))
            .padding(horizontal = 8.dp, vertical = if (collapsed) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp).glass(CircleShape, strong = true)
            ) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
        } else {
            Spacer(modifier = Modifier.width(12.dp))
        }
        if (collapsed) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        } else {
            // Long titles step down in size so they fit next to the buttons instead of being cut off.
            val styles = listOf(
                MaterialTheme.typography.headlineLarge,
                MaterialTheme.typography.headlineMedium,
                MaterialTheme.typography.headlineSmall,
                MaterialTheme.typography.titleLarge
            )
            var level by remember(title) { mutableStateOf(0) }
            Text(
                text = title,
                style = styles[level],
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
                overflow = if (level == styles.size - 1) androidx.compose.ui.text.style.TextOverflow.Ellipsis
                else androidx.compose.ui.text.style.TextOverflow.Clip,
                onTextLayout = { r -> if ((r.didOverflowWidth || r.hasVisualOverflow) && level < styles.size - 1) level++ }
            )
        }
        actions()
        // The same assistant, opened with this page's context.
        val pageAi = com.familyhub.app.ui.ai.LocalPageAi.current
        if (pageAi != null) {
            com.familyhub.app.ui.ai.PageAiButton("Ask AI about $title") { pageAi(title) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
fun TextInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    secret: Boolean = false,
    supporting: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = if (supporting != null) ({ Text(supporting) }) else null,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    )
}

/** A row of tappable chips; exactly one is selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(if (o == selected) "" else o) },
                label = { Text(o) }
            )
        }
    }
}

/** A field that opens a calendar to pick a date. Dates are stored at local noon. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickField(label: String, value: Long?, onChange: (Long?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = formatDate(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.Event, contentDescription = null) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        // A transparent layer on top makes the whole field open the calendar.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = true }
        )
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis
                    onChange(if (picked != null) localNoon(picked) else null)
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onChange(null); open = false }) { Text("Clear") }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/** Turns "12,500.50" style text into a number, or null. */
fun parseNumber(text: String): Double? = text.replace(",", "").trim().toDoubleOrNull()

fun numberText(v: Any?): String {
    val d = (v as? Number)?.toDouble() ?: return (v as? String).orEmpty()
    return if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
}
