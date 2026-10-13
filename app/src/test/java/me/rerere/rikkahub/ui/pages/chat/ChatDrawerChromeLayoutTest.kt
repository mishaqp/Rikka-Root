package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusChip
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.uuid.Uuid

/** Covers production drawer chrome, without a root manager or an update checker. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatDrawerChromeLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `header contains identity and settings without a model shortcut`() {
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerHeaderContent(
                        nickname = "Пользователь",
                        rootChip = null,
                        hasUpdate = false,
                        onEditNickname = {},
                        onNavigate = {},
                        greeting = { Text("Добрый вечер", style = MaterialTheme.typography.bodySmall) },
                        avatar = { Box(it.testTag("header_avatar")) },
                    )
                }
            }
        }

        compose.onNodeWithText("Пользователь", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Добрый вечер", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Настройки").assertExists()
        compose.onNodeWithText("Модель этого чата", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "Текущая модель: Модель этого чата. Выбрать модель по умолчанию",
        ).assertDoesNotExist()
        assertEquals("Identity header uses a compact 48dp row", 48f,
            compose.onNodeWithTag("chat_drawer_header").fetchSemanticsNode().boundsInRoot.height, .5f)
    }

    @Test
    fun `history action follows the topic search field and keeps a separate destination`() {
        val destinations = mutableListOf<Screen>()
        var newChats = 0
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerSearchBar(onNavigate = { destinations += it }, onNewChat = { newChats++ })
                }
            }
        }

        val search = compose.onNodeWithContentDescription("Поиск по сообщениям")
        val history = compose.onNodeWithContentDescription("История чатов")
        val fieldBounds = compose.onNodeWithTag("chat_drawer_topic_search").fetchSemanticsNode().boundsInRoot
        val historyBounds = history.fetchSemanticsNode().boundsInRoot
        assertTrue("History is separate from topic search", historyBounds.left >= fieldBounds.right)
        assertEquals("History uses the Moru 44dp visual", 44f, historyBounds.width, .5f)
        assertEquals(fieldBounds.center.y, historyBounds.center.y, .5f)
        assertTrue("History keeps its 48dp touch target", history.fetchSemanticsNode().touchBoundsInRoot.height >= 48f)

        search.performClick()
        history.performClick()
        assertEquals(listOf(Screen.MessageSearch, Screen.History), destinations)
        assertEquals(0, newChats)
    }

    @Test
    fun `compact new chat action is next to search and calls only its own callback`() {
        val destinations = mutableListOf<Screen>()
        var newChats = 0
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerSearchBar(onNavigate = { destinations += it }, onNewChat = { newChats++ })
                }
            }
        }

        val search = compose.onNodeWithTag("chat_drawer_topic_search").fetchSemanticsNode()
        val newChat = compose.onNodeWithContentDescription("Новый чат")
        val action = newChat.fetchSemanticsNode()
        assertTrue("New chat sits to the right of the search field", action.boundsInRoot.left > search.boundsInRoot.right)
        assertEquals("New chat shares the search row", search.boundsInRoot.center.y, action.boundsInRoot.center.y, .5f)
        assertEquals("New chat has a compact 44dp visual", 44f, action.boundsInRoot.width, .5f)
        assertEquals("New chat has a compact 44dp visual", 44f, action.boundsInRoot.height, .5f)
        assertTrue("New chat retains a 48dp touch target", action.touchBoundsInRoot.width >= 48f)
        assertTrue("New chat retains a 48dp touch target", action.touchBoundsInRoot.height >= 48f)
        compose.onNodeWithText("Новый чат").assertDoesNotExist()

        newChat.performClick()
        assertEquals(1, newChats)
        assertTrue("New chat must not navigate to search or history", destinations.isEmpty())
    }

    @Test
    fun `new chat long press shows its Russian tooltip without opening a chat`() {
        var newChats = 0
        val destinations = mutableListOf<Screen>()
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp).padding(top = 64.dp)) {
                    ChatDrawerSearchBar(onNavigate = { destinations += it }, onNewChat = { newChats++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Новый чат").performTouchInput { longClick() }
        compose.onNodeWithText("Новый чат").assertExists()
        assertEquals(0, newChats)
        assertTrue(destinations.isEmpty())
    }

    @Test
    fun `header cached root update settings and nickname actions retain their destinations`() {
        val destinations = mutableListOf<Screen>()
        var edits = 0
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerHeaderContent(
                        nickname = "Пользователь",
                        rootChip = SettingsDashboardStatusChip("Активен", SettingsDashboardStatusTone.Success),
                        hasUpdate = true,
                        onEditNickname = { edits++ },
                        onNavigate = { destinations += it },
                        greeting = { Text("Добрый вечер", style = MaterialTheme.typography.bodySmall) },
                        avatar = { Box(it.testTag("header_avatar")) },
                    )
                }
            }
        }

        val avatar = compose.onNodeWithTag("header_avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithContentDescription("Настройки").fetchSemanticsNode()
        assertEquals(40f, avatar.width, .5f)
        assertEquals(40f, settings.boundsInRoot.width, .5f)
        assertEquals(avatar.center.y, settings.boundsInRoot.center.y, .5f)
        assertTrue(settings.touchBoundsInRoot.width >= 48f)
        compose.onNodeWithText("Пользователь").performClick()
        compose.onNodeWithContentDescription("Root: Активен").performClick()
        compose.onNodeWithContentDescription("Есть обновление. Открыть сведения").performClick()
        compose.onNodeWithContentDescription("Настройки").performClick()
        assertEquals(1, edits)
        assertEquals(listOf(Screen.SettingRoot, Screen.SettingUpdate, Screen.Setting), destinations)
    }

    @Test
    fun `long nickname stays on one line and retains its full accessible value and edit callback`() {
        val nickname = "Очень длинное имя пользователя"
        val scale = mutableFloatStateOf(1.3f)
        var edits = 0
        compose.setContent { ScreenshotTheme(fontScale = scale.floatValue) {
            Box(Modifier.width(276.dp)) {
                ChatDrawerHeaderContent(nickname = nickname, rootChip = null, hasUpdate = false,
                    onEditNickname = { edits++ }, onNavigate = {}, greeting = {}, avatar = { Box(it) })
            }
        } }
        listOf(1.3f, 1.5f).forEach { fontScale ->
            compose.runOnIdle { scale.floatValue = fontScale }
            val node = compose.onNodeWithText(nickname, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle {
                assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            }
            val layout = layouts.single()
            assertEquals("Moru nickname remains one line", 1, layout.lineCount)
            assertTrue("Long nickname uses an ellipsis", layout.isLineEllipsized(0))
            assertEquals("TalkBack keeps the full name", nickname,
                node.config[SemanticsProperties.Text].single().text)
            val row = compose.onNodeWithTag("chat_drawer_header").fetchSemanticsNode().boundsInRoot
            assertTrue("Nickname stays within the profile row", node.boundsInRoot.top >= row.top && node.boundsInRoot.bottom <= row.bottom)
        }
        compose.onNodeWithText(nickname).performClick()
        assertEquals(1, edits)
    }

    @Test
    fun `dense bulk actions fit complete labels and retain touch targets and callbacks at large fonts`() {
        val scale = mutableFloatStateOf(1.3f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val pinned = mutableStateOf(false)
        var pinCalls = 0; var moveCalls = 0; var deleteCalls = 0
        compose.setContent { ScreenshotTheme(fontScale = scale.floatValue) {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                Box(Modifier.width(308.25.dp)) {
                    ChatDrawerSelectionActionBar(2, pinned.value, { pinCalls++ }, { moveCalls++ }, { deleteCalls++ })
                }
            }
        } }
        val observations = mutableListOf<Pair<Boolean, String>>()
        fun recordFullLabel(label: String) {
            val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            val button = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)) }
            val layout = layouts.single()
            val coordinates = text.layoutInfo.coordinates
            val origin = coordinates.localToRoot(Offset.Zero)
            val scaleX = coordinates.localToRoot(Offset(1f, 0f)).x - origin.x
            val scaleY = coordinates.localToRoot(Offset(0f, 1f)).y - origin.y
            fun project(rect: Rect): Rect {
                val start = coordinates.localToRoot(rect.topLeft)
                val end = coordinates.localToRoot(rect.bottomRight)
                return Rect(start.x, start.y, end.x, end.y)
            }
            fun Rect.within(outer: Rect): Boolean =
                left >= outer.left - .5f && right <= outer.right + .5f &&
                    top >= outer.top - .5f && bottom <= outer.bottom + .5f
            val characterBoxes = label.indices.map(layout::getBoundingBox)
            val projectedCharacters = characterBoxes.map(::project)
            val line = Rect(layout.getLineLeft(0), layout.getLineTop(0),
                layout.getLineRight(0), layout.getLineBottom(0))
            val measured = Rect(0f, 0f, layout.size.width.toFloat(), layout.size.height.toFloat())
            val complete = layout.lineCount == 1 && !layout.isLineEllipsized(0) &&
                layout.getLineStart(0) == 0 && layout.getLineEnd(0, visibleEnd = true) == label.length &&
                text.config[SemanticsProperties.Text].single().text == label &&
                button.config[SemanticsProperties.Text].any { it.text == label }
            // With unbounded FittedBox measurement paragraph.width is Infinity, so
            // didOverflowWidth/hasVisualOverflow compare a sentinel against the measured
            // text width. Actual line and character rectangles detect clipping directly.
            val charactersFitMeasured = characterBoxes.all { it.within(measured) }
            val charactersFitButton = projectedCharacters.all { it.within(button.boundsInRoot) }
            val lineFits = line.within(measured) && project(line).within(button.boundsInRoot)
            val textFits = project(measured).within(button.boundsInRoot) && text.boundsInRoot.within(button.boundsInRoot)
            val uniformScaleDown = scaleX > 0f && scaleX <= 1.001f && scaleY > 0f &&
                scaleY <= 1.001f && abs(scaleX - scaleY) < .001f
            val requestedFont = abs(layout.layoutInput.density.fontScale - scale.floatValue) < .001f
            val requestedDirection = layout.layoutInput.layoutDirection == direction.value &&
                text.layoutInfo.layoutDirection == direction.value
            val touchTarget = button.touchBoundsInRoot.width >= 48f && button.touchBoundsInRoot.height >= 48f
            val report = buildString {
                append("direction=${direction.value} fontScale=${scale.floatValue} label=$label\n")
                append("  measuredSize=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}")
                append(" constraints=${layout.layoutInput.constraints} fontSize=${layout.layoutInput.style.fontSize}\n")
                append("  overflow=${layout.hasVisualOverflow} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}")
                append(" lineCount=${layout.lineCount} ellipsis=${layout.isLineEllipsized(0)}")
                append(" lineStart=${layout.getLineStart(0)} lineEnd=${layout.getLineEnd(0)}")
                append(" visibleEnd=${layout.getLineEnd(0, visibleEnd = true)} lineExtent=$line projectedLine=${project(line)}\n")
                append("  coordinatesSize=${coordinates.size} semanticsSize=${text.size} textBounds=${text.boundsInRoot}")
                append(" buttonBounds=${button.boundsInRoot} touchBounds=${button.touchBoundsInRoot}")
                append(" scale=($scaleX,$scaleY) actualFontScale=${layout.layoutInput.density.fontScale}")
                append(" actualDirection=${layout.layoutInput.layoutDirection}\n")
                append("  complete=$complete charactersFitMeasured=$charactersFitMeasured charactersFitButton=$charactersFitButton")
                append(" lineFits=$lineFits textFits=$textFits uniformScaleDown=$uniformScaleDown")
                append(" requestedFont=$requestedFont requestedDirection=$requestedDirection touchTarget=$touchTarget\n")
                label.indices.forEach { index ->
                    append("  char[$index]=${label[index]} local=${characterBoxes[index]} root=${projectedCharacters[index]}\n")
                }
            }
            observations += (complete && !layout.didOverflowHeight && charactersFitMeasured &&
                charactersFitButton && lineFits && textFits && uniformScaleDown && requestedFont &&
                requestedDirection && touchTarget) to report
        }
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { layoutDirection ->
            listOf(1.3f, 1.5f).forEach { fontScale ->
                compose.runOnIdle {
                    direction.value = layoutDirection
                    scale.floatValue = fontScale
                    pinned.value = false
                }
                listOf("Закрепить", "Перенести", "Удалить").forEach { label ->
                    recordFullLabel(label)
                    compose.onNode(hasText(label) and hasClickAction()).performClick()
                }
                val pinBounds = compose.onNode(hasText("Закрепить") and hasClickAction()).fetchSemanticsNode().boundsInRoot
                val moveBounds = compose.onNode(hasText("Перенести") and hasClickAction()).fetchSemanticsNode().boundsInRoot
                val deleteBounds = compose.onNode(hasText("Удалить") and hasClickAction()).fetchSemanticsNode().boundsInRoot
                assertTrue("Buttons follow $layoutDirection reading order", if (layoutDirection == LayoutDirection.Ltr) {
                    pinBounds.right <= moveBounds.left && moveBounds.right <= deleteBounds.left
                } else {
                    deleteBounds.right <= moveBounds.left && moveBounds.right <= pinBounds.left
                })
                compose.runOnIdle { pinned.value = true }
                recordFullLabel("Открепить")
                compose.onNode(hasText("Открепить") and hasClickAction()).performClick()
            }
        }
        assertEquals(listOf(8, 4, 4), listOf(pinCalls, moveCalls, deleteCalls))
        assertTrue(observations.joinToString("\n") { it.second }, observations.all { it.first })
    }

    @Test
    fun `updates display toggle hides the badge without resetting a temporary disable`() {
        val settings = mutableStateOf(Settings())
        val updateChecksEnabled = mutableStateOf(true)
        val destinations = mutableListOf<Screen>()
        compose.setContent { ScreenshotTheme {
            Box(Modifier.width(288.dp)) {
                ChatDrawerHeaderContent(
                    nickname = "Пользователь",
                    rootChip = SettingsDashboardStatusChip("Активен", SettingsDashboardStatusTone.Success),
                    // Cached update is available; this is the existing caller's eligibility gate.
                    hasUpdate = updateChecksEnabled.value,
                    showAppUpdates = settings.value.displaySetting.showAppUpdates,
                    onEditNickname = {}, onNavigate = { destinations += it },
                    greeting = {}, avatar = { Box(it) },
                )
            }
        } }
        val update = compose.onNodeWithContentDescription("Есть обновление. Открыть сведения")
        update.assertExists().performClick()
        assertEquals(listOf(Screen.SettingUpdate), destinations)
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(showAppUpdates = false))
        }
        update.assertDoesNotExist()
        compose.onNodeWithContentDescription("Root: Активен").assertExists()
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(showAppUpdates = true))
        }
        update.assertExists()

        val disabledUntil = System.currentTimeMillis() + 86_400_000L
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(
                updateCheckDisabledUntilEpochMillis = disabledUntil))
            updateChecksEnabled.value = false
        }
        update.assertDoesNotExist()
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(showAppUpdates = false))
        }
        update.assertDoesNotExist()
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(showAppUpdates = true))
        }
        update.assertDoesNotExist()
        assertEquals(disabledUntil, settings.value.displaySetting.updateCheckDisabledUntilEpochMillis)
        assertEquals(false, updateChecksEnabled.value)
        // Once the existing time gate expires, the same enabled preference can show the badge again.
        compose.runOnIdle { updateChecksEnabled.value = true }
        update.assertExists()
    }

    @Test
    fun `assistant avatar aligns with the drawer inset instead of a second inner inset`() {
        val assistant = Assistant(name = "Ассистент")
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(320.dp).padding(horizontal = 16.dp)) {
                    ChatDrawerAssistantCard(
                        settings = settings,
                        onUpdateSettings = {},
                        onNavigate = {},
                        avatarContent = { _, _, modifier, _ -> Box(modifier.testTag("assistant_avatar")) },
                    )
                }
            }
        }

        val row = compose.onNodeWithTag("chat_drawer_assistant").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNodeWithTag("assistant_avatar", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Moru assistant avatar has a 4dp inner inset after the parent 16dp", 20f, avatar.left, .5f)
        assertEquals("Assistant inner inset remains 4dp", row.left + 4f, avatar.left, .5f)
        assertEquals("Compact assistant row remains 48dp", 48f, row.height, .5f)
        assertEquals("Moru assistant avatar is 32dp", 32f, avatar.width, .5f)
    }

    @Test
    fun `assistant row wraps its full name at large font size`() {
        val assistant = Assistant(name = "Ассистент для планирования семейных дел")
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))
        val scale = mutableFloatStateOf(1f)
        compose.setContent {
            ScreenshotTheme(fontScale = scale.floatValue) {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerAssistantCard(
                        settings = settings,
                        onUpdateSettings = {},
                        onNavigate = {},
                        avatarContent = { _, _, modifier, _ -> Box(modifier) },
                    )
                }
            }
        }
        compose.runOnIdle { scale.floatValue = 1.3f }
        val layouts = mutableListOf<TextLayoutResult>()
        val text = compose.onNodeWithText(assistant.name, useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        }
        assertTrue("Assistant name wraps", layouts.single().lineCount > 1)
        assertTrue("Assistant name is not truncated", !layouts.single().hasVisualOverflow)
        val row = compose.onNodeWithTag("chat_drawer_assistant").fetchSemanticsNode().boundsInRoot
        assertTrue("Large assistant name stays inside the row", text.boundsInRoot.bottom <= row.bottom)
    }

    @Test
    fun `assistant dropdown retains combined tag filters selection edit and management`() {
        val workTag = Tag(Uuid.parse("00000000-0000-0000-0000-000000000011"), "Работа")
        val homeTag = Tag(Uuid.parse("00000000-0000-0000-0000-000000000012"), "Дом")
        val work = Assistant(name = "Рабочий", tags = listOf(workTag.id))
        val home = Assistant(name = "Домашний", tags = listOf(homeTag.id))
        val general = Assistant(name = "Общий")
        val settings = mutableStateOf(Settings(assistantId = work.id, assistants = listOf(work, home, general),
            assistantTags = listOf(workTag, homeTag)))
        val destinations = mutableListOf<Screen>()
        compose.setContent {
            ScreenshotTheme(fontScale = 1.3f) {
                Box(Modifier.width(288.dp)) {
                    ChatDrawerAssistantCard(
                        settings = settings.value,
                        onUpdateSettings = { settings.value = it(settings.value) },
                        onNavigate = { destinations += it },
                        avatarContent = { _, _, modifier, _ -> Box(modifier) },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Выбрать ассистента").performClick()
        compose.onNodeWithText("Работа").performClick()
        compose.onNodeWithContentDescription("Настройки ассистента: Рабочий").assertExists()
        compose.onNodeWithContentDescription("Настройки ассистента: Домашний").assertDoesNotExist()
        compose.onNodeWithContentDescription("Настройки ассистента: Общий").assertDoesNotExist()
        compose.onNodeWithText("Дом").performClick()
        compose.onNodeWithContentDescription("Настройки ассистента: Домашний").assertExists()
        compose.onNodeWithText("Работа").performClick()
        compose.onNodeWithContentDescription("Настройки ассистента: Рабочий").assertDoesNotExist()
        compose.onNodeWithContentDescription("Настройки ассистента: Домашний").performClick()
        assertEquals(listOf(Screen.AssistantDetail(home.id.toString())), destinations)

        compose.onNodeWithContentDescription("Выбрать ассистента").performClick()
        compose.onNodeWithText("Домашний").performClick()
        assertEquals(home.id, settings.value.assistantId)
        compose.onNodeWithContentDescription("Выбрать ассистента").performClick()
        compose.onNodeWithText("Управление ассистентами").performClick()
        assertEquals(listOf(Screen.AssistantDetail(home.id.toString()), Screen.Assistant), destinations)
    }

    @Test
    fun `assistant selection and sidebar closing respect expansion preferences`() {
        val first = Assistant(name = "Первый")
        val second = Assistant(name = "Второй")
        val settings = mutableStateOf(Settings(assistantId = first.id, assistants = listOf(first, second)))
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(
            keepSidebarOpenOnAssistantTap = true, keepAssistantListExpandedOnSidebarClose = true))
        val open = mutableStateOf(true)
        compose.setContent { ScreenshotTheme {
            Box(Modifier.width(288.dp)) {
                ChatDrawerAssistantCard(settings.value, { settings.value = it(settings.value) }, {},
                    isDrawerOpen = open.value, avatarContent = { _, _, modifier, _ -> Box(modifier) })
            }
        } }
        compose.onNodeWithContentDescription("Выбрать ассистента").performClick()
        compose.onNodeWithText("Второй").performClick()
        assertEquals(second.id, settings.value.assistantId)
        compose.onNodeWithText("Управление ассистентами").assertExists()
        compose.runOnIdle { open.value = false }
        compose.onNodeWithText("Управление ассистентами").assertExists()
        compose.runOnIdle {
            open.value = true
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(
                keepAssistantListExpandedOnSidebarClose = false))
        }
        compose.runOnIdle { open.value = false }
        compose.onNodeWithText("Управление ассистентами").assertDoesNotExist()
    }
}
