package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatBackgroundRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletedWallpaperLeavesTheThemeBackgroundIntact() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(100.dp).background(Color.Magenta).testTag("chat_background_fallback")) {
                    AssistantBackground(Settings(), assistant = Assistant(background = "/missing-moru-wallpaper.png"))
                }
            }
        }
        compose.onNodeWithTag("chat_background_wallpaper").assertDoesNotExist()
        compose.onNodeWithTag("chat_background_gradient").assertDoesNotExist()
        val pixels = compose.onNodeWithTag("chat_background_fallback").captureToImage().toPixelMap()
        assertEquals(Color.Magenta, pixels[50, 50])
    }

    @Test fun disabledGlobalWallpaperDoesNotLoadItsRememberedPath() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(100.dp).background(Color.Magenta).testTag("chat_background_fallback")) {
                    AssistantBackground(
                        Settings(displaySetting = DisplaySetting(chatBackgroundImagePath = "https://example.com/image.png")),
                        assistant = Assistant(),
                    )
                }
            }
        }
        compose.onNodeWithTag("chat_background_wallpaper").assertDoesNotExist()
        val pixels = compose.onNodeWithTag("chat_background_fallback").captureToImage().toPixelMap()
        assertEquals(Color.Magenta, pixels[50, 50])
    }
}
