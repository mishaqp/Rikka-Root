// Adapted from ExTV/rikkahub-agent, local/NfcNdefCodec.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal data class NfcRecordData(val tnf: Short, val type: ByteArray, val id: ByteArray, val payload: ByteArray)

object NfcNdefCodec {
    private val uriPrefixes = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://",
        "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://",
        "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    fun encode(records: JsonArray): NdefMessage = NdefMessage(encodeRecords(records).map {
        NdefRecord(it.tnf, it.type, it.id, it.payload)
    }.toTypedArray())

    fun decode(message: NdefMessage): JsonArray {
        require(message.records.size <= 32 && message.toByteArray().size <= 65_536)
        return JsonArray(message.records.map { decodeRecord(NfcRecordData(it.tnf, it.type, it.id, it.payload)) })
    }

    internal fun encodeRecords(records: JsonArray): List<NfcRecordData> {
        require(records.size in 1..32)
        val encoded = records.map { element ->
            val obj = element as? JsonObject ?: throw IllegalArgumentException()
            val value = obj.textArgument("value")?.takeIf { it.length <= 100_000 } ?: throw IllegalArgumentException()
            val id = if (obj.containsKey("id_b64")) decodeMetadata(obj.textArgument("id_b64") ?: throw IllegalArgumentException()) else byteArrayOf()
            require(id.size <= 255)
            when (obj.textArgument("kind")) {
                "text" -> {
                    val language = obj.textArgument("language") ?: "ru"
                    require(language.matches(Regex("[A-Za-z0-9-]{0,63}")))
                    val lang = language.toByteArray(Charsets.US_ASCII)
                    NfcRecordData(1, byteArrayOf(84), id, byteArrayOf(lang.size.toByte()) + lang + value.toByteArray(Charsets.UTF_8))
                }
                "uri" -> {
                    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException() }
                    require(uri.isAbsolute && uri.rawSchemeSpecificPart.isNotEmpty())
                    val prefix = uriPrefixes.indices.firstOrNull { it > 0 && value.startsWith(uriPrefixes[it]) } ?: 0
                    NfcRecordData(1, byteArrayOf(85), id, byteArrayOf(prefix.toByte()) + value.removePrefix(uriPrefixes[prefix]).toByteArray(Charsets.UTF_8))
                }
                "raw" -> {
                    val tnf = obj.integerArgument("tnf")?.takeIf { it in 0..5 } ?: throw IllegalArgumentException()
                    val type = decodeMetadata(obj.textArgument("type_b64") ?: throw IllegalArgumentException())
                    require(value.length <= 87_384)
                    val payload = Base64.getDecoder().decode(value)
                    require(type.size <= 255)
                    require(tnf != 0 || (type.isEmpty() && id.isEmpty() && payload.isEmpty()))
                    require(tnf != 5 || type.isEmpty())
                    require(tnf !in 1..4 || type.isNotEmpty())
                    val record = NfcRecordData(tnf.toShort(), type, id, payload)
                    if (tnf == 1 && (type.contentEquals(byteArrayOf(84)) || type.contentEquals(byteArrayOf(85)))) {
                        require(decodeRecord(record).textArgument("kind") != "raw")
                    }
                    record
                }
                else -> throw IllegalArgumentException()
            }
        }
        require(encoded.sumOf { it.payload.size.toLong() + it.type.size + it.id.size + 16 } <= 65_536)
        return encoded
    }

    internal fun decodeRecord(record: NfcRecordData): JsonObject {
        // Malformed well-known records remain lossless raw data, never misleading empty text.
        if (record.tnf.toInt() == 1) {
            try {
                if (record.type.contentEquals(byteArrayOf(84))) {
                    val value = decodeTextPayload(record.payload)
                    val languageLength = record.payload[0].toInt() and 0x3f
                    return buildJsonObject {
                        put("kind", "text"); put("value", value)
                        put("language", String(record.payload, 1, languageLength, Charsets.US_ASCII))
                        if (record.id.isNotEmpty()) put("id_b64", b64(record.id))
                    }
                }
                if (record.type.contentEquals(byteArrayOf(85))) {
                    require(record.payload.isNotEmpty())
                    val prefix = record.payload[0].toInt() and 0xff
                    require(prefix < uriPrefixes.size)
                    val value = uriPrefixes[prefix] + decodeBytes(record.payload, 1, Charsets.UTF_8)
                    require(URI(value).isAbsolute)
                    return buildJsonObject {
                        put("kind", "uri"); put("value", value)
                        if (record.id.isNotEmpty()) put("id_b64", b64(record.id))
                    }
                }
            } catch (_: Exception) {
                // Deliberately preserve type, ID and payload below; do not log tag content.
            }
        }
        return buildJsonObject {
            put("kind", "raw"); put("value", b64(record.payload)); put("tnf", record.tnf.toInt())
            put("type_b64", b64(record.type)); put("id_b64", b64(record.id))
        }
    }

    internal fun decodeTextPayload(payload: ByteArray): String {
        require(payload.isNotEmpty())
        val status = payload[0].toInt() and 0xff
        val langLength = status and 0x3f
        require(status and 0x40 == 0 && 1 + langLength <= payload.size)
        val language = String(payload, 1, langLength, Charsets.US_ASCII)
        require(language.matches(Regex("[A-Za-z0-9-]{0,63}")))
        return decodeBytes(payload, 1 + langLength, if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16)
    }

    private fun decodeBytes(bytes: ByteArray, offset: Int, charset: Charset): String =
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeMetadata(encoded: String): ByteArray {
        require(encoded.length <= 340) { "Метаданные NFC превышают 255 байт." }
        return Base64.getDecoder().decode(encoded).also { require(it.size <= 255) }
    }
}
