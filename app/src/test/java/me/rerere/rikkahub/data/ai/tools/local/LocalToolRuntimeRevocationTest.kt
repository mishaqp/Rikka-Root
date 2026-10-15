package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LocalToolRuntimeRevocationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aCurrentTurnClipboardClosureCannotWriteAfterItsFeatureIsDisabled() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.writeClipboardText("clipboard before revocation")
        val assistant = Assistant(localTools = listOf(LocalToolOption.Clipboard))
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList(),
            config = ConversationConfig())
        val liveSettings = MutableStateFlow(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns liveSettings
        val localTools = LocalTools(context, AppEventBus(), mockk(), settingsStore,
            mockk(), mockk(), mockk(), mockk(), mockk())
        val snapshot = liveSettings.value.getAssistantOf(conversation)
        val tool = localTools.getTools(snapshot.localTools, snapshot.id.toString(), conversation.id.toString(),
            callerAssistant = snapshot).single { it.name == "clipboard_tool" }

        // localTools is an assistant setting, even for a conversation with a saved config.
        liveSettings.value = liveSettings.value.copy(assistants = listOf(assistant.copy(localTools = emptyList())))
        assertFalse(LocalToolOption.Clipboard in liveSettings.value.getAssistantOf(conversation).localTools)
        val parts = tool.execute(Json.parseToJsonElement("""{"action":"write","text":"must never be written"}"""))

        assertEquals("The current-turn closure must honor the live disabled feature",
            "clipboard before revocation", context.readClipboardText())
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertNotNull(output["error"])
    }

    @Test fun anAndroidClipboardWriteDenialCannotBeReportedAsSuccess() = runBlocking {
        val clipboard = mockk<ClipboardManager>()
        every { clipboard.setPrimaryClip(any()) } throws SecurityException("test Android clipboard denial")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CLIPBOARD_SERVICE) clipboard else super.getSystemService(name)
        }

        val parts = buildClipboardTool(context).execute(Json.parseToJsonElement(
            """{"action":"write","text":"not accepted by Android"}"""))
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

        verify(exactly = 1) { clipboard.setPrimaryClip(any()) }
        assertFalse("A rejected clipboard write must not claim success", output["success"]?.jsonPrimitive?.booleanOrNull == true)
        assertNotNull(output["error"])
    }

    @Test fun anEnabledCurrentTurnClipboardClosureWritesOnceAndKeepsItsApprovalAndSchema() = runBlocking {
        val clipboard = mockk<ClipboardManager>()
        justRun { clipboard.setPrimaryClip(any()) }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CLIPBOARD_SERVICE) clipboard else super.getSystemService(name)
        }
        val assistant = Assistant(localTools = listOf(LocalToolOption.Clipboard))
        val liveSettings = MutableStateFlow(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns liveSettings
        val localTools = LocalTools(context, AppEventBus(), mockk(), settingsStore,
            mockk(), mockk(), mockk(), mockk(), mockk())
        // A library caller can supply its valid assistant object without repeating assistantId.
        val tool = localTools.getTools(assistant.localTools, callerAssistant = assistant)
            .single { it.name == "clipboard_tool" }
        val input = Json.parseToJsonElement("""{"action":"write","text":"accepted by Android"}""")
        val original = buildClipboardTool(context)
        assertEquals(original.needsApproval(input), tool.needsApproval(input))
        assertEquals(original.parameters(), tool.parameters())

        val parts = tool.execute(input)
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

        verify(exactly = 1) { clipboard.setPrimaryClip(any()) }
        assertEquals(true, output.getValue("success").jsonPrimitive.boolean)
        assertEquals("accepted by Android", output.getValue("text").jsonPrimitive.content)
    }

    @Test fun metadataCanBeBuiltWithoutACallerButMissingOrInvalidIdentityCannotExecute() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val assistant = Assistant(localTools = listOf(LocalToolOption.Clipboard))
        val liveSettings = MutableStateFlow(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns liveSettings
        val localTools = LocalTools(context, AppEventBus(), mockk(), settingsStore,
            mockk(), mockk(), mockk(), mockk(), mockk())
        for (id in listOf(null, "invalid-assistant-id")) {
            context.writeClipboardText("unchanged for $id")
            val tool = localTools.getTools(assistant.localTools, assistantId = id).single()
            assertEquals("clipboard_tool", tool.name)
            assertNotNull(tool.parameters())

            val parts = tool.execute(Json.parseToJsonElement("""{"action":"write","text":"must not write"}"""))
            val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

            assertEquals("unchanged for $id", context.readClipboardText())
            assertNotNull(output["error"])
        }
    }

    @Test fun aLiveFeatureGateKeepsTheConversationWorkspaceAndCapturedPathResolver() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val selectedId = Uuid.random()
        val selected = temporary.newFolder("conversation-workspace")
        val other = temporary.newFolder("assistant-workspace")
        File(selected, "project").mkdir()
        File(other, "project").mkdir()
        val assistant = Assistant(workspaceId = Uuid.random(), localTools = listOf(LocalToolOption.Files))
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList(),
            config = ConversationConfig(workspaceId = selectedId), workspaceCwd = "/workspace/project")
        val liveSettings = MutableStateFlow(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns liveSettings
        val localTools = LocalTools(context, AppEventBus(), mockk(), settingsStore,
            mockk(), mockk(), mockk(), mockk(), mockk())
        val snapshot = liveSettings.value.getAssistantOf(conversation)
        val roots = mapOf(selectedId to selected, assistant.workspaceId to other)
        val tool = localTools.getTools(snapshot.localTools, snapshot.id.toString(), conversation.id.toString(),
            workspaceCwd = conversation.workspaceCwd, callerAssistant = snapshot,
            resolveWorkspacePath = { path -> File(roots.getValue(snapshot.workspaceId), path.removePrefix("/workspace").trimStart('/')) })
            .single { it.name == "write_binary_file" }
        liveSettings.value = liveSettings.value.copy(assistants = listOf(assistant.copy(workspaceId = Uuid.random())))
        val input = buildJsonObject {
            put("path", "selected.txt")
            put("base64_content", Base64.getEncoder().encodeToString("conversation data".toByteArray()))
        }
        assertTrue(tool.needsApproval(input))

        val parts = tool.execute(input)
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

        assertTrue(output.toString(), output.getValue("success").jsonPrimitive.boolean)
        assertEquals("conversation data", File(selected, "project/selected.txt").readText())
        assertFalse(File(other, "project/selected.txt").exists())
        assertEquals(selectedId, liveSettings.value.getAssistantOf(conversation).workspaceId)
    }

    @Test fun theMergedRevokeToolKeepsApprovalAndHonorsExternalStorageDisableBeforeReadingGrants() = runBlocking {
        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getContentResolver(): ContentResolver = resolver
        }
        val assistant = Assistant(localTools = listOf(LocalToolOption.ExternalStorage))
        val liveSettings = MutableStateFlow(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns liveSettings
        val localTools = LocalTools(context, AppEventBus(), mockk(), settingsStore,
            mockk(), mockk(), mockk(), mockk(), mockk())
        val tools = localTools.getTools(assistant.localTools, assistant.id.toString(), callerAssistant = assistant)
        assertTrue(tools.any { it.name == "revoke_directory_access" })
        val tool = tools.single { it.name == "revoke_directory_access" }
        val input = Json.parseToJsonElement(
            """{"content_uri":"content://com.android.externalstorage.documents/tree/primary%3ADownload"}""")
        assertTrue(tool.needsApproval(input))
        assertEquals(revokeDirectoryAccessTool(context).needsApproval(input), tool.needsApproval(input))

        liveSettings.value = liveSettings.value.copy(assistants = listOf(assistant.copy(localTools = emptyList())))
        val parts = tool.execute(input)
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

        assertEquals("Функция отключена у ассистента. Включите её в настройках инструментов перед новым вызовом.",
            output.getValue("error").jsonPrimitive.content)
        assertFalse(output.containsKey("revoked"))
        verify(exactly = 0) { resolver.persistedUriPermissions }
        verify(exactly = 0) { resolver.releasePersistableUriPermission(any(), any()) }
    }
}
