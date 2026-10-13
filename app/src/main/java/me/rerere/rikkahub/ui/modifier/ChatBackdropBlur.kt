package me.rerere.rikkahub.ui.modifier

import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

/** Captures the supplied backdrop; clipping and the surface tint belong to the caller. */
fun Modifier.chatBackdropBlur(
    state: HazeState?,
    enabled: Boolean,
    blurSigma: Float = 14f,
): Modifier = if (enabled && state != null && blurSigma > 0f) {
    hazeBlur(
        input = HazeInput.Sources(state),
        style = HazeBlurStyle {
            blurRadius(blurSigma.coerceIn(0f, 30f).dp)
            backgroundColor(Color.Transparent)
            colorEffects(emptyList())
            noiseFactor(0f)
        },
    )
} else this

/** The legacy GLASS choice uses refraction from the same captured backdrop. */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.chatBackdropGlass(state: HazeState?, enabled: Boolean, cornerRadius: Float = 20f): Modifier =
    if (enabled && state != null) {
        hazeGlass(input = HazeInput.Sources(state), style = GlassStyle.clear.then {
            shape(RoundedCornerShape(cornerRadius.coerceAtLeast(0f).dp))
            backgroundColor(Color.Transparent)
        })
    } else this
