package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class QuickMessageMigrationPreservationPart3Test {
    @Test fun damagedGlobalQuickMessagesCannotBeReplacedWithEmptySuccess() = runBlocking {
        for (raw in listOf("""[{"title":"исходный текст"""", """{"recoverable":"исходный текст"}""", "null")) {
            val before = preferencesOf(SettingsStore.VERSION to 2, SettingsStore.ASSISTANTS to "[]", SettingsStore.QUICK_MESSAGES to raw)
            val migration = PreferenceStoreV3Migration()
            assertTrue(migration.shouldMigrate(before))
            val result = runCatching { migration.migrate(before) }
            if (result.isSuccess) {
                assertEquals("Нельзя подтверждать потерю исходного JSON", raw, result.getOrThrow()[SettingsStore.QUICK_MESSAGES])
                assertEquals(2, result.getOrThrow()[SettingsStore.VERSION])
            } else {
                assertEquals(raw, before[SettingsStore.QUICK_MESSAGES])
                assertEquals(2, before[SettingsStore.VERSION])
            }
        }
    }

    @Test fun validExistingAndInlineQuickMessagesRemainUsableAfterV3() = runBlocking {
        val existing = """[{"id":"10000000-0000-0000-0000-000000000001","title":"сохранённое","content":"не менять"}]"""
        val assistants = """[{"id":"20000000-0000-0000-0000-000000000001","quickMessages":[{"title":"старое","content":"перенести"}]}]"""
        val before = preferencesOf(SettingsStore.VERSION to 2, SettingsStore.ASSISTANTS to assistants, SettingsStore.QUICK_MESSAGES to existing)
        val migration = PreferenceStoreV3Migration()
        val after = migration.migrate(before)
        val rows = JsonInstant.parseToJsonElement(after[SettingsStore.QUICK_MESSAGES]!!).jsonArray
        assertEquals(2, rows.size)
        assertEquals(JsonInstant.parseToJsonElement(existing).jsonArray.single(), rows.first())
        val assistant = JsonInstant.parseToJsonElement(after[SettingsStore.ASSISTANTS]!!).jsonArray.single().jsonObject
        assertEquals(rows.last().jsonObject["id"], assistant["quickMessageIds"]!!.jsonArray.single())
        assertEquals(3, after[SettingsStore.VERSION])
        assertFalse(migration.shouldMigrate(after))
        assertEquals(after, migration.migrate(after))
    }
}
