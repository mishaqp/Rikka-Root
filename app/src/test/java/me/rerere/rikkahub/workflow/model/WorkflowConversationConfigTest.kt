package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.model.ConversationConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class WorkflowConversationConfigTest {
    @Test fun `authoring chat snapshot survives dedicated database wire roundtrip`() {
        val config = ConversationConfig(chatModelId = Uuid.parse("11111111-1111-1111-1111-111111111111"))
        val original = WorkflowDefinition(id = "workflow", name = "Snapshot", trigger = TriggerSpec.Manual,
            actions = listOf(WorkflowAction("show_toast", buildJsonObject {})),
            authoringAssistantId = "22222222-2222-2222-2222-222222222222",
            authoringConversationId = "33333333-3333-3333-3333-333333333333", callerConversationConfig = config)
        val restored = WorkflowJson.parseStored(WorkflowJson.encode(original))!!
        assertEquals(config, restored.callerConversationConfig)
        assertEquals(original.authoringConversationId, restored.authoringConversationId)
        assertEquals(original.authoringAssistantId, restored.authoringAssistantId)
    }
}
