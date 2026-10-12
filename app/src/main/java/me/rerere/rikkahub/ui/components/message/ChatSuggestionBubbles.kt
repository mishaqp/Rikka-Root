package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/** Moru's continuation bubbles under the last answer: wrapped, 16dp corners, up to four lines each. */
@Composable
fun ChatSuggestionBubbles(
    suggestions: List<String>,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = suggestions.map { it.trim() }.filter { it.isNotEmpty() }
    if (visible.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkMode.current
    val container = if (dark) colors.onSurface.copy(alpha = 0.08f) else colors.primaryContainer.copy(alpha = 0.42f)
    val content = colors.onSurface.copy(alpha = if (dark) 0.92f else 0.88f)
    // Bubbles are ~32dp tall as in Moru; the 48dp minimum would double the 8dp gaps between rows.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FlowRow(
            modifier = modifier.testTag("chat_suggestions"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visible.forEach { suggestion ->
                Surface(
                    onClick = { onClick(suggestion) },
                    shape = RoundedCornerShape(16.dp),
                    color = container,
                    contentColor = content,
                ) {
                    Text(
                        text = suggestion,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 15.6.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
