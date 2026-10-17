package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMandatoryConfirmationAuditTest {
    private fun confirmed(name: String, input: String) {
        val arguments = Json.parseToJsonElement(input)
        assertTrue("$name requires separate confirmation: $input", ToolPermissionPolicy.mandatoryConfirmation(name, arguments))
        assertFalse("A destructive file call must not receive a persistent grant", ToolPermissionPolicy.canGrantAlways(name, arguments))
        assertFalse("A destructive file call must not inherit a chat grant", ToolPermissionPolicy.canGrantForChat(name, arguments))
    }

    @Test fun recursiveBatchDeletionRequiresSeparateConfirmation() {
        confirmed("batch_delete", """{"paths":["/scratch/tree"],"recursive":true}""")
        confirmed("batch_delete", """{"root":"/sdcard/DCIM","pattern":"*","recursive":true}""")
    }

    @Test fun movingSharedFilesRequiresConfirmationForBothDirectionsAndBatches() {
        for (path in listOf(
            "/sdcard/DCIM/photo.jpg", "/storage/emulated/0/DCIM/photo.jpg",
            "/storage/ABCD-1234/photo.jpg", "/storage/self/primary/photo.jpg",
            "/mnt/sdcard/photo.jpg", "file:///sdcard/photo.jpg",
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2Fphoto.jpg",
        )) {
            val encoded = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(path))
            confirmed("move_file", """{"src":$encoded,"dst":"/scratch/photo.jpg"}""")
            confirmed("move_file", """{"src":"/scratch/photo.jpg","dst":$encoded}""")
            confirmed("batch_move", """{"paths":[$encoded],"dst_dir":"/scratch"}""")
            confirmed("batch_move", """{"root":"/scratch","pattern":"*","dst_dir":$encoded}""")
        }
        confirmed("batch_move", """{"root":"/sdcard/DCIM","pattern":"*","dst_dir":"/scratch"}""")
    }

    @Test fun privateMovesAndNonRecursiveDeletionRetainTheirGrantPolicy() {
        for ((name, input) in listOf(
            "move_file" to """{"src":"/scratch/a","dst":"/workspace/b"}""",
            "move_file" to """{"src":"file:///scratch/a","dst":"/upload/b"}""",
            "move_file" to """{"src":"/scratch/storage/a","dst":"/scratch/b"}""",
            "batch_move" to """{"paths":["/scratch/a"],"dst_dir":"/scratch/b"}""",
            "batch_delete" to """{"paths":["/scratch/a"],"recursive":false}""",
            "batch_delete" to """{"paths":["/scratch/a"]}""",
        )) {
            val arguments = Json.parseToJsonElement(input)
            assertFalse("Existing grant policy for $name must remain: $input", ToolPermissionPolicy.mandatoryConfirmation(name, arguments))
            assertTrue(ToolPermissionPolicy.canGrantAlways(name, arguments))
            assertTrue(ToolPermissionPolicy.canGrantForChat(name, arguments))
        }
    }

    @Test fun equivalentSharedStoragePathsCannotBypassMoveConfirmation() {
        for (path in listOf(
            "/./sdcard/DCIM/photo.jpg", "//storage/emulated/0/DCIM/photo.jpg",
            "/storage/emulated/0/Download/../DCIM/photo.jpg", "/mnt/media_rw/ABCD-1234/photo.jpg",
            "file:///./sdcard/photo.jpg", "file:///storage/emulated/0/Download/%2e%2e/DCIM/photo.jpg",
        )) {
            val encoded = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(path))
            confirmed("move_file", """{"src":$encoded,"dst":"/scratch/photo.jpg"}""")
            confirmed("move_file", """{"src":"/scratch/photo.jpg","dst":$encoded}""")
            confirmed("batch_move", """{"paths":[$encoded],"dst_dir":"/scratch"}""")
            confirmed("batch_move", """{"root":"/scratch","pattern":"*","dst_dir":$encoded}""")
            confirmed("batch_move", """{"root":$encoded,"pattern":"*","dst_dir":"/scratch"}""")
        }
    }
}
