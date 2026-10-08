package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Runs Android's backup parser against the packaged resource, rather than reading XML text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AndroidBackupPolicyTest {
    @Test
    fun `legacy backup whitelist includes upload only and cannot include browser or secret domains`() {
        val application = RuntimeEnvironment.getApplication()
        val info = ApplicationInfo(application.applicationInfo)
        // Test-only resource binding avoids starting the real app or its providers. The
        // framework constructor and parser below still resolve our packaged XML themselves.
        ApplicationInfo::class.java.getDeclaredField("fullBackupContent").apply {
            isAccessible = true
            setInt(info, R.xml.backup_rules)
        }
        val context = object : ContextWrapper(application) {
            override fun getApplicationInfo(): ApplicationInfo = info
        }

        // FullBackup's parser is a hidden Android API, so use reflection at the test
        // boundary. A fresh scheme avoids Android's global package-name cache.
        val fullBackup = Class.forName("android.app.backup.FullBackup")
        val schemeType = Class.forName("android.app.backup.FullBackup\$BackupScheme")
        val scheme = schemeType.getDeclaredConstructor(Context::class.java).apply {
            isAccessible = true
        }.newInstance(context)
        @Suppress("UNCHECKED_CAST")
        val includes = schemeType.getMethod("maybeParseAndGetCanonicalIncludePaths")
            .invoke(scheme) as Map<String, Set<Any>>
        val filesToken = fullBackup.getField("FILES_TREE_TOKEN").get(null) as String

        // A nonempty include map makes Android backup a whitelist across domains.
        // This assertion catches accidental removal/broadening of that whitelist even
        // when redundant <exclude> entries have been removed for Android Lint.
        assertTrue("An empty include map permits Android's default broad backup", includes.isNotEmpty())
        assertEquals(setOf(filesToken), includes.keys)
        val canonicalPaths = includes.getValue(filesToken).map { path ->
            path.javaClass.getMethod("getPath").invoke(path) as String
        }.toSet()
        // Android's BackupScheme maps "file" to credential-protected storage,
        // which Robolectric distinguishes from the application's default context.
        val credentialContext = Context::class.java.getMethod("createCredentialProtectedStorageContext")
            .invoke(context) as Context
        val credentialFilesDir = credentialContext.filesDir
        assertEquals(setOf(File(credentialFilesDir, "upload").canonicalPath), canonicalPaths)

        for (domain in listOf("ROOT_TREE_TOKEN", "SHAREDPREFS_TREE_TOKEN", "DATABASE_TREE_TOKEN",
            "DEVICE_ROOT_TREE_TOKEN", "DEVICE_FILES_TREE_TOKEN", "DEVICE_SHAREDPREFS_TREE_TOKEN",
            "DEVICE_DATABASE_TREE_TOKEN", "MANAGED_EXTERNAL_TREE_TOKEN")) {
            val token = fullBackup.getField(domain).get(null) as String
            assertFalse("Private domain $domain must remain outside the upload whitelist", token in includes)
        }
    }
}
