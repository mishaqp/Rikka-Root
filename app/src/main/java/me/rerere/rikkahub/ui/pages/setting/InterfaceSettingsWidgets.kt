package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.hooks.HapticAction
import me.rerere.rikkahub.ui.hooks.rememberAppHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Compact native rows and grouped cards follow Moru's mobile settings layout. */
@Composable
internal fun InterfaceSettingsScaffold(
    @StringRes title: Int,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("ui_settings_page"),
        topBar = { TopAppBar(title = { Text(stringResource(title), style = MaterialTheme.typography.titleMedium, maxLines = 2) }, navigationIcon = { BackButton() }, actions = actions) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("ui_settings_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun InterfaceSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f), RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
internal fun InterfaceDivider() = HorizontalDivider(
    Modifier.padding(start = 14.dp, end = 12.dp), thickness = .6.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .25f),
)

@Composable
internal fun InterfaceNavigationRow(
    @StringRes title: Int,
    icon: ImageVector? = null,
    detail: String? = null,
    @StringRes description: Int? = null,
    detailContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptics = rememberAppHapticFeedback(HapticAction.LIST_ITEM)
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        detailContent?.invoke()
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 130.dp)) }
        Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun InterfaceSwitchRow(
    @StringRes title: Int,
    checked: Boolean,
    tag: String,
    @StringRes description: Int? = null,
    icon: ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    val haptics = rememberAppHapticFeedback(HapticAction.SWITCH)
    fun change(value: Boolean) { haptics.performHapticFeedback(HapticFeedbackType.ToggleOn); onChange(value) }
    Row(
        Modifier.fillMaxWidth().testTag("ui_$tag").clickable(role = Role.Switch) { change(!checked) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, ::change)
    }
}

@Composable
internal fun InterfaceSliderRow(
    @StringRes title: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String = stringResource(R.string.ui_percent, (value * 100).toInt()),
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    val label = stringResource(title)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(draft, { draft = it }, valueRange = range, steps = steps,
            onValueChangeFinished = { onChange(draft) }, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
internal fun InterfaceNumberRow(
    @StringRes title: Int,
    value: Int,
    range: IntRange,
    @StringRes unit: Int? = null,
    emptyUntilEdited: Boolean = false,
    onChange: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf(if (emptyUntilEdited) "" else value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused, emptyUntilEdited) { if (!focused) draft = if (emptyUntilEdited) "" else value.toString() }
    fun commit() {
        if (emptyUntilEdited && draft.isBlank()) return
        val parsed = draft.toIntOrNull()?.coerceIn(range) ?: value
        draft = parsed.toString()
        onChange(parsed)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(value = draft, onValueChange = { text ->
            draft = text.filter(Char::isDigit).take(7)
        }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }), singleLine = true,
            modifier = Modifier.width(88.dp).onFocusChanged { state ->
                if (focused && !state.isFocused) commit()
                focused = state.isFocused
            }, textStyle = MaterialTheme.typography.bodyMedium)
        unit?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun InterfaceFooter(@StringRes text: Int, vararg formatArgs: Any) = Text(stringResource(text, *formatArgs),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
