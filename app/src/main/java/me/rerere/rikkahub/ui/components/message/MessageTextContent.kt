package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownDisplayOptions

/** The plain branch preserves source characters, links and fences as visible message text. */
@Composable
internal fun MessageTextContent(
    text: String,
    markdown: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    displayOptions: MarkdownDisplayOptions? = null,
    onClickCitation: (String) -> Unit = {},
) {
    if (markdown) {
        MarkdownBlock(content = text, modifier = modifier, style = style,
            onClickCitation = onClickCitation, displayOptions = displayOptions)
    } else {
        Text(text = text, style = style, modifier = modifier.padding(horizontal = 4.dp)
            .testTag("chat_message_plain_text"))
    }
}
