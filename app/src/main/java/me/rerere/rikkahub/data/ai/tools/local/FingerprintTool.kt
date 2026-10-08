// Adapted from ExTV/rikkahub-agent, local/FingerprintTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun fingerprintTool(context: Context): Tool = personalJsonTool(context,"verify_fingerprint",
    "Show Android's strong biometric prompt and verify identity. title required (1-120 chars), optional subtitle (at most 240). allow_device_credential defaults false; if true PIN/password/pattern fallback is available, including Android 8-9. Returns success only after actual system authentication; cancellation/error never authenticate.",LocalToolOption.Fingerprint,
    InputSchema.Obj(buildJsonObject {
        put("title",buildJsonObject { put("type","string"); put("maxLength",120) }); put("subtitle",buildJsonObject { put("type","string"); put("maxLength",240) })
        put("allow_device_credential",buildJsonObject { put("type","boolean") })
    },listOf("title")), validate={
        val title=it.textArgument("title"); require(!title.isNullOrBlank() && title.length <= 120) { "title должен содержать от 1 до 120 символов." }
        val subtitle=if (it.containsKey("subtitle")) it.textArgument("subtitle").also { v -> require(v != null && v.length <= 240) { "subtitle должен быть строкой до 240 символов." } } else null
        PersonalUiRequest.Biometric(title,subtitle,personalBoolean(it,"allow_device_credential",false))
    }, read={ request -> when (val result=awaitPersonalToolUi(context,request,180000)) {
        is PersonalUiResult.Authentication -> buildJsonObject { put("success",true); put("method",result.method) }
        else -> personalUiError(result)
    } })
