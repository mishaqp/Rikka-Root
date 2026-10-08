package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PersonalToolArgumentsTest {
    @Test fun approximateGpsWorksWithoutNetworkOnAndroid12AndKeepsLegacyFineRequirement() {
        assertEquals(listOf("gps"), locationProviders("high",false,31) { it == "gps" })
        assertEquals(listOf("network","gps"), locationProviders("balanced",false,37) { true })
        assertTrue(locationProviders("high",false,30) { it == "gps" }.isEmpty())
        assertEquals(listOf("gps"), locationProviders("high",true,26) { it == "gps" })
    }
    private fun args(source: String) = Json.parseToJsonElement(source).jsonObject
    @Test fun booleansNeverAcceptStringOrNumericCoercion() {
        assertTrue(personalBoolean(args("{}"), "flag", true))
        assertFalse(personalBoolean(args("{\"flag\":false}"), "flag", true))
        listOf("{\"flag\":\"true\"}", "{\"flag\":1}", "{\"flag\":null}").forEach {
            assertThrows(IllegalArgumentException::class.java) { personalBoolean(args(it), "flag", false) }
        }
    }
    @Test fun limitsAndDatesRejectWrongTypesBeforeProviderAccess() {
        assertEquals(20, personalLimit(args("{}"), 20, 200))
        assertEquals(200, personalLimit(args("{\"limit\":200}"), 20, 200))
        listOf("{\"limit\":0}", "{\"limit\":201}", "{\"limit\":\"5\"}", "{\"limit\":false}", "{\"since_ms\":-1}", "{\"since_ms\":\"123\"}")
            .forEach { source -> assertThrows(source, IllegalArgumentException::class.java) { val p=args(source); personalLimit(p,20,200); personalSince(p) } }
        assertEquals(123L, personalSince(args("{\"since_ms\":123}")))
    }

    @Test fun smsRecipientIsOnePhoneNumberAndBodyIsBounded() {
        assertEquals("+79991234567", validateSmsArguments(args("{\"recipient\":\"+7 (999) 123-45-67\",\"body\":\"Привет\"}")).recipient)
        listOf("", "--()", "+", "12", "123;456", "123,456", "abc", "1".repeat(21)).forEach { number ->
            assertThrows(number, IllegalArgumentException::class.java) { validateSmsArguments(args(Json.encodeToString(mapOf("recipient" to number, "body" to "тест")))) }
        }
        assertThrows(IllegalArgumentException::class.java) { validateSmsArguments(args(Json.encodeToString(mapOf("recipient" to "12345", "body" to "a".repeat(4097))))) }
    }

    @Test fun literalSmsSearchEscapesWildcardsAndBackslashes() {
        assertEquals("%a\\%b\\_c\\\\d%", smsSubstringSelection("a%b_c\\d"))
    }

    @Test fun captureDurationsHaveHardBoundsAndDoNotSilentlyClamp() {
        assertEquals(10000, recordingDuration(args("{}")))
        assertEquals(300000, recordingDuration(args("{\"duration_ms\":300000}")))
        listOf("{\"duration_ms\":999}", "{\"duration_ms\":300001}", "{\"duration_ms\":\"1000\"}")
            .forEach { assertThrows(IllegalArgumentException::class.java) { recordingDuration(args(it)) } }
        assertEquals(30000, speechTimeout(args("{}")))
        assertThrows(IllegalArgumentException::class.java) { speechTimeout(args("{\"timeout_ms\":60001}")) }
    }

    @Test fun locationAccuracyAndTimeoutAreExplicitAndCacheNeverClaimsFutureFreshness() {
        assertEquals("balanced", locationArguments(args("{}")).accuracy)
        assertEquals(60000, locationArguments(args("{\"accuracy\":\"high\",\"timeout_ms\":60000}")).timeoutMs)
        listOf("{\"accuracy\":\"fast\"}", "{\"timeout_ms\":0}", "{\"timeout_ms\":\"1000\"}")
            .forEach { assertThrows(IllegalArgumentException::class.java) { locationArguments(args(it)) } }
        assertEquals(1000L, locationCachedAgeMs(99000,100000))
        assertNull(locationCachedAgeMs(200000,100000))
        assertNull(locationCachedAgeMs(-1,100000))
    }

    @Test fun speechAcceptsValidLanguageTagsButRejectsOversizedOrMalformedInput() {
        assertEquals("ru-RU", speechLanguage("ru-ru"))
        listOf("", "ru_RU", "en<script>", "a".repeat(81)).forEach { assertThrows(IllegalArgumentException::class.java) { speechLanguage(it) } }
    }
}
