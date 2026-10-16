package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PermissionsAccessCatalogTest {
    private val catalog get() = PermissionsAccessCatalog.entries

    @Test fun `catalog only contains permissions already declared by app and web`() {
        val declared = listOf(File("src/main/AndroidManifest.xml"), File("../web/src/main/AndroidManifest.xml"))
            .flatMap { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    .getElementsByTagName("uses-permission")
                (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("android:name").nodeValue }
            }.toSet() + setOf("android.permission.USE_FINGERPRINT", "android.permission.WAKE_LOCK") // Merged AndroidX grants.
        assertEquals(catalog.size, catalog.map { it.id }.distinct().size)
        assertTrue(catalog.all { it.title.isNotBlank() && it.purpose.isNotBlank() })
        assertTrue(catalog.flatMap { it.permissions }.all { it in declared })
        assertTrue("Every declared access is described", declared.all { it in catalog.flatMap { row -> row.permissions } })
        assertTrue(catalog.any { "android.permission.MANAGE_EXTERNAL_STORAGE" in it.permissions })
        for (notAdded in listOf("SYSTEM_ALERT_WINDOW", "BIND_ACCESSIBILITY_SERVICE", "BIND_NOTIFICATION_LISTENER_SERVICE",
            "QUERY_ALL_PACKAGES", "ACCESS_BACKGROUND_LOCATION")) {
            assertFalse(notAdded, catalog.flatMap { it.permissions }.any { it.endsWith(".$notAdded") })
        }
    }

    @Test fun `bulk request contains only declared available ungranted dangerous permissions`() {
        val declared = catalog.flatMap { it.permissions }.toSet()
        val supported = declared - "com.termux.permission.RUN_COMMAND"
        val requested = PermissionsAccessCatalog.runtimeRequest(35, declared, supported,
            setOf("android.permission.CAMERA", "android.permission.READ_CONTACTS"))
        assertTrue(requested.containsAll(listOf("android.permission.POST_NOTIFICATIONS", "android.permission.SEND_SMS",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR", "android.permission.NEARBY_WIFI_DEVICES")))
        assertFalse("com.termux.permission.RUN_COMMAND" in requested)
        assertFalse("android.permission.CAMERA" in requested)
        assertFalse("android.permission.READ_CONTACTS" in requested)
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in requested)
        assertFalse("android.permission.ACCESS_LOCAL_NETWORK" in requested)
        assertTrue(requested.none { it.endsWith("MANAGE_EXTERNAL_STORAGE") || it.endsWith("SCHEDULE_EXACT_ALARM") })
        assertEquals(requested.size, requested.distinct().size)
        assertTrue(PermissionsAccessCatalog.runtimeRequest(35, emptySet(), supported, emptySet()).isEmpty())
    }

    @Test fun `SDK bounds match storage notification bluetooth and local network changes`() {
        val names = catalog.flatMap { it.permissions }.toSet()
        fun requested(sdk: Int) = PermissionsAccessCatalog.runtimeRequest(sdk, names, names, emptySet())
        assertTrue("android.permission.WRITE_EXTERNAL_STORAGE" in requested(28))
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in requested(29))
        assertFalse("android.permission.POST_NOTIFICATIONS" in requested(32))
        assertTrue("android.permission.POST_NOTIFICATIONS" in requested(33))
        assertFalse("android.permission.BLUETOOTH_CONNECT" in requested(30))
        assertTrue("android.permission.BLUETOOTH_CONNECT" in requested(31))
        assertFalse("android.permission.ACCESS_LOCAL_NETWORK" in requested(36))
        assertTrue("android.permission.ACCESS_LOCAL_NETWORK" in requested(37))
        assertTrue(requested(35).indexOf("android.permission.ACCESS_COARSE_LOCATION") <
            requested(35).indexOf("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test fun `precise location request also includes approximate permission after approximate was granted`() {
        val names = catalog.flatMap { it.permissions }.toSet()
        val request = PermissionsAccessCatalog.runtimeRequest(35, names, names,
            setOf("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue("android.permission.ACCESS_FINE_LOCATION" in request)
        assertTrue("Android 12 requires both location permissions in one request", "android.permission.ACCESS_COARSE_LOCATION" in request)
    }

    @Test fun `boot recovery access is shown on Android 8 and does not depend on foreground service permission`() {
        val boot = catalog.single { "android.permission.RECEIVE_BOOT_COMPLETED" in it.permissions }
        assertTrue(boot.availableOn(26))
        assertEquals(listOf("android.permission.RECEIVE_BOOT_COMPLETED"), boot.permissions)
    }

    @Test fun `notifications can be enabled in Android settings before Android 13 runtime permission exists`() {
        val legacy = catalog.firstOrNull { it.id == "legacy_notifications" }
        assertNotNull(legacy)
        assertTrue(requireNotNull(legacy).availableOn(26))
        assertTrue(legacy.availableOn(32))
        assertFalse(legacy.availableOn(33))
        assertEquals(PermissionAccessKind.Special, legacy.kind)
    }

    @Test fun `root grant plans contain only fixed permission operations and the actual Android user`() {
        val sms = catalog.single { it.id == "sms" }
        assertEquals(listOf(
            "pm grant --user 10 me.mishaqp.rikkaroot android.permission.READ_SMS",
            "pm grant --user 10 me.mishaqp.rikkaroot android.permission.SEND_SMS",
        ), permissionRootGrantCommands(sms, "me.mishaqp.rikkaroot", 10, 35))
        assertEquals(listOf("appops set --user 0 me.mishaqp.rikkaroot MANAGE_EXTERNAL_STORAGE allow"),
            permissionRootGrantCommands(catalog.single { it.id == "all_files" }, "me.mishaqp.rikkaroot", 0, 35))
        assertEquals(listOf("cmd notification allow_dnd me.mishaqp.rikkaroot 10"),
            permissionRootGrantCommands(catalog.single { it.id == "dnd" }, "me.mishaqp.rikkaroot", 10, 35))
        assertTrue(permissionRootGrantCommands(catalog.single { it.id == "camera" }, "bad;touch /tmp/bad", 0, 35).isEmpty())
        assertTrue(permissionRootGrantCommands(sms, "me.mishaqp.rikkaroot", -1, 35).isEmpty())
        assertTrue(permissionRootGrantCommands(catalog.single { it.id == "legacy_storage" }, "me.mishaqp.rikkaroot", 0, 35).isEmpty())
        assertTrue(permissionRootGrantCommands(catalog.single { it.id == "network" }, "me.mishaqp.rikkaroot", 0, 35).isEmpty())
    }

    @Test fun `SAF paths explain primary removable and cloud folders without inventing phone paths`() {
        assertEquals("/sdcard/Download/Rikka", safFolderDisplayPath("primary:Download/Rikka", "Rikka"))
        assertEquals("/storage/1234-ABCD/Documents", safFolderDisplayPath("1234-ABCD:Documents", "Documents"))
        assertEquals("Облачная папка", safFolderDisplayPath("cloud-opaque-id", "Облачная папка"))
        assertEquals("Папка", safFolderDisplayPath(null, ""))
    }
}
