// Adapted from ExTV/rikkahub-agent, local/KeystoreTools.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import java.util.Base64

private fun cryptoSchema(vararg names: String, optional: Set<String> = emptySet()) = InputSchema.Obj(buildJsonObject {
    names.forEach { name -> put(name, buildJsonObject {
        put("type", if (name == "purposes") "array" else "string")
        put("description", when (name) {
            "alias" -> "Имя ключа только в пространстве этих инструментов: 1–64 латинских буквы, цифры, дефис или подчёркивание."
            "type" -> "Тип: rsa_2048 для sign/verify либо aes_256_gcm для encrypt/decrypt."
            "purposes" -> "От 1 до 2 разных назначений выбранного типа: sign/verify для RSA, encrypt/decrypt для AES."
            "input_encoding" -> "Формат секретного ввода пользователем на защищённом экране: utf8 по умолчанию либо base64. Не передавайте исходный текст модели."
            "data_b64" -> "Открытые несекретные данные в Base64, не более 65536 декодированных байт; не передавайте пароли или токены."
            "signature_b64" -> "Подпись RSA в Base64, не более 256 декодированных байт."
            "ciphertext_b64" -> "Шифротекст AES-GCM с тегом: от 16 до 65552 декодированных байт в Base64."
            "iv_b64" -> "Случайный IV AES-GCM из результата шифрования: ровно 12 декодированных байт в Base64."
            else -> "Параметр криптографического инструмента."
        })
        when (name) {
            "alias" -> { put("minLength", 1); put("maxLength", 64); put("pattern", "^[A-Za-z0-9_-]{1,64}$") }
            "type" -> put("enum", buildJsonArray { add("rsa_2048"); add("aes_256_gcm") })
            "purposes" -> {
                put("items", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("sign"); add("verify"); add("encrypt"); add("decrypt") }) })
                put("minItems", 1); put("maxItems", 2); put("uniqueItems", true)
            }
            "input_encoding" -> { put("enum", buildJsonArray { add("utf8"); add("base64") }); put("default", "utf8") }
        }
        if (name.endsWith("_b64")) put("maxLength", when (name) { "iv_b64" -> 16; "signature_b64" -> 344; "data_b64" -> 87384; else -> 87404 })
    }) }
}, names.filterNot { it in optional })
private fun toolAlias(input: JsonObject) = validateToolKeyAlias(input.textArgument("alias"))
private fun metadataJson(description: ToolKeyDescription) = buildJsonObject {
    put("alias", description.alias); put("type", description.metadata.type.wire)
    put("purposes", buildJsonArray { description.metadata.purposes.forEach { add(it.wire) } })
    put("hardware_backed", description.metadata.hardwareBacked)
}
private suspend fun cryptoResult(action: suspend () -> JsonObject): JsonObject = try { action() }
    catch (error: ToolKeyFailure) { deviceToolError(error.message ?: "Ключ AndroidKeyStore недоступен.") }

internal fun keystoreTools(context: Context, crypto: KeystoreCrypto): List<Tool> = listOf(
    personalJsonTool(context, "keystore_generate_key",
        "Создаёт неэкспортируемый ключ AndroidKeyStore, аппаратный при поддержке устройства. type rsa_2048 для sign/verify или aes_256_gcm для encrypt/decrypt; purposes — 1–2 разных назначения выбранного типа. alias — 1–64 латинских буквы, цифры, дефис или подчёркивание; изолированное пространство инструментов, максимум 128 ключей. Не перезаписывает ключ: сначала проверьте keystore_list_keys. JSON возвращает только метаданные; требуется одобрение.",
        LocalToolOption.Keystore, cryptoSchema("alias", "type", "purposes"), validate = { input ->
            val alias = toolAlias(input)
            val purposes = (input["purposes"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content
                ?: throw IllegalArgumentException("purposes должен быть массивом строк.") }
                ?: throw IllegalArgumentException("purposes должен быть массивом строк.")
            val type = input.textArgument("type")
            validateToolKeySpec(type, purposes)
            Triple(alias, type!!, purposes)
        }, read = { (alias, type, purposes) -> cryptoResult { metadataJson(crypto.generate(alias, type, purposes)) } }),
    personalJsonTool(context, "keystore_sign",
        "Подписывает открытые несекретные data_b64 ключом RSA-2048 с разрешённым sign через SHA256withRSA. Декодированные данные до 65536 байт; JSON возвращает signature_b64. alias смотрите через keystore_list_keys. Не передавайте пароли, токены и приватные ключи в аргументах или чате; для секретных данных используйте защищённый ввод keystore_encrypt. Требуется одобрение.",
        LocalToolOption.Keystore, cryptoSchema("alias", "data_b64"), validate = { input -> toolAlias(input) to decodeToolBase64(input.textArgument("data_b64"), 65536, "data_b64") },
        read = { (alias, data) -> try { cryptoResult { buildJsonObject { put("signature_b64", Base64.getEncoder().encodeToString(crypto.sign(alias, data))) } } } finally { data.fill(0) } }),
    personalJsonTool(context, "keystore_verify",
        "Проверяет signature_b64 над открытыми несекретными data_b64 ключом RSA с назначением verify. Данные до 65536, подпись до 256 декодированных байт; JSON valid сообщает результат, false не является успешной проверкой подписи. alias смотрите через keystore_list_keys. Не передавайте секреты в аргументах. Требуется одобрение.",
        LocalToolOption.Keystore, cryptoSchema("alias", "data_b64", "signature_b64"), validate = { input ->
            val alias = toolAlias(input)
            val signature = decodeToolBase64(input.textArgument("signature_b64"), 256, "signature_b64")
            Triple(alias, decodeToolBase64(input.textArgument("data_b64"), 65536, "data_b64"), signature)
        }, read = { (alias, data, signature) -> try { cryptoResult { buildJsonObject { put("valid", crypto.verify(alias, data, signature)) } } } finally { data.fill(0); signature.fill(0) } }),
    personalJsonTool(context, "keystore_encrypt",
        "Шифрует AES-256-GCM ключом alias с назначением encrypt. Открывает защищённый экран, где только пользователь вводит исходные данные; input_encoding utf8 по умолчанию либо base64, до 65536 декодированных байт. Не передавайте plaintext/пароли в чате или аргументах. JSON возвращает только ciphertext_b64 и случайный iv_b64; секретные байты не сохраняются и не поступают модели. Для обратного показа пользователю — keystore_decrypt; требуется одобрение и открытое приложение.",
        LocalToolOption.Keystore, cryptoSchema("alias", "input_encoding", optional = setOf("input_encoding")), validate = { input ->
            val alias = toolAlias(input)
            require("plaintext_b64" !in input && "plaintext" !in input) { "Секретные данные вводятся только на защищённом экране пользователем." }
            val encoding = if (input.containsKey("input_encoding")) input.textArgument("input_encoding") else "utf8"
            require(encoding in listOf("utf8", "base64")) { "input_encoding должен быть utf8 или base64." }
            alias to encoding!!
        }, read = { (alias, encoding) -> cryptoResult {
            crypto.check(alias, ToolKeyType.AES, ToolKeyPurpose.ENCRYPT)
            val result = awaitPersonalToolUi(context, PersonalUiRequest.Encrypt(alias, encoding), 300000)
            if (result !is PersonalUiResult.Secret) return@cryptoResult personalUiError(result, "Откройте Rikka-Root и введите данные на защищённом экране, если хотите повторить шифрование.")
            try {
                val sealed = crypto.encrypt(alias, result.bytes)
                buildJsonObject { put("ciphertext_b64", Base64.getEncoder().encodeToString(sealed.ciphertext)); put("iv_b64", Base64.getEncoder().encodeToString(sealed.iv)) }
            } finally { result.bytes.fill(0) }
        } }),
    personalJsonTool(context, "keystore_decrypt",
        "Расшифровывает AES-256-GCM ключом alias с назначением decrypt и проверяет подлинность. ciphertext_b64 — 16–65552 байт, iv_b64 — ровно 12 байт после декодирования. Исходный текст показывает только пользователю на защищённом экране открытого приложения; JSON содержит success/shown_to_user, никогда не текст. Не используйте для получения секретов в чат. Для шифрования — keystore_encrypt; требуется одобрение.",
        LocalToolOption.Keystore, cryptoSchema("alias", "ciphertext_b64", "iv_b64"), validate = { input ->
            val alias = toolAlias(input)
            val iv = decodeToolBase64(input.textArgument("iv_b64"), 12, "iv_b64").also(::validateToolIv)
            val ciphertext = decodeToolBase64(input.textArgument("ciphertext_b64"), 65552, "ciphertext_b64")
            require(ciphertext.size >= 16) { "Шифротекст должен включать 16-байтовый тег GCM." }
            Triple(alias, ciphertext, iv)
        }, read = { (alias, ciphertext, iv) -> cryptoResult {
            val plaintext = crypto.decrypt(alias, ciphertext, iv)
            try {
                val result = awaitPersonalToolUi(context, PersonalUiRequest.Reveal(plaintext), 300000)
                if (result != PersonalUiResult.Acknowledged) return@cryptoResult personalUiError(result, "Откройте Rikka-Root, если хотите повторить показ расшифрованных данных на защищённом экране.")
                buildJsonObject { put("success", true); put("shown_to_user", true) }
            } finally { plaintext.fill(0) }
        } }),
    personalJsonTool(context, "keystore_delete_key",
        "Необратимо удаляет ключ только из изолированного пространства этих инструментов AndroidKeyStore. Зашифрованные им данные станут недоступны; проверка подписей возможна лишь при отдельно сохранённом открытом ключе. Сначала проверьте alias через keystore_list_keys. Внутренние ключи приложения недоступны; экспорта или миграции нет. JSON success сообщает об удалении; требуется отдельное одобрение.",
        LocalToolOption.Keystore, cryptoSchema("alias"), ::toolAlias, read = { alias -> cryptoResult { crypto.delete(alias); buildJsonObject { put("success", true) } } }),
    personalJsonTool(context, "keystore_list_keys",
        "Перечисляет только ключи этих инструментов AndroidKeyStore: JSON keys с alias, type, фактическими purposes и hardware_backed. Используйте для выбора alias перед криптооперацией. Не экспортирует приватные или секретные ключи и не показывает внутренние ключи приложения. Пустой список допустим; требуется одобрение.",
        LocalToolOption.Keystore, cryptoSchema(), validate = { Unit }, read = { cryptoResult { buildJsonObject { put("keys", buildJsonArray { crypto.list().forEach { add(metadataJson(it)) } }) } } }),
)
