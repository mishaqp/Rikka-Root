package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.HeadlessToolPolicy
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.ai.tools.WebContentGuard
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.service.localOptionForHeadlessTool
import me.rerere.rikkahub.ui.components.ai.tools.LocalToolUiRegistry
import me.rerere.rikkahub.ui.components.message.timelineToolIcon
import me.rerere.rikkahub.ui.components.message.timelineToolNameResource
import me.rerere.tts.provider.TTSManager
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class NewLocalToolsRegistrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val groups = linkedMapOf(
        "http_request" to listOf("http_request"),
        "alarm_clock" to listOf("set_alarm", "set_timer", "show_alarms"),
        "qr_codes" to listOf("qr_generate", "qr_scan"),
        "read_document" to listOf("read_document"),
    )
    private fun option(name: String): LocalToolOption = Json.decodeFromString("""{"type":"$name"}""")

    @Test fun backupNamesRoundTripAndNewOrRestoredAssistantsKeepAllOptionsOff() {
        val options = groups.keys.map(::option)
        groups.keys.zip(options).forEach { (name, option) ->
            assertEquals("""{"type":"$name"}""", Json.encodeToString<LocalToolOption>(option))
        }
        for (assistant in listOf(Assistant(), Json.decodeFromString<Assistant>("{}"))) {
            assertTrue(assistant.localTools.none { it in options })
        }
    }

    @Test fun sevenToolsHavePermissionNamesRussianResourcesIconsAndHeadlessMappings() {
        for ((serialName, names) in groups) {
            val option = option(serialName)
            val ui = LocalToolUiRegistry.entry(option)
            assertEquals(serialName != "alarm_clock", ui.background)
            for (name in names) {
                assertTrue(name, name in ToolPermissionPolicy.registry)
                assertNotNull(name, timelineToolNameResource(name))
                assertNotNull(name, timelineToolIcon(name))
                assertEquals(name, option, localOptionForHeadlessTool(name))
                assertEquals(name, if (serialName == "alarm_clock") "interactive_tool" else null,
                    HeadlessToolPolicy.blockReason(name, Json.parseToJsonElement("{}")))
            }
        }
        assertTrue(WebContentGuard.isClientReader("http_request"))
        assertFalse(WebContentGuard.isClientReader("read_document"))
    }

    @Test fun onlyEnabledGroupsRegisterTheirExactToolsAndDisabledFileToolsStayAbsent() = fixture { local ->
        assertTrue(local.getTools(emptyList()).isEmpty())
        groups.forEach { (serialName, names) ->
            val actual = local.getTools(listOf(option(serialName))).map { it.name }
            assertEquals(serialName, names.toSet(), actual.toSet())
            assertEquals(serialName, names.size, actual.size)
        }
        val names = local.getTools(groups.keys.map(::option)).map { it.name }
        assertEquals(groups.values.flatten().toSet(), names.toSet())
        assertEquals(7, names.toSet().size)
        assertTrue(scopedFileTools(context(folder.newFolder()), listOf(LocalToolOption.Files))
            .none { it.name in groups.values.flatten() })
    }

    @Test fun httpApprovalKeepsDefaultGetAndHeadFreeAfterTheAppPolicyIsApplied() {
        val tool = ToolPermissionPolicy.apply(httpRequestTool(OkHttpClient()))
        for (input in listOf("{}", """{"method":"GET"}""", """{"method":"HEAD"}""",
            """{"method":"get"}""", """{"method":"head"}""")) {
            assertFalse(input, tool.needsApproval(Json.parseToJsonElement(input)))
        }
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "post")) {
            assertTrue(method, tool.needsApproval(Json.parseToJsonElement("""{"method":"$method"}""")))
        }
    }

    @Test fun httpApprovalRejectsMalformedAndUnknownMethods() {
        val tool = ToolPermissionPolicy.apply(httpRequestTool(OkHttpClient()))
        for (input in listOf("null", "[]", "42", "\"GET\"", """{"method":null}""", """{"method":1}""", """{"method":true}""",
            """{"method":[]}""", """{"method":{}}""", """{"method":""}""",
            """{"method":"TRACE"}""", """{"method":" GET "}""")) {
            assertTrue(input, tool.needsApproval(Json.parseToJsonElement(input)))
        }
    }

    private fun context(dir: File): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
        override fun getNoBackupFilesDir(): File = File(dir, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(dir, "cache").apply { mkdirs() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fixture(check: (LocalTools) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = AppScope().apply { cancel() }
        try {
            val context = context(folder.newFolder())
            val settings = SettingsStore(context, scope)
            val files = FilesManager(context, FilesRepository(rejectCalls(ManagedFileDAO::class.java)), scope)
            val key = SecretKeySpec(ByteArray(32), "AES")
            val ssh = SshHostRepository(rejectCalls(SshHostDao::class.java),
                SshCredentialStore(File(context.noBackupFilesDir, "ssh.enc"), Json) { key })
            startKoin { modules(module { single { OkHttpClient() } }) }
            check(LocalTools(context, AppEventBus(), TTSManager(context), settings, RootShellManager(),
                RootAccessStore(context.noBackupFilesDir), files, TermuxPreferences(context), ssh))
        } finally {
            stopKoin()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun <T> rejectCalls(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        error("Registration must not access ${type.simpleName}.${method.name}")
    })
}
