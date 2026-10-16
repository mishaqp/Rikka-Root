package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.pages.setting.PermissionAccessRow
import me.rerere.rikkahub.ui.pages.setting.PermissionAccessStatus
import me.rerere.rikkahub.ui.pages.setting.PermissionsAccessCatalog
import me.rerere.rikkahub.ui.pages.setting.PermissionsAccessPageContent
import me.rerere.rikkahub.ui.pages.setting.PermissionsAccessSnapshot
import me.rerere.rikkahub.ui.pages.setting.SafAccessFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A separate set: recording never touches any chat screenshot archive. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class PermissionsAccessScreenshotsTest internal constructor(
    private val theme: ScreenshotThemeMode,
    private val fontScale: Float,
    private val widthDp: Int,
) {
    @get:Rule val compose = createComposeRule()

    @Test fun screenShowsAccessWithoutRequestingItUntilUserTaps() {
        RuntimeEnvironment.setQualifiers("ru-rRU-w${widthDp}dp-h891dp-mdpi")
        var grants = 0
        var rootGrants = 0
        var bulkRequests = 0
        var folderRequests = 0
        var revoked: SafAccessFolder? = null
        val folder = SafAccessFolder("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FRikka", "/sdcard/Download/Rikka", true)
        val snapshot = PermissionsAccessSnapshot(
            rows = PermissionsAccessCatalog.entries.map { entry ->
                PermissionAccessRow(entry, if (entry.availableOn(35)) PermissionAccessStatus.Denied else PermissionAccessStatus.Unavailable)
            }, folders = listOf(folder), runtimeRequest = listOf("android.permission.CAMERA"), loading = false,
        )
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                Surface(Modifier.requiredSize(widthDp.dp, 891.dp).testTag("permissions_access_capture")) {
                    PermissionsAccessPageContent(snapshot, allStorageEnabled = false,
                        rootStatus = RootStatus.READY, busy = false,
                        onRefresh = {}, onGrantAll = { bulkRequests++ }, onGrant = { grants++ },
                        onGrantRoot = { rootGrants++ }, onAllStorageChange = {},
                        onAddFolder = { folderRequests++ }, onRevokeFolder = { revoked = it })
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, grants)
        assertEquals(0, rootGrants)
        assertEquals(0, bulkRequests)
        assertEquals(0, folderRequests)
        compose.onNodeWithText("Разрешения и доступ").assertIsDisplayed()
        capture("overview")
        assertTextFits("Разрешения и доступ")
        assertTextFits("Выдать все обычные разрешения")
        assertTextFits("Файловые инструменты: вся память телефона")
        compose.onNodeWithTag("permissions_grant_all").performClick()
        assertEquals(1, bulkRequests)
        compose.onNodeWithTag("permissions_access_list").performScrollToNode(hasTestTag("permissions_folders"))
        compose.onNodeWithTag("permissions_add_folder").performScrollTo().performClick()
        assertEquals(1, folderRequests)
        compose.onNodeWithText("/sdcard/Download/Rikka").assertIsDisplayed()
        compose.onNodeWithText("Отозвать").performScrollTo().performClick()
        assertEquals(folder, revoked)
        compose.onNodeWithTag("permissions_access_list").performScrollToNode(hasTestTag("permissions_camera"))
        compose.onNodeWithTag("permissions_grant_camera").performScrollTo().performClick()
        assertEquals(1, grants)
        compose.onNodeWithTag("permissions_root_camera").performScrollTo().performClick()
        assertEquals(1, rootGrants)
        compose.onNodeWithTag("permissions_access_list").performScrollToNode(hasTestTag("permissions_exact_alarms"))
        compose.onNodeWithTag("permissions_exact_alarms").assertIsDisplayed()
        assertTextFits("Точные будильники")
        capture("special_access")
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Text must be measured: $text", layouts.isNotEmpty())
        assertTrue("Text must wrap without being cut off: $text; " + layouts.joinToString { result ->
            "size=${result.size}, lines=${result.lineCount}, width=${result.didOverflowWidth}, height=${result.didOverflowHeight}, constraints=${result.layoutInput.constraints}"
        }, layouts.none { it.hasVisualOverflow })
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val case = "${theme.fileName}_font${fontScale}_width$widthDp"
        val baseline = File("src/test/screenshots/permissions-access/$case/$name.png")
        requireNotNull(baseline.parentFile).mkdirs()
        val artifact = File(System.getProperty("ui.screenshots.dir", "build/outputs/roborazzi"), "permissions-access/$case/$name.png")
        requireNotNull(artifact.parentFile).mkdirs()
        val options = RoborazziOptions(
            taskType = if (System.getProperty("chat.stage5.record").toBoolean()) RoborazziTaskType.Record else RoborazziTaskType.Verify,
            compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = .007f)),
        )
        captureRoboFrame(artifact, baseline, options) { file, captureOptions ->
            compose.onNodeWithTag("permissions_access_capture").captureRoboImage(file.absolutePath, roborazziOptions = captureOptions)
        }
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}_font{1}_width{2}") fun cases(): List<Array<Any>> =
            listOf(ScreenshotThemeMode.Light, ScreenshotThemeMode.Dark, ScreenshotThemeMode.Amoled).flatMap { theme ->
                listOf(arrayOf<Any>(theme, 1f, 411), arrayOf<Any>(theme, 1.3f, 320))
            }
    }
}
