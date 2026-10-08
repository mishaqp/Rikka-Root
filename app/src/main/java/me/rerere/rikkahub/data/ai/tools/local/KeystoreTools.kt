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
        if (name == "purposes") { put("items", buildJsonObject { put("type", "string") }); put("minItems", 1); put("maxItems", 2) }
        if (name == "alias") put("maxLength", 64)
        if (name.endsWith("_b64")) put("maxLength", when (name) { "iv_b64" -> 16; "signature_b64" -> 344; else -> 87404 })
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
        "Create a non-exportable AndroidKeyStore key, hardware backed if supported. type rsa_2048 for sign/verify or aes_256_gcm for encrypt/decrypt. purposes is a nonempty unique list matching type; only these purposes are authorized. alias [A-Za-z0-9_-]{1,64}, own tool namespace only, at most 128 keys. Never overwrites existing keys. Requires approval.",
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
        "Sign public non-secret base64 data (at most 64 KiB) with an RSA-2048 key, SHA256withRSA. Returns signature_b64. Never put passwords, tokens or private keys in chat/tool arguments. Requires approval and sign purpose.",
        LocalToolOption.Keystore, cryptoSchema("alias", "data_b64"), validate = { input -> toolAlias(input) to decodeToolBase64(input.textArgument("data_b64"), 65536, "data_b64") },
        read = { (alias, data) -> try { cryptoResult { buildJsonObject { put("signature_b64", Base64.getEncoder().encodeToString(crypto.sign(alias, data))) } } } finally { data.fill(0) } }),
    personalJsonTool(context, "keystore_verify",
        "Verify signature_b64 over public non-secret data_b64 (64 KiB max) with an RSA key. Returns valid; requires approval and verify purpose. Do not put secrets in arguments.",
        LocalToolOption.Keystore, cryptoSchema("alias", "data_b64", "signature_b64"), validate = { input ->
            val alias = toolAlias(input)
            val signature = decodeToolBase64(input.textArgument("signature_b64"), 256, "signature_b64")
            Triple(alias, decodeToolBase64(input.textArgument("data_b64"), 65536, "data_b64"), signature)
        }, read = { (alias, data, signature) -> try { cryptoResult { buildJsonObject { put("valid", crypto.verify(alias, data, signature)) } } } finally { data.fill(0); signature.fill(0) } }),
    personalJsonTool(context, "keystore_encrypt",
        "AES-256-GCM encrypt using alias. Opens a protected foreground screen where the USER enters plaintext. input_encoding utf8 (default) or base64. Do NOT provide plaintext in chat/tool arguments. Returns ONLY ciphertext_b64 and random iv_b64. Secret bytes are not stored or sent to the model. Requires approval and encrypt purpose.",
        LocalToolOption.Keystore, cryptoSchema("alias", "input_encoding", optional = setOf("input_encoding")), validate = { input ->
            val alias = toolAlias(input)
            require("plaintext_b64" !in input && "plaintext" !in input) { "Секретные данные вводятся только на защищённом экране пользователем." }
            val encoding = if (input.containsKey("input_encoding")) input.textArgument("input_encoding") else "utf8"
            require(encoding in listOf("utf8", "base64")) { "input_encoding должен быть utf8 или base64." }
            alias to encoding!!
        }, read = { (alias, encoding) -> cryptoResult {
            crypto.check(alias, ToolKeyType.AES, ToolKeyPurpose.ENCRYPT)
            val result = awaitPersonalToolUi(context, PersonalUiRequest.Encrypt(alias, encoding), 300000)
            if (result !is PersonalUiResult.Secret) return@cryptoResult personalUiError(result)
            try {
                val sealed = crypto.encrypt(alias, result.bytes)
                buildJsonObject { put("ciphertext_b64", Base64.getEncoder().encodeToString(sealed.ciphertext)); put("iv_b64", Base64.getEncoder().encodeToString(sealed.iv)) }
            } finally { result.bytes.fill(0) }
        } }),
    personalJsonTool(context, "keystore_decrypt",
        "AES-256-GCM decrypt ciphertext_b64 (16..65552 bytes) and iv_b64 (exactly 12 bytes). Authenticates ciphertext. Shows plaintext ONLY to the user on a protected foreground screen; returns success/shown_to_user status, never plaintext. Requires approval and decrypt purpose.",
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
                if (result != PersonalUiResult.Acknowledged) return@cryptoResult personalUiError(result)
                buildJsonObject { put("success", true); put("shown_to_user", true) }
            } finally { plaintext.fill(0) }
        } }),
    personalJsonTool(context, "keystore_delete_key",
        "Permanently delete a key from the tools' isolated AndroidKeyStore namespace. Data encrypted with it cannot be recovered; signatures remain verifiable only if the public key is retained elsewhere. Cannot delete internal application keys. Requires approval.",
        LocalToolOption.Keystore, cryptoSchema("alias"), ::toolAlias, read = { alias -> cryptoResult { crypto.delete(alias); buildJsonObject { put("success", true) } } }),
    personalJsonTool(context, "keystore_list_keys",
        "List only keys created by these tools: alias, type, actual purposes, hardware_backed. Never exports private/secret keys or lists internal app keys. Requires approval.",
        LocalToolOption.Keystore, cryptoSchema(), validate = { Unit }, read = { cryptoResult { buildJsonObject { put("keys", buildJsonArray { crypto.list().forEach { add(metadataJson(it)) } }) } } }),
)
