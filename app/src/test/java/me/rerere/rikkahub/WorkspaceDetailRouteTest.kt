package me.rerere.rikkahub

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDetailRouteTest {
    @Test
    fun `legacy workspace route without tab flag keeps settings entry`() {
        val route = JsonInstant.decodeFromString(
            Screen.WorkspaceDetail.serializer(),
            """{"id":"workspace-legacy"}""",
        )

        assertEquals("workspace-legacy", route.id)
        assertFalse(route.openFiles)
        assertFalse(Screen.WorkspaceDetail("workspace-existing-caller").openFiles)
    }

    @Test
    fun `files entry survives navigation route serialization`() {
        val route = Screen.WorkspaceDetail("workspace-files", openFiles = true)
        val restored = JsonInstant.decodeFromString(
            Screen.WorkspaceDetail.serializer(),
            JsonInstant.encodeToString(Screen.WorkspaceDetail.serializer(), route),
        )

        assertEquals(route, restored)
        assertTrue(restored.openFiles)
    }
}
