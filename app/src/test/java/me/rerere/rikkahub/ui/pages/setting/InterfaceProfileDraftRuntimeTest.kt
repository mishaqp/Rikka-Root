package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** SettingVM writes asynchronously, so an earlier saved value may arrive after another keystroke. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class InterfaceProfileDraftRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anEarlierSavedNameDoesNotReplaceTheMoreRecentTypedName() {
        val fixture = mountMessagesPage()
        val field = compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_name))).performScrollTo()
        field.performTextReplacement("Ан")
        field.performTextReplacement("Анна")
        field.assertIsFocused().assertDraft("Анна")
        compose.runOnIdle {
            assertEquals("Every input still schedules its ordinary persistence write", 2, fixture.pendingWrites.size)
            fixture.emitNextSavedValue()
        }
        field.assertDraft("Анна")
        compose.runOnIdle { fixture.emitNextSavedValue() }
        field.assertDraft("Анна")
        compose.runOnIdle { assertEquals("Анна", fixture.settings.value.displaySetting.userNickname) }
    }

    @Test fun anEarlierSavedEmojiDoesNotReplaceTheMoreRecentTypedAvatar() {
        val fixture = mountMessagesPage()
        val field = compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_avatar_emoji))).performScrollTo()
        field.performTextReplacement("🌷")
        field.performTextReplacement("🌷🌻")
        field.assertIsFocused().assertDraft("🌷🌻")
        compose.runOnIdle {
            assertEquals(2, fixture.pendingWrites.size)
            fixture.emitNextSavedValue()
        }
        field.assertDraft("🌷🌻")
        compose.runOnIdle { fixture.emitNextSavedValue() }
        field.assertDraft("🌷🌻")
        compose.runOnIdle { assertEquals(Avatar.Emoji("🌷🌻"), fixture.settings.value.displaySetting.userAvatar) }
    }

    @Test fun externalSettingsAndBackupValuesStillReachAFocusedEditorWithPendingWrites() {
        val fixture = mountMessagesPage()
        val name = compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_name))).performScrollTo()
        name.performTextReplacement("Ан")
        name.performTextReplacement("Анна")
        name.assertIsFocused()
        compose.runOnIdle {
            val before = fixture.settings.value
            fixture.settings.value = before.copy(displaySetting = before.displaySetting.copy(
                userNickname = "Имя из бэкапа", userAvatar = Avatar.Emoji("🌟")))
        }
        name.assertDraft("Имя из бэкапа")
        val emoji = compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_avatar_emoji))).performScrollTo()
        emoji.assertDraft("🌟")
        emoji.performTextReplacement("🌟🌷")
        emoji.assertIsFocused()
        compose.runOnIdle {
            val before = fixture.settings.value
            fixture.settings.value = before.copy(displaySetting = before.displaySetting.copy(userAvatar = Avatar.Dummy))
        }
        emoji.assertDraft("")
        compose.runOnIdle { assertEquals("External input does not issue an extra persistence write", 3, fixture.pendingWrites.size) }
    }

    @Test fun aConflatedLatestSaveDoesNotLeaveOldAcknowledgementsInTheNextEdit() {
        val fixture = mountMessagesPage()
        val field = compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_name))).performScrollTo()
        field.performTextReplacement("Ан")
        field.performTextReplacement("Анна")
        compose.runOnIdle {
            val first = fixture.pendingWrites.removeAt(0)(fixture.settings.value)
            fixture.settings.value = fixture.pendingWrites.removeAt(0)(first)
        }
        field.assertDraft("Анна")
        field.performTextReplacement("Анна-Мария")
        compose.runOnIdle { fixture.emitNextSavedValue() }
        field.assertDraft("Анна-Мария")
        compose.runOnIdle { assertEquals("Анна-Мария", fixture.settings.value.displaySetting.userNickname) }
    }

    @Test fun typingTheSameNameAgainDoesNotAllowAnIntermediateEchoToReplaceIt() {
        val fixture = mountMessagesPage()
        val field = compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_name))).performScrollTo()
        listOf("Анна", "Анна-Мария", "Анна").forEach { field.performTextReplacement(it) }
        repeat(3) {
            compose.runOnIdle { fixture.emitNextSavedValue() }
            field.assertDraft("Анна")
        }
        compose.runOnIdle { assertEquals("Анна", fixture.settings.value.displaySetting.userNickname) }
    }

    @Test fun aNewImageAvatarStillAppliesWhileNameSavesArePending() {
        val fixture = mountMessagesPage()
        val name = compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_name))).performScrollTo()
        name.performTextReplacement("Ан")
        name.performTextReplacement("Анна")
        val image = Avatar.Image("content://profile-fixture/new-photo")
        compose.runOnIdle {
            val before = fixture.settings.value
            fixture.settings.value = before.copy(displaySetting = before.displaySetting.copy(userAvatar = image))
        }
        compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_avatar_emoji))).assertDraft("")
        name.assertDraft("Анна")
        repeat(2) {
            compose.runOnIdle { fixture.emitNextSavedValue() }
            name.assertDraft("Анна")
        }
        compose.runOnIdle {
            assertEquals(image, fixture.settings.value.displaySetting.userAvatar)
            assertEquals("Анна", fixture.settings.value.displaySetting.userNickname)
        }
    }

    @Test fun aBackupMatchingAnOlderConflatedNameStillAppliesAfterLeavingTheEditor() {
        val fixture = mountMessagesPage()
        val name = compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_name))).performScrollTo()
        listOf("Анна", "Анна-Мария", "Анна").forEach { name.performTextReplacement(it) }
        compose.runOnIdle {
            var saved = fixture.settings.value
            while (fixture.pendingWrites.isNotEmpty()) saved = fixture.pendingWrites.removeAt(0)(saved)
            fixture.settings.value = saved
        }
        name.assertDraft("Анна")
        compose.onNode(hasSetTextAction() and hasText(string(R.string.ui_profile_avatar_emoji)))
            .performClick().assertIsFocused()
        compose.runOnIdle {
            val before = fixture.settings.value
            fixture.settings.value = before.copy(displaySetting = before.displaySetting.copy(userNickname = "Анна-Мария"))
        }
        name.assertDraft("Анна-Мария")
        compose.runOnIdle {
            assertEquals("Анна-Мария", fixture.settings.value.displaySetting.userNickname)
            assertEquals(0, fixture.pendingWrites.size)
        }
    }

    private fun mountMessagesPage(): Fixture {
        val fixture = Fixture()
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalSettings provides fixture.settings.value) {
                    Surface(Modifier.requiredSize(411.dp, 891.dp)) {
                        InterfaceSettingsContent(InterfaceSettingsPageId.Messages, fixture.settings.value,
                            onUpdateSettings = { fixture.pendingWrites += it })
                    }
                }
            }
        }
        compose.onNodeWithTag("ui_settings_list").performScrollToIndex(1)
        return fixture
    }

    private class Fixture {
        val settings = mutableStateOf(Settings().let {
            it.copy(displaySetting = it.displaySetting.copy(userNickname = "Начальное имя", userAvatar = Avatar.Emoji("🌿")))
        })
        val pendingWrites = mutableListOf<(Settings) -> Settings>()
        fun emitNextSavedValue() {
            settings.value = pendingWrites.removeAt(0)(settings.value)
        }
    }

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun SemanticsNodeInteraction.assertDraft(expected: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))
}
