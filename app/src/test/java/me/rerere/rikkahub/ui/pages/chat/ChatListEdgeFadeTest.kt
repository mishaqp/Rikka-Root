package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListEdgeFadeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun masksRevealWallpaperWithoutClearingOverlayControlsOrMiddleContent() {
        compose.setContent {
            Box(Modifier.requiredSize(100.dp, 600.dp).background(Color.Blue).testTag("wallpaper")) {
                Box(Modifier.size(60.dp, 600.dp).chatListEdgeFade(78.dp, 80.dp).background(Color.Red))
                Box(Modifier.align(Alignment.TopEnd).size(40.dp).background(Color.Green))
            }
        }
        val pixels = compose.onNodeWithTag("wallpaper").captureToImage().toPixelMap()
        fun color(y: Int) = pixels[30, y]
        assertEquals("Wallpaper remains visible beneath the transparent header", 1f, color(30).blue, .02f)
        assertEquals(0f, color(30).red, .02f)
        assertTrue("A quarter of the top fade remains mostly wallpaper", color(84).red in .22f.. .32f)
        assertTrue("The top fade midpoint blends list and wallpaper", color(90).red in .47f.. .57f)
        assertTrue("Three quarters of the top fade retains mostly list content", color(96).red in .72f.. .82f)
        (77..102).zipWithNext().forEach { (previous, next) ->
            val delta = color(next).red - color(previous).red
            assertTrue("The header edge fades continuously rather than sharply clipping at y=$next", delta in 0f.. .08f)
        }
        assertEquals("The middle keeps the original list colour", 1f, color(180).red, .02f)
        assertEquals(0f, color(180).blue, .02f)
        assertEquals("The last visible content keeps its full contrast above the composer", 1f, color(490).red, .02f)
        val soft = color(560)
        assertTrue("The bottom fade mixes content and wallpaper underneath the composer", soft.red in .4f.. .6f && soft.blue in .4f.. .6f)
        assertEquals("The bottom of the composer exposes the same wallpaper", 1f, color(599).blue, .02f)
        assertEquals("Offscreen compositing must not mask sibling controls", Color.Green, pixels[80, 20])
    }

    @Test fun fadeGeometryTracksHeaderAndImeComposerInsets() {
        val regular = chatListEdgeFadeGeometry(800f, 102f, 120f, 24f, 180f)
        assertEquals(0f, regular.alphaAt(102f), .001f)
        assertEquals(.5f, regular.alphaAt(114f), .001f)
        assertEquals(1f, regular.alphaAt(300f), .001f)
        assertEquals(1f, regular.alphaAt(648f), .001f) // last actions, 32dp above input
        assertEquals(1f, regular.alphaAt(680f), .001f) // input top
        assertEquals(.5f, regular.alphaAt(740f), .001f)
        assertEquals(0f, regular.alphaAt(800f), .001f)
        val expanded = chatListEdgeFadeGeometry(800f, 102f, 240f, 24f, 180f)
        assertEquals(1f, expanded.alphaAt(528f), .001f)
        val ime = chatListEdgeFadeGeometry(800f, 102f, 400f, 24f, 180f)
        assertEquals(1f, ime.alphaAt(368f), .001f)
        assertEquals(1f, ime.alphaAt(400f), .001f)
        assertEquals(.5f, ime.alphaAt(710f), .001f)
        assertEquals(0f, ime.alphaAt(800f), .001f)
    }

    @Test fun shortViewportAndInvalidInsetsKeepMaskFiniteAndBounded() {
        val mask = chatListEdgeFadeGeometry(100f, 900f, -10f, 0f, 0f)
        (0..100).forEach { y ->
            val alpha = mask.alphaAt(y.toFloat())
            assertTrue(alpha.isFinite() && alpha in 0f..1f)
        }
    }
}
