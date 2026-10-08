package me.rerere.ai.util

import android.content.Context
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class KeyRoulettePrivacyTest {
    private lateinit var context: Context
    private val keys = listOf("sk-test-roulette-key-first", "sk-test-roulette-key-second", "sk-test-roulette-key-third")

    @Before
    fun prepareCache() {
        context = RuntimeEnvironment.getApplication()
        removeRouletteFiles()
    }

    @After
    fun cleanupCache() = removeRouletteFiles()

    @Test
    fun `ordinary key rotation persists no API key and survives a new provider instance`() {
        val first = KeyRoulette.lru(context)
        assertEquals(keys[0], first.next(keys.joinToString(","), "provider-one"))
        assertNoPlaintextKeys()
        assertEquals(keys[1], KeyRoulette.lru(context).next(keys.joinToString("\n"), "provider-one"))
        assertEquals(keys[2], KeyRoulette.lru(context).next(keys.joinToString(" "), "provider-one"))
        assertNoPlaintextKeys()
    }

    @Test
    fun `legacy cache migrates on initialization without resetting oldest key or other providers`() {
        val now = System.currentTimeMillis()
        legacy().writeText(Json.encodeToString(mapOf(
            "provider-one" to linkedMapOf(keys[0] to now - 1_000, keys[1] to now - 3_000, keys[2] to now - 2_000),
            "provider-two" to linkedMapOf(keys[0] to now - 4_000, keys[1] to now - 1_000),
        )))
        val roulette = KeyRoulette.lru(context)
        assertFalse("legacy plaintext must be removed before the first request", legacy().exists())
        assertNoPlaintextKeys()
        assertEquals(keys[1], roulette.next(keys.joinToString(","), "provider-one"))
        assertEquals(keys[0], roulette.next(keys.take(2).joinToString(","), "provider-two"))
        assertNoPlaintextKeys()
    }

    @Test
    fun `corrupt legacy cache is discarded without retaining plaintext`() {
        legacy().writeText("broken-json ${keys[0]}")
        val roulette = KeyRoulette.lru(context)
        assertFalse(legacy().exists())
        assertEquals(keys[0], roulette.next(keys.joinToString(","), "provider-one"))
        assertNoPlaintextKeys()
    }

    @Test
    fun `expired legacy usage retains original first unused key behavior`() {
        val expired = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1_000L
        legacy().writeText(Json.encodeToString(mapOf("provider-one" to mapOf(keys[0] to expired, keys[1] to expired))))
        assertEquals(keys[0], KeyRoulette.lru(context).next(keys.joinToString(","), "provider-one"))
        assertNoPlaintextKeys()
    }

    @Test
    fun `initializing with no configured keys still removes old plaintext`() {
        legacy().writeText(Json.encodeToString(mapOf("provider-one" to mapOf(keys[0] to System.currentTimeMillis()))))
        assertEquals("", KeyRoulette.lru(context).next("", "provider-one"))
        assertFalse(legacy().exists())
        assertNoPlaintextKeys()
    }

    @Test
    fun `interrupted upgrade with both cache versions preserves newer usage`() {
        assertEquals(keys[0], KeyRoulette.lru(context).next(keys.joinToString(","), "provider-one"))
        val now = System.currentTimeMillis()
        legacy().writeText(Json.encodeToString(mapOf("provider-one" to linkedMapOf(
            keys[0] to now - 5_000,
            keys[1] to now - 3_000,
            keys[2] to now - 2_000,
        ))))
        assertEquals(keys[1], KeyRoulette.lru(context).next(keys.joinToString(","), "provider-one"))
        assertFalse(legacy().exists())
        assertNoPlaintextKeys()
    }

    private fun legacy() = File(context.cacheDir, "lru_key_roulette.json")

    private fun assertNoPlaintextKeys() {
        val files = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("lru_key_roulette") }
        assertTrue("nonsecret persisted rotation state should remain", files.isNotEmpty())
        files.forEach { file ->
            val text = file.readText()
            keys.forEach { key -> assertFalse("API key persisted in ${file.name}", text.contains(key)) }
        }
    }

    private fun removeRouletteFiles() {
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("lru_key_roulette") }.forEach { it.delete() }
    }
}
