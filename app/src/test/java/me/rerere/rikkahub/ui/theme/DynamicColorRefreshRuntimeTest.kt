package me.rerere.rikkahub.ui.theme

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.TypedArray
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Changes platform resource values, never the production palette resolver or Compose theme. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35],
    qualifiers = "ru-rRU-w411dp-h891dp-mdpi", shadows = [MutableSystemThemeColors::class])
class DynamicColorRefreshRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val owner = ThemeLifecycleOwner()
    private val configuration = mutableStateOf(Configuration().apply {
        uiMode = Configuration.UI_MODE_NIGHT_NO
        orientation = Configuration.ORIENTATION_PORTRAIT
    })
    private val initial = Settings(dynamicColor = true, displaySetting = DisplaySetting(
        usePureBackground = false, useLayeredSurfaces = false, useLayeredSheetTiles = false,
    ))
    private val settings = MutableStateFlow(initial)
    private val showTheme = mutableStateOf(true)
    private var rendered: PaletteRoles? = null

    @Before fun installSettingsBoundary() {
        MutableSystemThemeColors.reset()
        application.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE).edit().clear().commit()
        // Only persistence is replaced. Unexpected writes fail on this strict mock.
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns settings
        stopKoin()
        startKoin { modules(module { single { store } }) }
    }

    @After fun closeSettingsBoundary() { stopKoin() }

    @Test fun forcedLightThemeRefreshesSystemPaletteOnResume() = verifyResume(ColorMode.LIGHT)

    @Test fun forcedDarkThemeRefreshesSystemPaletteOnResume() = verifyResume(ColorMode.DARK)

    @Test fun systemModeRefreshesPaletteWithoutChangingNightMode() = verifyResume(ColorMode.SYSTEM)

    @Test fun colorEditorRefreshesSystemPaletteOnResume() = verifyResume(ColorMode.LIGHT, editor = true)

    @Test fun visiblePausedThemeRefreshesWithoutAnOnStartEvent() =
        verifyResume(ColorMode.LIGHT, backgroundState = Lifecycle.State.STARTED)

    @Test fun dynamicToggleRetainsSelectedThemeAndUsesCurrentPlatformOnReenable() {
        mount(ColorMode.LIGHT)
        assertRendered(MutableSystemThemeColors.FIRST.light)
        val selected = PresetThemes.last()
        compose.runOnIdle { settings.value = settings.value.copy(dynamicColor = false, themeId = selected.id) }
        compose.waitForIdle()
        assertScheme(selected.getColorScheme(false))
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            MutableSystemThemeColors.palette = MutableSystemThemeColors.SECOND
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertScheme(selected.getColorScheme(false))
        compose.runOnIdle { settings.value = settings.value.copy(dynamicColor = true) }
        compose.waitForIdle()
        assertRendered(MutableSystemThemeColors.SECOND.light)
        assertEquals(selected.id, settings.value.themeId)
        assertEquals(initial.displaySetting, settings.value.displaySetting)
    }

    @Test fun selectingAndEditingCustomThemesStillUpdatesTheRenderedPalette() {
        val first = CustomTheme(id = "first", name = "First", primaryColorArgb = 0xFF00695CL)
        val second = CustomTheme(id = "second", name = "Second", primaryColorArgb = 0xFF880E4FL)
        settings.value = initial.copy(dynamicColor = false, themeId = first.id, customThemes = listOf(first, second))
        mount(ColorMode.LIGHT)
        assertScheme(first.generateColorScheme(false))
        compose.runOnIdle { settings.value = settings.value.copy(themeId = second.id) }
        compose.waitForIdle()
        assertScheme(second.generateColorScheme(false))
        val edited = second.copy(primaryColorArgb = 0xFF283593L)
        compose.runOnIdle { settings.value = settings.value.copy(customThemes = listOf(first, edited)) }
        compose.waitForIdle()
        assertScheme(edited.generateColorScheme(false))
        assertEquals(listOf(first, edited), settings.value.customThemes)
    }

    @Test fun amoledKeepsBlackSurfacesWhileSystemAccentsRefresh() {
        val preferences = application.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("amoledDark", true).commit()
        mount(ColorMode.DARK)
        assertRendered(MutableSystemThemeColors.FIRST.dark.copy(surface = 0xFF000000.toInt()))
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            MutableSystemThemeColors.palette = MutableSystemThemeColors.SECOND
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertRendered(MutableSystemThemeColors.SECOND.dark.copy(surface = 0xFF000000.toInt()))
        assertEquals(initial, settings.value)
        assertEquals(mapOf("amoledDark" to true), preferences.all)
    }

    @Test fun systemModeUsesTheDarkPaletteAfterNightModeChanges() {
        mount(ColorMode.SYSTEM)
        assertRendered(MutableSystemThemeColors.FIRST.light)
        compose.runOnIdle {
            MutableSystemThemeColors.palette = MutableSystemThemeColors.SECOND
            configuration.value = Configuration(configuration.value).apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        }
        compose.waitForIdle()
        assertRendered(MutableSystemThemeColors.SECOND.dark)
    }

    @Test fun paletteLifecycleObserversAreRemovedWithTheirComposition() {
        val before = owner.registry.observerCount
        mount(ColorMode.LIGHT)
        compose.runOnIdle { assertTrue(owner.registry.observerCount > before) }
        compose.runOnIdle { showTheme.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Disposed themes must release lifecycle observers", before, owner.registry.observerCount) }
    }

    @Test @Config(sdk = [30])
    fun android11KeepsTheSelectedFallbackWhenDynamicColorsAreRequested() {
        val selected = PresetThemes.last()
        settings.value = initial.copy(themeId = selected.id)
        mount(ColorMode.LIGHT)
        assertScheme(selected.getColorScheme(false))
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            MutableSystemThemeColors.palette = MutableSystemThemeColors.SECOND
            configuration.value = Configuration(configuration.value).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertScheme(selected.getColorScheme(false))
        assertEquals(initial.copy(themeId = selected.id), settings.value)
    }

    @Test @Config(sdk = [31, 33])
    fun android12And13RefreshTheirTonalPaletteOnResume() {
        mount(ColorMode.LIGHT)
        compose.runOnIdle { assertEquals(MutableSystemThemeColors.FIRST.light.primary, rendered?.primary) }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            MutableSystemThemeColors.palette = MutableSystemThemeColors.SECOND
        }
        assertEquals(MutableSystemThemeColors.SECOND.light.primary, dynamicLightColorScheme(application).primary.toArgb())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(MutableSystemThemeColors.SECOND.light.primary, rendered?.primary) }
    }

    @Test fun configurationChangeRefreshesForcedLightPalette() {
        mount(ColorMode.LIGHT)
        assertRendered(MutableSystemThemeColors.FIRST.light)
        compose.runOnIdle {
            MutableSystemThemeColors.palette = MutableSystemThemeColors.SECOND
            configuration.value = Configuration(configuration.value).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
        }
        compose.waitForIdle()
        assertPlatform(MutableSystemThemeColors.SECOND.light, dark = false)
        assertRendered(MutableSystemThemeColors.SECOND.light)
        assertEquals("Palette changes must not rewrite user settings", initial, settings.value)
    }

    private fun verifyResume(mode: ColorMode, editor: Boolean = false, backgroundState: Lifecycle.State = Lifecycle.State.CREATED) {
        val dark = mode == ColorMode.DARK
        mount(mode, editor)
        val first = if (dark) MutableSystemThemeColors.FIRST.dark else MutableSystemThemeColors.FIRST.light
        val second = if (dark) MutableSystemThemeColors.SECOND.dark else MutableSystemThemeColors.SECOND.light
        assertRendered(first)
        compose.runOnIdle {
            owner.registry.currentState = backgroundState
            MutableSystemThemeColors.palette = MutableSystemThemeColors.SECOND
        }
        compose.waitForIdle()
        assertPlatform(second, dark)
        assertNotEquals("Fixture must contain two different platform palettes", first, second)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertRendered(second)
        assertEquals("Lifecycle must not rewrite user settings", initial, settings.value)
    }

    private fun mount(mode: ColorMode, editor: Boolean = false) {
        // Keep editor settings fixed while only platform resources and lifecycle change.
        val editorSettings = settings.value
        compose.setContent {
            if (showTheme.value) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner,
                LocalConfiguration provides configuration.value) {
                if (editor) {
                    val scheme = effectiveInterfaceColorScheme(editorSettings, dark = false)
                    MaterialTheme(colorScheme = scheme) { PaletteProbe() }
                } else {
                    RikkahubTheme(colorMode = mode) { PaletteProbe() }
                }
            }
            }
        }
        compose.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun PaletteProbe() {
        val colors = MaterialTheme.colorScheme
        val roles = PaletteRoles(colors.primary.toArgb(), colors.surface.toArgb(), colors.onSurface.toArgb())
        SideEffect { rendered = roles }
        Text("System palette", color = colors.onSurface)
    }

    private fun assertPlatform(expected: PaletteRoles, dark: Boolean) {
        val colors = if (dark) dynamicDarkColorScheme(application) else dynamicLightColorScheme(application)
        assertEquals("Real Material3 API must see the changed platform resources", expected,
            PaletteRoles(colors.primary.toArgb(), colors.surface.toArgb(), colors.onSurface.toArgb()))
    }

    private fun assertScheme(scheme: ColorScheme) = assertRendered(
        PaletteRoles(scheme.primary.toArgb(), scheme.surface.toArgb(), scheme.onSurface.toArgb()))

    private fun assertRendered(expected: PaletteRoles) = compose.runOnIdle {
        assertEquals("Production theme/editor must expose the current platform roles", expected, rendered)
    }
}

private class ThemeLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
}

data class PaletteRoles(val primary: Int, val surface: Int, val onSurface: Int)

/** Literal Android 34+ role fixtures; unrelated TypedArray reads retain platform behavior. */
@Implements(TypedArray::class)
class MutableSystemThemeColors {
    @RealObject private lateinit var typedArray: TypedArray

    @Implementation(minSdk = 31)
    fun getColor(index: Int, defaultValue: Int): Int {
        val id = typedArray.getResourceId(index, 0)
        if (id != 0 && typedArray.resources.getResourcePackageName(id) == "android") {
            val name = typedArray.resources.getResourceEntryName(id)
            val roles = if (name.endsWith("_dark")) palette.dark else palette.light
            when (name) {
                "system_primary_light", "system_primary_dark" -> return roles.primary
                "system_surface_light", "system_surface_dark" -> return roles.surface
                "system_on_surface_light", "system_on_surface_dark" -> return roles.onSurface
                "system_accent1_600" -> return palette.light.primary
                "system_accent1_200" -> return palette.dark.primary
            }
        }
        return Shadow.directlyOn(typedArray, TypedArray::class.java, "getColor",
            ClassParameter.from(Int::class.javaPrimitiveType!!, index),
            ClassParameter.from(Int::class.javaPrimitiveType!!, defaultValue))
    }

    data class SystemPalette(val light: PaletteRoles, val dark: PaletteRoles)

    companion object {
        val FIRST = SystemPalette(
            PaletteRoles(0xFF336699.toInt(), 0xFFF8F9FA.toInt(), 0xFF101820.toInt()),
            PaletteRoles(0xFF99CCFF.toInt(), 0xFF121417.toInt(), 0xFFEDF2F8.toInt()),
        )
        val SECOND = SystemPalette(
            PaletteRoles(0xFF884400.toInt(), 0xFFFFF8F2.toInt(), 0xFF211307.toInt()),
            PaletteRoles(0xFFFFBB77.toInt(), 0xFF1B120A.toInt(), 0xFFFFEBDC.toInt()),
        )
        // Plain state intentionally cannot invalidate Compose by itself.
        var palette = FIRST

        @JvmStatic @Resetter fun reset() { palette = FIRST }
    }
}
