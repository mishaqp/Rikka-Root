package me.rerere.rikkahub.miniapps

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MiniAppArchiveIntentTest {
    @Test fun exportedSnapshotsHaveIndependentBytesReadGrantsAndImportWithoutOverwritingEntries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ReflectionHelpers.getStaticField<MutableMap<String, *>>(FileProvider::class.java, "sCache").clear()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "mini-share-test-").toFile()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val source = root.resolve("source").apply { mkdirs() }
            source.resolve("moru-app.json").writeText("""{"id":"water","name":"Water"}""")
            source.resolve("index.html").writeText("<p>Water</p>")
            val store = MiniAppStore(root.resolve("installed"))
            val app = store.install(source).app
            store.storageSet(app.id, "counter", JsonPrimitive(1))
            val first = MiniAppLauncher.exportUri(context, store, app, true)
            store.storageSet(app.id, "counter", JsonPrimitive(2))
            val second = MiniAppLauncher.exportUri(context, store, app, true)
            assertNotEquals(first, second)
            fun archivedCounter(uri: Uri): Int = context.contentResolver.openInputStream(uri)!!.use { stream ->
                ZipInputStream(stream).use zipUse@ { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == MiniAppStore.archiveDataFile) return@zipUse Json.parseToJsonElement(zip.readBytes().toString(Charsets.UTF_8)).jsonObject.getValue("counter").jsonPrimitive.int
                        entry = zip.nextEntry
                    }
                    error("Archive must include data")
                }
            }
            assertEquals(1, archivedCounter(first))
            assertEquals(2, archivedCounter(second))
            MiniAppLauncher.share(activity.get(), app, first)
            val chooser = shadowOf(context as Application).nextStartedActivity
            val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/zip", send.type)
            assertEquals(first, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals(first, send.clipData!!.getItemAt(0).uri)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val other = MiniAppStore(root.resolve("imported"))
            assertTrue(MiniAppLauncher.importUri(context, first, other).dataRestored)
            assertEquals(JsonPrimitive(1), other.storageGet("water", "counter"))
            other.storageSet("water", "counter", JsonPrimitive(9))
            assertFalse(MiniAppLauncher.importUri(context, second, other).dataRestored)
            assertEquals(JsonPrimitive(9), other.storageGet("water", "counter"))
        } finally { activity.pause().stop().destroy(); root.deleteRecursively() }
    }
}
