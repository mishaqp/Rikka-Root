package me.rerere.rikkahub.ui.components.message

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageSurfaceTest {
    private val colors = lightColorScheme(primary = Color(0xff0066cc), background = Color.White,
        surfaceContainerHigh = Color(0xffd0d0d0))

    @Test fun userTintIsAnOpaqueMoruWashBeforeSavedOpacity() {
        listOf(false, true).forEach { dark ->
            val expected = colors.primary.copy(alpha = if (dark) .15f else .08f).compositeOver(colors.background)
            val full = messageSurfaceColor(MessageTextSurface.USER, colors, dark, 1f)
            assertEquals(expected, full)
            assertEquals(1f, full.alpha, .001f)
            val translucent = messageSurfaceColor(MessageTextSurface.USER, colors, dark, .5f)
            assertEquals(full.red, translucent.red, .001f)
            assertEquals(full.green, translucent.green, .001f)
            assertEquals(full.blue, translucent.blue, .001f)
            assertEquals(.5f, translucent.alpha, .003f)
        }
    }

    @Test fun assistantUsesMaterialSurfaceAndSameExplicitOpacityPreference() {
        assertEquals(colors.surfaceContainerHigh, messageSurfaceColor(MessageTextSurface.ASSISTANT, colors, false, 1f))
        assertEquals(.5f, messageSurfaceColor(MessageTextSurface.ASSISTANT, colors, false, .5f).alpha, .003f)
        assertEquals(Color.Transparent, messageSurfaceColor(MessageTextSurface.NONE, colors, false, 1f))
    }
}
