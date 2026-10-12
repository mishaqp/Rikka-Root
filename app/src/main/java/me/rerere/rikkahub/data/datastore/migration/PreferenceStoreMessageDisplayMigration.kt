package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/** Keeps the former implicit avatar preference while giving fresh installations the new default. */
class PreferenceStoreMessageDisplayMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val json = currentData[SettingsStore.DISPLAY_SETTING] ?: return true
        return parseDisplayObject(json)?.containsKey(USER_AVATAR_FIELD) == false
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val json = currentData[SettingsStore.DISPLAY_SETTING]
        val display = if (json == null) {
            // This migration precedes V1 so version keys cannot make a new store look established.
            JsonObject(mapOf(USER_AVATAR_FIELD to JsonPrimitive(currentData.asMap().isNotEmpty())))
        } else {
            val parsed = parseDisplayObject(json) ?: return currentData
            if (USER_AVATAR_FIELD in parsed) return currentData
            preserveLegacyUserAvatar(parsed)
        }
        return currentData.toMutablePreferences().apply {
            this[SettingsStore.DISPLAY_SETTING] = JsonInstant.encodeToString(display)
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/** Backups also used true when an older serializer omitted this setting. */
internal fun preserveLegacyUserAvatar(display: JsonObject): JsonObject =
    if (USER_AVATAR_FIELD in display) display
    else JsonObject(display + (USER_AVATAR_FIELD to JsonPrimitive(true)))

private fun parseDisplayObject(json: String): JsonObject? =
    runCatching { JsonInstant.parseToJsonElement(json) as? JsonObject }.getOrNull()

private const val USER_AVATAR_FIELD = "showUserAvatar"
