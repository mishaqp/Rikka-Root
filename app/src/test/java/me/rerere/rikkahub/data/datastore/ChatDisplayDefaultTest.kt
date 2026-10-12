package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDisplayDefaultTest {
    @Test fun freshInstallationHidesUserAvatar() {
        assertFalse(DisplaySetting().showUserAvatar)
    }

    @Test fun explicitlySavedAvatarPreferenceSurvivesDecoding() {
        assertTrue(JsonInstant.decodeFromString<DisplaySetting>("""{"showUserAvatar":true}""").showUserAvatar)
        assertFalse(JsonInstant.decodeFromString<DisplaySetting>("""{"showUserAvatar":false}""").showUserAvatar)
    }
}
