package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.skills.SkillInstallCaller
import kotlin.uuid.Uuid

/** Necessary ConversationConfig adaptation of Agent's skill auto-enable callback. */
internal fun createSkillInstallCaller(
    owner: Uuid,
    conversationId: Uuid?,
    headless: Boolean,
    loadAssistant: suspend (Uuid) -> Assistant,
    updateAssistant: suspend (Uuid, (Assistant) -> Assistant) -> Unit,
): SkillInstallCaller {
    fun foregroundId(): Uuid {
        check(!headless) { "Установка навыков требует подтверждения в открытом чате." }
        return checkNotNull(conversationId) { "Контекст текущего чата недоступен." }
    }
    return SkillInstallCaller(
        enabledSkills = {
            val live = loadAssistant(foregroundId())
            check(live.id == owner) { "Владелец чата изменился; повторите установку навыка." }
            live.enabledSkills
        },
        saveEnabledSkills = { skills ->
            updateAssistant(foregroundId()) { live ->
                check(live.id == owner) { "Владелец чата изменился; повторите установку навыка." }
                live.copy(enabledSkills = skills)
            }
        },
    )
}
