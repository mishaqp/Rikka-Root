package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewModelScope
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.skills.SkillUrlImporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regressions for existing upstream multi-skill/binary ZIP behavior after Agent extraction. */
class SkillsVmZipCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `all independent skills and binary assets are retained`() = withVm { vm, manager ->
        val binary = byteArrayOf(0x89.toByte(), 0x50, 0, 0xff.toByte(), 0xfe.toByte(), 0x0d, 0x0a)
        val imported = importFiles(vm, linkedMapOf(
            "pack/first/SKILL.md" to skill("first"),
            "pack/first/picture.png" to binary,
            "pack/second/SKILL.md" to skill("second"),
            "pack/second/SOUL.md" to "kept".toByteArray(),
        ))
        assertEquals(listOf("first", "second"), imported)
        assertArrayEquals(binary, manager.resolveSkillFile("first", "picture.png")!!.readBytes())
        assertEquals("kept", manager.resolveSkillFile("second", "SOUL.md")!!.readText())
    }

    @Test
    fun `nested skill is excluded from its parent files`() = withVm { vm, manager ->
        val imported = importFiles(vm, linkedMapOf(
            "parent/SKILL.md" to skill("parent"),
            "parent/guide.md" to "parent guide".toByteArray(),
            "parent/nested/SKILL.md" to skill("nested"),
            "parent/nested/only.md" to "nested guide".toByteArray(),
        ))
        assertEquals(listOf("parent", "nested"), imported)
        assertTrue(manager.resolveSkillFile("parent", "guide.md")!!.exists())
        assertFalse(manager.getSkillDir("parent")!!.resolve("nested").exists())
        assertEquals("nested guide", manager.resolveSkillFile("nested", "only.md")!!.readText())
    }

    @Test
    fun `case-insensitive skill manifest is saved with canonical name`() = withVm { vm, manager ->
        assertEquals(listOf("case-skill"), importFiles(vm, mapOf("package/skill.md" to skill("case-skill"))))
        assertTrue(manager.resolveSkillFile("case-skill", "SKILL.md")!!.exists())
        assertFalse(manager.getSkillDir("case-skill")!!.resolve("skill.md").exists())
    }

    @Suppress("UNCHECKED_CAST")
    private fun importFiles(vm: SkillsVM, files: Map<String, ByteArray>): List<String> {
        val method = SkillsVM::class.java.getDeclaredMethod("importSkillFiles", Map::class.java)
        method.isAccessible = true
        return method.invoke(vm, files) as List<String>
    }

    private fun skill(name: String) = "---\nname: $name\ndescription: ZIP compatibility fixture\n---\nbody\n".toByteArray()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun withVm(check: (SkillsVM, SkillManager) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = AppScope().apply { cancel() }
        val files = temporary.newFolder("context")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = File(files, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(files, "no-backup").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        var vm: SkillsVM? = null
        try {
            val manager = SkillManager(context, SettingsStore(context, scope))
            vm = SkillsVM(context, manager, SkillUrlImporter(manager))
            check(vm, manager)
        } finally {
            vm?.viewModelScope?.cancel()
            runBlocking { vm?.viewModelScope?.coroutineContext?.get(Job)?.join() }
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
