package com.familyhub.app.core.theme

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/*
 * FamilyHub Liquid Glass: the shared pieces every screen uses for dialogs, sheets, chips, fields, buttons, search
 * and notices. Glass is a see-through fill with a bright edge. Real blur is used only where it is cheap: behind
 * dialogs on Android 12 and later (the system blurs the window behind). Long scrolling lists are never blurred.
 * "Reduce transparency" in Settings (LocalSolidGlass) turns every piece solid.
 */

private val DialogShape = RoundedCornerShape(30.dp)
private val SheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

/** Fill for floating glass panels (dialogs, sheets, menus): translucent, or solid when transparency is reduced. */
@Composable
fun glassPanelColor(): Color {
    val dark = LocalGlassDark.current
    return when {
        LocalSolidGlass.current && dark -> Color(0xFF1C1C1E)
        LocalSolidGlass.current -> Color(0xFFF9F9FB)
        dark -> Color(0xE61C1C1F)
        else -> Color(0xEBFFFFFF)
    }
}

@Composable
private fun glassEdge(): Color = if (LocalGlassDark.current) Color(0x33FFFFFF) else Color(0x14000000)

private fun View.dialogWindow(): android.view.Window? {
    var v: Any? = this
    while (v != null) {
        if (v is DialogWindowProvider) return v.window
        v = (v as? View)?.parent
    }
    return null
}

/** Blurs whatever is behind the current dialog window (Android 12+, when the phone allows it and glass is on). */
@Composable
fun BlurBehindWindow(radius: Int = 48) {
    val view = LocalView.current
    val solid = LocalSolidGlass.current
    val still = reducedMotion()
    DisposableEffect(view, solid) {
        val w = view.dialogWindow()
        if (w != null && !solid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wm = view.context.getSystemService(WindowManager::class.java)
            if (wm?.isCrossWindowBlurEnabled == true && !still) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = radius }
                w.setDimAmount(0.18f)
            }
        }
        onDispose { }
    }
}

/** Glass alert dialog: same parameters as Material's AlertDialog, frosted panel, blurred background. */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties()
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.border(0.5.dp, glassEdge(), DialogShape),
        dismissButton = dismissButton,
        icon = icon,
        title = { BlurBehindWindow(); title?.invoke() },
        text = text,
        shape = DialogShape,
        containerColor = glassPanelColor(),
        tonalElevation = 0.dp,
        properties = properties
    )
}

/** Glass bottom sheet: Material's ModalBottomSheet with a frosted panel and a lighter scrim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = glassPanelColor(),
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (LocalGlassDark.current) 0.45f else 0.22f),
        content = content
    )
}

/** One choice in a [GlassActionSheet]. */
data class GlassAction(val label: String, val destructive: Boolean = false, val icon: ImageVector? = null, val onClick: () -> Unit)

/** iPhone-style action sheet: a short list of choices and Cancel, in a glass sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassActionSheet(title: String?, actions: List<GlassAction>, onDismiss: () -> Unit) {
    val tick = rememberTick()
    GlassBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (!title.isNullOrBlank()) {
                Text(
                    title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp))) {
                actions.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = glassEdge(), thickness = 0.5.dp)
                    Row(
                        Modifier.fillMaxWidth().clickable { tick(); onDismiss(); a.onClick() }.padding(horizontal = 18.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val c = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        a.icon?.let { Icon(it, null, tint = c, modifier = Modifier.size(20.dp).padding(end = 2.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(a.label, color = c, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strong = true).clickable { onDismiss() }.padding(vertical = 15.dp),
                contentAlignment = Alignment.Center
            ) { Text("Cancel", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** Glass filter chip: same parameters as Material's FilterChip, pill shaped with a frosted fill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val dark = LocalGlassDark.current
    val tick = rememberTick()
    FilterChip(
        selected = selected,
        onClick = { tick(); onClick() },
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = if (dark) Color(0x1FFFFFFF) else Color(0x99FFFFFF),
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.30f else 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected,
            borderColor = glassEdge(), selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            borderWidth = 0.5.dp, selectedBorderWidth = 0.5.dp
        )
    )
}

/** Glass text field: same parameters as Material's OutlinedTextField, with a frosted fill and soft edge. */
@Composable
fun GlassInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = RoundedCornerShape(14.dp),
    colors: TextFieldColors = glassFieldColors()
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
        textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        prefix = prefix, suffix = suffix, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape, colors = colors
    )
}

@Composable
fun glassFieldColors(): TextFieldColors {
    val dark = LocalGlassDark.current
    val fill = if (dark) Color(0x14FFFFFF) else Color(0x8CFFFFFF)
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = fill.copy(alpha = fill.alpha * 0.6f),
        unfocusedBorderColor = if (dark) Color(0x33FFFFFF) else Color(0x1F3C3C43),
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    )
}

/** Glass floating action button: a round accent button with a soft glow. */
@Composable
fun GlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable () -> Unit
) {
    val tick = rememberTick()
    FloatingActionButton(
        onClick = { tick(); onClick() },
        modifier = modifier.border(1.dp, Color.White.copy(alpha = 0.35f), shape),
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 2.dp)
    ) { content() }
}

/**
 * The search bar: frosted pill that widens its glow when focused, with clear and (optional) voice buttons.
 * [onClickWhenIdle] turns it into a button that opens the full search page (used on Home).
 */
@Composable
fun GlassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
    onClickWhenIdle: (() -> Unit)? = null,
    requester: androidx.compose.ui.focus.FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val still = reducedMotion()
    val pad by animateDpAsState(if (focused) 13.dp else 11.dp, if (still) spring(stiffness = 10_000f) else spring(0.7f, 400f), label = "searchPad")
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), strong = focused)
            .then(if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(22.dp)) else Modifier)
            .then(if (onClickWhenIdle != null) Modifier.clickable { onClickWhenIdle() } else Modifier)
            .padding(horizontal = 14.dp, vertical = pad)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onClickWhenIdle == null) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                )
            }
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, contentDescription = "Clear search", tint = tint,
                modifier = Modifier.padding(start = 6.dp).size(22.dp).clip(CircleShape).clickable { onValueChange("") }
            )
        } else if (onVoice != null) {
            Icon(
                Icons.Rounded.Mic, contentDescription = "Search by voice", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp).size(22.dp).clip(CircleShape).clickable { onVoice() }
            )
        }
    }
}

/** A notice in a list (alerts, reminders, activity): glass card with a coloured edge for priority. */
@Composable
fun GlassNotificationCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: String? = null,
    urgent: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(shape)
            .then(if (urgent) Modifier.border(1.dp, accent.copy(alpha = 0.55f), shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = if (urgent) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Shimmering placeholder rows while data loads (still when reduced motion is on). */
@Composable
fun GlassSkeleton(rows: Int = 3, modifier: Modifier = Modifier) = LoadingState(label = "Loading…", modifier = modifier, rows = rows)

/** Glass pop-up menu: Material's DropdownMenu on a frosted, rounded panel. */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        containerColor = glassPanelColor(),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(0.5.dp, glassEdge()),
        content = content
    )
}
