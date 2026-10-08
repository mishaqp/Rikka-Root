package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class NfcNdefCodecTest {
    private fun records(source: String) = Json.parseToJsonElement(source) as JsonArray

    @Test fun russianTextAndAbsoluteUrisRoundTripThroughWireRecords() {
        val encoded = NfcNdefCodec.encodeRecords(records("[{\"kind\":\"text\",\"value\":\"Привет\"},{\"kind\":\"uri\",\"value\":\"https://example.com/метка\"}]"))
        assertEquals(2, encoded.size)
        assertEquals("Привет", NfcNdefCodec.decodeRecord(encoded[0]).textArgument("value"))
        assertEquals("https://example.com/метка", NfcNdefCodec.decodeRecord(encoded[1]).textArgument("value"))
    }

    @Test fun unknownRecordsPreserveTnfTypePayloadAndId() {
        val raw = NfcRecordData(4, "example.com:kind".toByteArray(), byteArrayOf(10, 20), byteArrayOf(0, -1, 3))
        val decoded = NfcNdefCodec.decodeRecord(raw)
        val encoded = NfcNdefCodec.encodeRecords(JsonArray(listOf(decoded))).single()
        assertEquals(raw.tnf, encoded.tnf)
        assertArrayEquals(raw.type, encoded.type)
        assertArrayEquals(raw.id, encoded.id)
        assertArrayEquals(raw.payload, encoded.payload)
    }

    @Test fun malformedTextAndUriPayloadsRemainLosslessRawRecords() {
        val badText = NfcRecordData(1, byteArrayOf(84), byteArrayOf(), byteArrayOf(63, 1))
        val badUri = NfcRecordData(1, byteArrayOf(85), byteArrayOf(), byteArrayOf(-1, 1))
        assertEquals("raw", NfcNdefCodec.decodeRecord(badText).textArgument("kind"))
        assertEquals("raw", NfcNdefCodec.decodeRecord(badUri).textArgument("kind"))
    }

    @Test fun utf16TextAndLanguagePrefixAreDecodedCorrectly() {
        val payload = byteArrayOf(0x82.toByte(), 101, 110) + "Привет".toByteArray(Charsets.UTF_16)
        assertEquals("Привет", NfcNdefCodec.decodeTextPayload(payload))
    }

    @Test fun invalidOrOversizedRecordsFailBeforeOpeningTheReader() {
        listOf("[]", "[{\"kind\":\"uri\",\"value\":\"relative/path\"}]",
            "[{\"kind\":\"raw\",\"value\":\"!\",\"tnf\":4,\"type_b64\":\"YQ==\"}]",
            "[{\"kind\":\"raw\",\"value\":\"YQ==\",\"tnf\":6,\"type_b64\":\"YQ==\"}]",
            "[{\"kind\":\"raw\",\"value\":\"YQ==\",\"tnf\":0,\"type_b64\":\"\"}]",
            "[{\"kind\":\"text\",\"value\":\"" + "я".repeat(40_000) + "\"}]")
            .forEach { source -> assertThrows(IllegalArgumentException::class.java) { NfcNdefCodec.encodeRecords(records(source)) } }
    }

    @Test fun malformedKnownRtdCannotBypassValidationAsRaw() {
        listOf("[{\"kind\":\"raw\",\"tnf\":1,\"type_b64\":\"VA==\",\"value\":\"PwE=\"}]",
            "[{\"kind\":\"raw\",\"tnf\":1,\"type_b64\":\"VQ==\",\"value\":\"/wE=\"}]")
            .forEach { source -> assertThrows(IllegalArgumentException::class.java) { NfcNdefCodec.encodeRecords(records(source)) } }
    }

    @Test fun invalidMetadataIsRejectedBeforeDecodingUnboundedBase64() {
        listOf("id_b64", "type_b64").forEach { field ->
            val source = "[{\"kind\":\"raw\",\"tnf\":4,\"value\":\"YQ==\",\"type_b64\":\"YQ==\",\"$field\":\"" + "A".repeat(344) + "!\"}]"
            val error = assertThrows(IllegalArgumentException::class.java) { NfcNdefCodec.encodeRecords(records(source)) }
            assertEquals("Метаданные NFC превышают 255 байт.", error.message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NfcNdefCodec.encodeRecords(records("[{\"kind\":\"text\",\"value\":\"тест\",\"id_b64\":17}]"))
        }
    }
}
