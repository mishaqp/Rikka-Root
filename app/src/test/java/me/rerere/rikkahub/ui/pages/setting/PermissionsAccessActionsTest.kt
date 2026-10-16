package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import me.rerere.rikkahub.root.RootStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PermissionsAccessActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `cached granted root can be checked again only by an explicit tap`() {
        var requests = 0
        val snapshot = PermissionsAccessSnapshot(rows = listOf(
            PermissionAccessRow(PermissionsAccessCatalog.entries.single { it.id == "root" }, PermissionAccessStatus.Granted),
        ), loading = false)
        compose.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    me.rerere.rikkahub.ui.context.LocalNavController provides
                        me.rerere.rikkahub.ui.context.Navigator(mutableListOf()),
                ) {
                    PermissionsAccessPageContent(snapshot, false, RootStatus.READY, false,
                        onRefresh = {}, onGrantAll = {}, onGrant = { requests++ }, onGrantRoot = {},
                        onAllStorageChange = {}, onAddFolder = {}, onRevokeFolder = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithTag("permissions_access_list").performScrollToNode(hasTestTag("permissions_root"))
        compose.onNodeWithTag("permissions_grant_root").performClick()
        assertEquals(1, requests)
    }
}
