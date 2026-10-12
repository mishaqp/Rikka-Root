package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.ui.table.DataTable

/** Shared chrome for native Markdown and HTML tables; cell renderers retain links and math. */
@Composable
internal fun MarkdownTableBlock(
    headerCells: List<String>,
    rows: List<List<String>>,
    headers: List<@Composable () -> Unit>,
    rowContent: List<List<@Composable () -> Unit>>,
    modifier: Modifier = Modifier,
    tableMarkdown: String = buildMarkdownTableSource(headerCells, rows),
) {
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val csv = remember(headerCells, rows) { buildMarkdownTableCsv(headerCells, rows) }
    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(csv.toByteArray(Charsets.UTF_8))
                    }
                }.onFailure { it.printStackTrace() }
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val inheritedTextStyle = LocalTextStyle.current
    val fontSizeRatio = currentMarkdownDisplayOptions().fontSizeRatio
    val darkMode = LocalDarkMode.current
    val borderColor = colors.outlineVariant.copy(alpha = if (darkMode) 0.22f else 0.30f)
    // Moru's primary tints are composited onto an opaque theme surface.
    val headerColor = colors.primary.copy(alpha = if (darkMode) 0.15f else 0.07f).compositeOver(colors.surface)
    val bodyColor = colors.primary.copy(alpha = if (darkMode) 0.045f else 0.018f).compositeOver(colors.surface)
    Column(
        modifier = modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth()
            .testTag("markdown_table")
            .clip(shape)
            .border(BorderStroke(0.8.dp, borderColor), shape)
            .background(bodyColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(headerColor)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_message_table_label),
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = stringResource(R.string.chat_message_table_copy),
                tint = colors.onSurfaceVariant.copy(alpha = 0.68f),
                modifier = Modifier
                    .testTag("markdown_table_copy")
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        scope.launch {
                            clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("table", tableMarkdown)))
                        }
                    }
                    .padding(8.dp)
                    .size(16.dp),
            )
            Icon(
                imageVector = HugeIcons.Download04,
                contentDescription = stringResource(R.string.chat_message_table_csv),
                tint = colors.onSurfaceVariant.copy(alpha = 0.68f),
                modifier = Modifier
                    .testTag("markdown_table_csv")
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { createDocumentLauncher.launch("Таблица.csv") }
                    .padding(8.dp)
                    .size(16.dp),
            )
        }
        DataTable(
            headers = headers.map { content ->
                @Composable {
                    ProvideTextStyle(inheritedTextStyle.copy(fontSize = 13.sp * fontSizeRatio, lineHeight = 18.46.sp * fontSizeRatio, fontWeight = FontWeight.SemiBold, color = colors.onSurface)) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) { content() }
                    }
                }
            },
            rows = rowContent.map { row -> row.map { content ->
                @Composable {
                    ProvideTextStyle(inheritedTextStyle.copy(fontSize = 13.5.sp * fontSizeRatio, lineHeight = 19.17.sp * fontSizeRatio, fontWeight = FontWeight.Normal, color = colors.onSurface.copy(alpha = 0.9f))) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) { content() }
                    }
                }
            } },
            cellPadding = 0.dp,
            cellBorder = BorderStroke(0.5.dp, borderColor),
            headerBackground = headerColor,
            columnMinWidths = List(headers.size) { 112.dp },
            columnMaxWidths = List(headers.size) { 178.dp },
            outerBorder = null,
            shape = RectangleShape,
        )
    }
}

internal fun buildMarkdownTableCsv(headerCells: List<String>, rows: List<List<String>>): String {
    fun escape(field: String): String = if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${field.replace("\"", "\"\"")}\""
    } else field
    return (listOf(headerCells) + rows).joinToString("\n") { row -> row.joinToString(",", transform = ::escape) }
}

internal fun buildMarkdownTableSource(headerCells: List<String>, rows: List<List<String>>): String {
    fun row(cells: List<String>) = "| " + cells.joinToString(" | ") { it.replace("|", "\\|").replace("\n", "<br>") } + " |"
    return (listOf(row(headerCells), row(List(headerCells.size) { "---" })) + rows.map(::row)).joinToString("\n")
}
