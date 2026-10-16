package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.root.RootStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PermissionsAccessAndroidTest {
    @Before fun installFrameworkPermissionMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric's small platform-permission catalog is not the Android system's
        // complete PackageManager database; supply the two real permissions exercised here.
        val shadow = org.robolectric.Shadows.shadowOf(context.packageManager)
        listOf("android.permission.CAMERA", "android.permission.SCHEDULE_EXACT_ALARM").forEach { name ->
            shadow.addPermissionInfo(PermissionInfo().apply {
                this.name = name
                packageName = "android"
                protectionLevel = if (name.endsWith("CAMERA")) PermissionInfo.PROTECTION_DANGEROUS else PermissionInfo.PROTECTION_NORMAL
            })
        }
        assertTrue("The test must load CAMERA from the actual manifest",
            "android.permission.CAMERA" in context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty())
    }

    @Test @Config(sdk = [31]) fun `Android 12 exact alarm access remains available without Android 13 USE_EXACT_ALARM`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = spyk(context.packageManager)
        every { manager.getPermissionInfo("android.permission.USE_EXACT_ALARM", 0) } throws PackageManager.NameNotFoundException()
        val wrapped = spyk(context)
        every { wrapped.packageManager } returns manager
        val row = loadPermissionsAccess(wrapped, RootStatus.UNCHECKED).rows.single { it.entry.id == "exact_alarms" }
        assertNotEquals(PermissionAccessStatus.Unavailable, row.status)
    }

    @Test fun `every special settings screen has an app-specific fallback`() {
        for (entry in PermissionsAccessCatalog.entries.filter { it.kind == PermissionAccessKind.Special }) {
            val intents = permissionSettingsIntents(entry, "me.mishaqp.rikkaroot")
            assertFalse(entry.id, intents.isEmpty())
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
            assertEquals("package:me.mishaqp.rikkaroot", intents.last().dataString)
        }
        val allFiles = permissionSettingsIntents(PermissionsAccessCatalog.entries.single { it.id == "all_files" }, "me.mishaqp.rikkaroot")
        assertEquals("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", allFiles.first().action)
        assertEquals("package:me.mishaqp.rikkaroot", allFiles.first().dataString)
    }

    @Test fun `refresh reads the current permission state and never retains a revoked grant`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadow = org.robolectric.Shadows.shadowOf(context as Application)
        shadow.grantPermissions("android.permission.CAMERA")
        assertEquals(PermissionAccessStatus.Granted,
            loadPermissionsAccess(context, RootStatus.UNCHECKED).rows.single { it.entry.id == "camera" }.status)
        shadow.denyPermissions("android.permission.CAMERA")
        assertEquals(PermissionAccessStatus.Denied,
            loadPermissionsAccess(context, RootStatus.UNCHECKED).rows.single { it.entry.id == "camera" }.status)
    }
}
