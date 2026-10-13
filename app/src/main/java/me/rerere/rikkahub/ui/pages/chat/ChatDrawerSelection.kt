package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.drawerTitleMatchesSearchQuery
import kotlin.uuid.Uuid
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Selection applies to every matching row, including pages not yet rendered. */
internal fun drawerSelectableConversations(
    conversations: List<Conversation>, query: String, allFolders: Boolean, folderId: Uuid?,
): List<Conversation> = conversations.filter {
    (allFolders || it.folderId == folderId) &&
        drawerTitleMatchesSearchQuery(it.title, query)
}

internal fun drawerToggleAllSelection(
    selected: Map<Uuid, Conversation>, visible: List<Conversation>,
): Map<Uuid, Conversation> = if (visible.isNotEmpty() && visible.all { it.id in selected }) {
    selected - visible.map { it.id }.toSet()
} else {
    selected + visible.associateBy { it.id }
}

/** Shared production controls: deterministic inputs also support screenshot and click fixtures. */
@Composable
internal fun ChatDrawerSelectionHeader(
    selectedCount: Int,
    allSelected: Boolean,
    onCancel: () -> Unit,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat_drawer_selection_header"),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
            Icon(HugeIcons.Cancel01, stringResource(R.string.moru_drawer_exit_selection), Modifier.size(22.dp))
        }
        Text(stringResource(R.string.moru_drawer_selected_count, selectedCount), Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .semantics { selected = allSelected }.clickable(role = Role.Checkbox, onClick = onToggleSelectAll)
            .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(
                if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center) {
                if (allSelected) Icon(HugeIcons.Tick01, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Text(stringResource(if (allSelected) R.string.moru_drawer_deselect_all else R.string.moru_drawer_select_all),
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun ChatDrawerSelectionActionBar(
    selectedCount: Int,
    allSelectedPinned: Boolean,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().testTag("chat_drawer_selection_actions"),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), color = colors.surface,
        shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionAction(if (allSelectedPinned) HugeIcons.PinOff else HugeIcons.Pin,
                stringResource(if (allSelectedPinned) R.string.moru_drawer_unpin else R.string.moru_drawer_pin), colors.onSurface, selectedCount > 0, onPin, Modifier.weight(1f))
            SelectionAction(HugeIcons.Forward02, stringResource(R.string.moru_drawer_move_selected),
                colors.primary, selectedCount > 0, onMove, Modifier.weight(1f))
            SelectionAction(HugeIcons.Delete01, stringResource(R.string.chat_page_delete),
                colors.error, selectedCount > 0, onDelete, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, color: Color, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .background(color.copy(alpha = .12f)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        SelectionActionScaleDown {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(16.dp), tint = color.copy(alpha = if (enabled) 1f else .4f))
                Text(label, Modifier.padding(start = 4.dp), maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = color.copy(alpha = if (enabled) 1f else .4f))
            }
        }
    }
}

/** Moru's FittedBox(scaleDown): fit the complete icon and label, retaining the parent's hit area. */
@Composable
private fun SelectionActionScaleDown(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val row = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0,
            maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else row.width
        val widthScale = width.toFloat() / row.width.coerceAtLeast(1)
        val heightScale = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() / row.height.coerceAtLeast(1) else 1f
        val scale = minOf(1f, widthScale, heightScale)
        val height = constraints.constrainHeight(ceil(row.height * scale).toInt())
        layout(width, height) {
            row.placeWithLayer(((width - row.width * scale) / 2f).roundToInt(),
                ((height - row.height * scale) / 2f).roundToInt()) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
