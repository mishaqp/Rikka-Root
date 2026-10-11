package me.rerere.rikkahub.ui.screenshot

import android.content.res.TypedArray
import dynamiccolor.ColorSpec
import hct.Hct
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import scheme.SchemeTonalSpot

/** Freezes Android 34+ wallpaper color resources while retaining Material3's real dynamic API. */
@Implements(TypedArray::class)
class FixedSeedDynamicColors {
    @RealObject private lateinit var typedArray: TypedArray

    @Implementation(minSdk = 34)
    fun getColor(index: Int, defaultValue: Int): Int {
        val resourceId = typedArray.getResourceId(index, 0)
        if (resourceId != 0 && typedArray.resources.getResourcePackageName(resourceId) == "android") {
            val name = typedArray.resources.getResourceEntryName(resourceId)
            colors[name]?.let { color ->
                observedResources += name
                return color
            }
        }
        return Shadow.directlyOn(
            typedArray, TypedArray::class.java, "getColor",
            ClassParameter.from(Int::class.javaPrimitiveType!!, index),
            ClassParameter.from(Int::class.javaPrimitiveType!!, defaultValue),
        )
    }

    companion object {
        const val SEED_ARGB: Long = 0xFF6750A4
        val observedResources = mutableSetOf<String>()

        @JvmStatic
        @Resetter
        fun reset() { observedResources.clear() }

        private val colors: Map<String, Int> by lazy {
            buildMap {
                for (dark in listOf(false, true)) {
                    // Android 15 wallpaper roles use the original tonal-spot specification.
                    val scheme = SchemeTonalSpot(
                        Hct.fromInt(SEED_ARGB.toInt()), dark, 0.0, ColorSpec.SpecVersion.SPEC_2021,
                    )
                    val suffix = if (dark) "dark" else "light"
                    val roles = mapOf(
                        "primary" to scheme.primary, "on_primary" to scheme.onPrimary,
                        "primary_container" to scheme.primaryContainer, "on_primary_container" to scheme.onPrimaryContainer,
                        "secondary" to scheme.secondary, "on_secondary" to scheme.onSecondary,
                        "secondary_container" to scheme.secondaryContainer, "on_secondary_container" to scheme.onSecondaryContainer,
                        "tertiary" to scheme.tertiary, "on_tertiary" to scheme.onTertiary,
                        "tertiary_container" to scheme.tertiaryContainer, "on_tertiary_container" to scheme.onTertiaryContainer,
                        "background" to scheme.background, "on_background" to scheme.onBackground,
                        "surface" to scheme.surface, "on_surface" to scheme.onSurface,
                        "surface_variant" to scheme.surfaceVariant, "on_surface_variant" to scheme.onSurfaceVariant,
                        "outline" to scheme.outline, "outline_variant" to scheme.outlineVariant,
                        "surface_bright" to scheme.surfaceBright, "surface_dim" to scheme.surfaceDim,
                        "surface_container" to scheme.surfaceContainer,
                        "surface_container_high" to scheme.surfaceContainerHigh,
                        "surface_container_highest" to scheme.surfaceContainerHighest,
                        "surface_container_low" to scheme.surfaceContainerLow,
                        "surface_container_lowest" to scheme.surfaceContainerLowest,
                    )
                    roles.forEach { (role, color) -> put("system_${role}_$suffix", color) }
                    put("system_primary_fixed", scheme.primaryFixed)
                    put("system_primary_fixed_dim", scheme.primaryFixedDim)
                    put("system_on_primary_fixed", scheme.onPrimaryFixed)
                    put("system_on_primary_fixed_variant", scheme.onPrimaryFixedVariant)
                    put("system_secondary_fixed", scheme.secondaryFixed)
                    put("system_secondary_fixed_dim", scheme.secondaryFixedDim)
                    put("system_on_secondary_fixed", scheme.onSecondaryFixed)
                    put("system_on_secondary_fixed_variant", scheme.onSecondaryFixedVariant)
                    put("system_tertiary_fixed", scheme.tertiaryFixed)
                    put("system_tertiary_fixed_dim", scheme.tertiaryFixedDim)
                    put("system_on_tertiary_fixed", scheme.onTertiaryFixed)
                    put("system_on_tertiary_fixed_variant", scheme.onTertiaryFixedVariant)
                }
            }
        }
    }
}
