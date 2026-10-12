package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreMessageDisplayMigrationTest {
    private val migration = PreferenceStoreMessageDisplayMigration()

    @Test fun freshStoreKeepsNewAvatarDefaultThroughExistingMigrations() = runBlocking {
        val fresh = emptyPreferences()
        assertTrue(migration.shouldMigrate(fresh))
        var migrated = migration.migrate(fresh)
        for (existing in listOf(
            PreferenceStoreV1Migration(), PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(), PreferenceStoreV4Migration(),
        )) {
            if (existing.shouldMigrate(migrated)) migrated = existing.migrate(migrated)
        }
        val display = JsonInstant.decodeFromString<DisplaySetting>(migrated[SettingsStore.DISPLAY_SETTING]!!)
        assertFalse(display.showUserAvatar)
        assertEquals(4, migrated[SettingsStore.VERSION])
        assertFalse(migration.shouldMigrate(migrated))
    }

    @Test fun existingStoreWithoutDisplayJsonKeepsPreviousVisibleAvatar() = runBlocking {
        val previous = preferencesOf(SettingsStore.VERSION to 4, SettingsStore.THEME_ID to "old-theme")
        val migrated = migration.migrate(previous)
        assertTrue(JsonInstant.decodeFromString<DisplaySetting>(migrated[SettingsStore.DISPLAY_SETTING]!!).showUserAvatar)
        assertEquals("old-theme", migrated[SettingsStore.THEME_ID])
    }

    @Test fun omittedLegacyDefaultIsPreservedWithoutTouchingOtherDisplayFields() = runBlocking {
        val previous = preferencesOf(SettingsStore.DISPLAY_SETTING to
            """{"bubbleOpacity":0.4,"showModelIcon":false,"futureField":"keep"}""")
        val migrated = migration.migrate(previous)
        val display = JsonInstant.parseToJsonElement(migrated[SettingsStore.DISPLAY_SETTING]!!).jsonObject
        assertEquals(JsonPrimitive(true), display["showUserAvatar"])
        assertEquals(JsonPrimitive(0.4), display["bubbleOpacity"])
        assertEquals(JsonPrimitive(false), display["showModelIcon"])
        assertEquals(JsonPrimitive("keep"), display["futureField"])
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(migrated, migration.migrate(migrated))
    }

    @Test fun explicitSavedValuesRemainUntouched() = runBlocking {
        for (visible in listOf(true, false)) {
            val previous = preferencesOf(SettingsStore.DISPLAY_SETTING to """{"showUserAvatar":$visible}""")
            assertFalse(migration.shouldMigrate(previous))
            assertEquals(previous, migration.migrate(previous))
        }
    }

    @Test fun malformedDisplaySettingsRemainUntouchedForExistingReadErrorHandling() = runBlocking {
        for (value in listOf("not json", "[]", "null")) {
            val previous = preferencesOf(SettingsStore.DISPLAY_SETTING to value)
            assertFalse(migration.shouldMigrate(previous))
            assertEquals(previous, migration.migrate(previous))
        }
    }
}
