package me.rerere.rikkahub.skills.js

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SkillSecretsStoreSecurityTest {
    private lateinit var prefs: SharedPreferences
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before fun reset() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("skill-secret-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test fun `actual encrypted store reloads and separates skills without plaintext`() {
        val first = SkillSecretsStore(prefs) { key }
        first.set("weather", "api_key", "canary-weather-private-key")
        first.set("iv_other", "api_key", "canary-other-private-key")
        val reloaded = SkillSecretsStore(prefs) { key }
        assertEquals("canary-weather-private-key", reloaded.get("weather", "api_key"))
        assertEquals("canary-other-private-key", reloaded.get("iv_other", "api_key"))
        assertNull(reloaded.get("unknown", "api_key"))
        assertEquals(setOf("weather" to "api_key", "iv_other" to "api_key"), reloaded.list().toSet())
        assertTrue(prefs.all.values.none { it.toString().contains("canary-") })
        reloaded.removeAllForSkill("weather")
        assertNull(reloaded.get("weather", "api_key"))
        assertEquals("canary-other-private-key", reloaded.get("iv_other", "api_key"))
    }

    @Test fun `keystore failure never writes base64 plaintext fallback`() {
        val store = SkillSecretsStore(prefs) { error("key unavailable") }
        assertTrue(runCatching { store.set("weather", "api_key", "must-not-store") }.isFailure)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `legacy fallback and wrong key do not reveal plaintext`() {
        prefs.edit().putString("skill_secret_weather__api_key", "cGxhaW50ZXh0")
            .putString("skill_iv_weather__api_key", "__no_keystore__").commit()
        assertNull(SkillSecretsStore(prefs) { key }.get("weather", "api_key"))
        val store = SkillSecretsStore(prefs) { key }
        store.set("weather", "api_key", "encrypted-private-value")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(SkillSecretsStore(prefs) { otherKey }.get("weather", "api_key"))
    }

    @Test fun `double underscore ambiguity cannot cross skill namespaces`() {
        val store = SkillSecretsStore(prefs) { key }
        store.set("a_b", "key", "belongs-to-a-b")
        assertTrue(runCatching { store.set("a__b", "key", "foreign") }.isFailure)
        assertTrue(runCatching { store.get("a", "_b__key") }.isFailure)
        assertTrue(runCatching { store.removeAllForSkill("a__b") }.isFailure)
        assertEquals("belongs-to-a-b", store.get("a_b", "key"))
    }
}
