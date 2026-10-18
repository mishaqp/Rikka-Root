// Adapted from ExTV/rikkahub-agent, local/FingerprintTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun fingerprintTool(context: Context): Tool = personalJsonTool(context,"verify_fingerprint",
    "Показывает системную проверку сильной биометрии Android. title — обязательный заголовок 1–120 символов, subtitle — до 240; allow_device_credential=false по умолчанию, true разрешает PIN, пароль или графический ключ, включая Android 8–9. JSON success и method возвращаются только после реальной системной аутентификации; отмена и ошибка не подтверждают личность. Не используйте как замену отдельному одобрению другого инструмента.",LocalToolOption.Fingerprint,
    InputSchema.Obj(buildJsonObject {
        put("title",buildJsonObject { put("type","string"); put("maxLength",120); put("description", "Обязательный непустой заголовок системной проверки, 1–120 символов."); put("minLength", 1) }); put("subtitle",buildJsonObject { put("type","string"); put("maxLength",240); put("description", "Необязательный подзаголовок проверки, до 240 символов.") })
        put("allow_device_credential",buildJsonObject { put("type","boolean"); put("description", "Разрешить PIN, пароль или графический ключ Android вместо биометрии; по умолчанию false."); put("default", false) })
    },listOf("title")), validate={
        val title=it.textArgument("title"); require(!title.isNullOrBlank() && title.length <= 120) { "title должен содержать от 1 до 120 символов." }
        val subtitle=if (it.containsKey("subtitle")) it.textArgument("subtitle").also { v -> require(v != null && v.length <= 240) { "subtitle должен быть строкой до 240 символов." } } else null
        PersonalUiRequest.Biometric(title,subtitle,personalBoolean(it,"allow_device_credential",false))
    }, read={ request -> when (val result=awaitPersonalToolUi(context,request,180000)) {
        is PersonalUiResult.Authentication -> buildJsonObject { put("success",true); put("method",result.method) }
        else -> personalUiError(result, "Проверьте биометрию и способ блокировки экрана в настройках безопасности Android.")
    } })
