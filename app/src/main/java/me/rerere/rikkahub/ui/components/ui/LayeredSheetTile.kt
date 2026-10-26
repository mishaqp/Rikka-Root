package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import me.rerere.rikkahub.ui.theme.sheetTileContainer

/** Shared production card for selectable bottom-sheet rows. */
@Composable
internal fun LayeredSheetTile(
    selected: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = CardDefaults.cardColors(
        containerColor = if (selected) scheme.primaryContainer else scheme.sheetTileContainer,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
    )
    if (onClick == null) {
        Card(modifier = modifier, shape = shape, colors = colors, content = content)
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            content = content,
        )
    }
}

