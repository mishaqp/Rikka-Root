package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01

/** Shared accessible row used by the dashboard and every search result. */
@Composable
fun SettingsItem(
    title: String,
    description: String,
    icon: ImageVector,
    tone: SettingsSectionTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: SettingsDashboardStatus? = null,
    query: String = "",
) {
    val chip = (status as? SettingsDashboardStatus.Ready)?.chip
    val statusText = when (status) {
        SettingsDashboardStatus.Loading -> "Статус загружается"
        is SettingsDashboardStatus.Error -> "Статус недоступен"
        else -> chip?.text
    }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(title, description, statusText).joinToString(". ")
                role = Role.Button
                onClick(label = "Открыть: $title") { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconCircle(icon, tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(settingsHighlightedText(title, query), style = MaterialTheme.typography.titleMedium)
            SettingsStatusChip(status)
            Text(settingsHighlightedText(description, query), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun SettingsIconCircle(
    icon: ImageVector,
    tone: SettingsSectionTone,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    val colors = settingsToneColors(tone)
    Box(Modifier.size(size).background(colors.first, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = colors.second, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun SettingsStatusChip(status: SettingsDashboardStatus?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (status) {
        SettingsDashboardStatus.Loading -> Text("…", modifier.background(scheme.surfaceContainerHighest,
            RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        is SettingsDashboardStatus.Ready -> status.chip?.let { SettingsStatusValueChip(it, modifier) }
        is SettingsDashboardStatus.Error -> SettingsStatusValueChip(
            SettingsDashboardStatusChip("Недоступно", SettingsDashboardStatusTone.Error), modifier,
        )
        null -> Unit
    }
}

@Composable
private fun SettingsStatusValueChip(chip: SettingsDashboardStatusChip, modifier: Modifier) {
    val colors = settingsStatusColors(chip.tone)
    Surface(modifier, color = colors.first, contentColor = colors.second, shape = RoundedCornerShape(8.dp)) {
        Text(chip.text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun settingsHighlightedText(text: String, query: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    return buildAnnotatedString {
        append(text)
        query.trim().split(Regex("\\s+")).filter(String::isNotBlank).distinct().forEach { word ->
            Regex(Regex.escape(word), RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
                addStyle(SpanStyle(background = color, color = foreground), match.range.first, match.range.last + 1)
            }
        }
    }
}
