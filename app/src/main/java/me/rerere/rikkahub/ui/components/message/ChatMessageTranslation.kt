package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.currentMarkdownDisplayOptions
import java.util.Locale

@Composable
fun LanguageSelectionDialog(
    onLanguageSelected: (Locale) -> Unit,
    onClearTranslation: () -> Unit = {},
    onDismissRequest: () -> Unit
) {
    // 支持的语言列表
    val languages = remember {
        listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.ENGLISH,
            Locale.TRADITIONAL_CHINESE,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.FRENCH,
            Locale.GERMAN,
            Locale("es", "ES"),
            Locale.ITALIAN,
        )
    }

    // 语言名称映射函数，原有的 locale.displayName 方法无法获取 emoji
    @Composable
    fun getLanguageDisplayName(locale: Locale): String {
        return when (locale) {
            Locale.SIMPLIFIED_CHINESE -> stringResource(R.string.language_simplified_chinese)
            Locale.ENGLISH -> stringResource(R.string.language_english)
            Locale.TRADITIONAL_CHINESE -> stringResource(R.string.language_traditional_chinese)
            Locale.JAPANESE -> stringResource(R.string.language_japanese)
            Locale.KOREAN -> stringResource(R.string.language_korean)
            Locale.FRENCH -> stringResource(R.string.language_french)
            Locale.GERMAN -> stringResource(R.string.language_german)
            Locale.ITALIAN -> stringResource(R.string.language_italian)
            Locale("es", "ES") -> stringResource(R.string.language_spanish)
            else -> locale.getDisplayLanguage(Locale.getDefault())
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题
            Text(
                text = stringResource(R.string.translation_language_selection_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth()
            )

            // 语言列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(languages) { language ->
                    Card(
                        onClick = {
                            onLanguageSelected(language)
                        },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = HugeIcons.LanguageCircle,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = getLanguageDisplayName(language),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }

                item {
                    Card(
                        onClick = {
                            onClearTranslation()
                        },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = null,
                            )
                            Text(
                                text = stringResource(R.string.translation_clear),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CollapsibleTranslationText(
    content: String,
    onClickCitation: (String) -> Unit
) {
    if (content.isNotBlank()) {
        var isCollapsed by remember { mutableStateOf(false) }

        val toggleDescription = stringResource(
            if (isCollapsed) R.string.expand_translation else R.string.collapse_translation
        )
        val fontSizeRatio = currentMarkdownDisplayOptions().fontSizeRatio
        val translationStyle = LocalTextStyle.current.copy(
            fontSize = 15.5.sp * fontSizeRatio,
            lineHeight = 21.7.sp * fontSizeRatio,
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("chat_message_translation"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_message_translation_toggle")
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = toggleDescription,
                            onClick = { isCollapsed = !isCollapsed },
                        )
                        .semantics(mergeDescendants = true) { contentDescription = toggleDescription }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.LanguageCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.translation_text),
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = if (isCollapsed) HugeIcons.ArrowRight01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }

                AnimatedVisibility(
                    visible = !isCollapsed,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(Modifier.padding(top = 8.dp)) {
                        val isTranslating = content == stringResource(R.string.translating)
                        val bodyModifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 6.dp)

                        if (isTranslating) {
                            Row(
                                modifier = bodyModifier.testTag("chat_message_translation_loading"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )

                                val infiniteTransition = rememberInfiniteTransition(label = "loading")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 0.3f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse,
                                    ),
                                    label = "alpha",
                                )

                                Text(
                                    text = stringResource(R.string.translating),
                                    style = translationStyle.copy(fontStyle = FontStyle.Italic),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.graphicsLayer(alpha = alpha),
                                )
                            }
                        } else {
                            MarkdownBlock(
                                content = content,
                                style = translationStyle,
                                onClickCitation = onClickCitation,
                                horizontalPadding = 0.dp,
                                modifier = bodyModifier.testTag("chat_message_translation_content").animateContentSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
