package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import java.io.File

internal data class ChatBackgroundPresentation(
    val image: String?,
    val imageOpacity: Float,
    val topMaskAlpha: Float,
    val bottomMaskAlpha: Float,
    val shadowAlpha: Float,
    val gradient: Boolean,
)

/** Assistant artwork retains priority; global artwork is explicitly opt-in. */
internal fun resolveChatBackground(
    assistant: Assistant,
    display: DisplaySetting,
    bigScreen: Boolean = false,
): ChatBackgroundPresentation {
    val image = assistant.background?.takeIf { it.isNotBlank() }
        ?: display.chatBackgroundImagePath.takeIf { display.chatBackgroundEnabled && it.isNotBlank() }
    val strength = display.chatBackgroundMaskStrength.coerceIn(0f, 2f)
    return ChatBackgroundPresentation(
        image = image,
        imageOpacity = if (!assistant.background.isNullOrBlank()) assistant.backgroundOpacity.coerceIn(0f, 1f) else 1f,
        topMaskAlpha = ((if (bigScreen) .08f else .20f) * strength).coerceIn(0f, 1f),
        bottomMaskAlpha = ((if (bigScreen) .36f else .50f) * strength).coerceIn(0f, 1f),
        shadowAlpha = (.04f * strength).coerceIn(0f, 1f),
        gradient = assistant.useGradientBackground || (display.glassTheme && image == null),
    )
}

@Composable
fun AssistantBackground(
    setting: Settings,
    modifier: Modifier = Modifier,
    assistant: Assistant = setting.getCurrentAssistant(),
    bigScreen: Boolean = false,
) {
    val presentation = remember(assistant.background, assistant.backgroundOpacity, assistant.useGradientBackground, setting.displaySetting, bigScreen) {
        resolveChatBackground(assistant, setting.displaySetting, bigScreen)
    }
    if (presentation.gradient) {
        MeshGradientBackground(modifier = modifier.testTag("chat_background_gradient"))
        return
    }
    val image = presentation.image ?: return
    // A missing/deleted local file keeps the ordinary theme background and never breaks the chat.
    val available = remember(image) {
        when {
            image.startsWith("file://") -> File(android.net.Uri.parse(image).path.orEmpty()).isFile
            image.startsWith("/") -> File(image).isFile
            else -> true // HTTP, SAF and Android resource URIs are resolved by Coil.
        }
    }
    if (!available) return
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxSize().testTag("chat_background_wallpaper")) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(presentation.imageOpacity),
        )
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = presentation.shadowAlpha)))
        Box(
            Modifier.fillMaxSize().testTag("chat_background_mask").background(
                Brush.verticalGradient(
                    colors = listOf(
                        surface.copy(alpha = presentation.topMaskAlpha),
                        surface.copy(alpha = presentation.bottomMaskAlpha),
                    ),
                ),
            ),
        )
    }
}
