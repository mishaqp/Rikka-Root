package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.utils.urlDecode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMessageCitations(annotations: List<UIMessageAnnotation>) {
    val citations = remember(annotations) { annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>() }
    if (citations.isEmpty()) return
    var showSources by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f

    Surface(
        onClick = { showSources = true },
        modifier = Modifier.testTag("chat_message_sources"),
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        border = BorderStroke(0.8.dp, colors.onSurface.copy(alpha = if (isDark) 0.16f else 0.10f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).heightIn(min = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceFaviconStack(citations)
            Text(
                text = stringResource(R.string.chat_message_sources_count, citations.size),
                color = colors.onSurface.copy(alpha = if (isDark) 0.90f else 0.86f),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }

    if (showSources) {
        ModalBottomSheet(
            onDismissRequest = { showSources = false },
            modifier = Modifier.testTag("chat_message_sources_sheet"),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_message_sources_title),
                    modifier = Modifier.testTag("chat_message_sources_title"),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (citations.size > 1) {
                    Text(
                        text = citations.size.toString(),
                        color = colors.onSurface.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Box(Modifier.weight(1f))
                IconButton(onClick = { showSources = false }) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.chat_message_sources_close),
                        modifier = Modifier.size(20.dp),
                        tint = colors.onSurface.copy(alpha = 0.62f),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(citations) { index, annotation ->
                    CitationSourceCard(
                        annotation = annotation,
                        index = index + 1,
                        onOpen = { uriHandler.openUri(annotation.url) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceFaviconStack(citations: List<UIMessageAnnotation.UrlCitation>) {
    val urls = remember(citations) {
        citations.map { it.url }
            .filter { it.toHttpUrlOrNull()?.host?.isNotEmpty() == true }
            .distinctBy { it.toHttpUrlOrNull()?.host }
            .take(3)
    }
    if (urls.isEmpty()) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = HugeIcons.Link01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                modifier = Modifier.size(13.dp),
            )
        }
        return
    }
    Layout(
        content = {
            urls.forEachIndexed { index, url ->
                Box(Modifier.size(18.dp).zIndex(index.toFloat()), contentAlignment = Alignment.Center) {
                    Favicon(url, modifier = Modifier.size(16.dp), shape = CircleShape)
                }
            }
        },
    ) { measurables, constraints ->
        val slot = 18.dp.roundToPx()
        val step = 11.dp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(slot, slot)) }
        layout(constraints.constrainWidth(slot + (placeables.size - 1) * step), constraints.constrainHeight(slot)) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(index * step, 0) }
        }
    }
}

@Composable
private fun CitationSourceCard(annotation: UIMessageAnnotation.UrlCitation, index: Int, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val domain = remember(annotation.url) { annotation.url.toHttpUrlOrNull()?.host.orEmpty() }
    val title = remember(annotation.title, domain, annotation.url) {
        val decoded = annotation.title.urlDecode().trim()
        if (decoded.isNotEmpty() && !decoded.all(Char::isDigit)) decoded else domain.ifEmpty { annotation.url }
    }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("chat_message_source_card_$index"),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(start = 8.dp, top = 20.dp, end = 4.dp, bottom = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Favicon(annotation.url, modifier = Modifier.size(14.dp), shape = CircleShape)
                Text(
                    text = domain,
                    modifier = Modifier.weight(1f),
                    color = colors.onSurface.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = colors.primaryContainer,
                    border = BorderStroke(0.5.dp, colors.primary.copy(alpha = 0.22f)),
                ) {
                    Box(
                        Modifier.sizeIn(minWidth = 18.dp, minHeight = 18.dp)
                            .padding(horizontal = if (index >= 10) 5.dp else 0.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = index.toString(),
                            color = colors.primary,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                lineHeight = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }
            Text(
                text = title,
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.48.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
