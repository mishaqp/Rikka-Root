package me.rerere.rikkahub.skills.js

import androidx.webkit.WebMessageCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillWebViewIsolationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `same directory name in different stores cannot share browser data`() {
        val first = temporary.newFolder("store-one", "mood-tracker")
        val second = temporary.newFolder("store-two", "mood-tracker")
        assertNotEquals(SkillWebViewProfile.profileName(first), SkillWebViewProfile.profileName(second))
        assertNotEquals("Default", SkillWebViewProfile.profileName(first))
        assertEquals(SkillWebViewProfile.profileName(first), SkillWebViewProfile.profileName(first.resolve(".")))
    }

    @Test fun `unsupported profiles fail closed without touching default profile`() {
        val root = temporary.newFolder("skill")
        var configured = false
        var cookiesTouched = false
        assertFalse(configureIsolatedSkillProfile(
            skillRoot = root,
            isSupported = { false },
            setProfile = { configured = true },
            disableThirdPartyCookies = { cookiesTouched = true },
        ))
        assertFalse(configured)
        assertFalse(cookiesTouched)
    }

    @Test fun `cookie policy is applied only after successful profile isolation`() {
        val root = temporary.newFolder("skill")
        val steps = mutableListOf<String>()
        assertTrue(configureIsolatedSkillProfile(
            skillRoot = root,
            isSupported = { true },
            setProfile = { steps += it },
            disableThirdPartyCookies = { steps += "third-party-cookies-disabled" },
        ))
        assertEquals(listOf(SkillWebViewProfile.profileName(root), "third-party-cookies-disabled"), steps)
        steps.clear()
        assertFalse(configureIsolatedSkillProfile(
            skillRoot = root,
            isSupported = { true },
            setProfile = { throw UnsupportedOperationException() },
            disableThirdPartyCookies = { steps += "unsafe-cookie-manager" },
        ))
        assertTrue(steps.isEmpty())
        assertFalse(configureIsolatedSkillProfile(
            skillRoot = root,
            isSupported = { true },
            setProfile = {},
            disableThirdPartyCookies = { throw UnsupportedOperationException() },
        ))
    }

    @Test fun `only HTTPS own-origin main frame can submit result to AI`() {
        val host = JsSkillRunner.skillOriginHost("mood-tracker")
        assertTrue(isSkillBridgeOriginAllowed("https://$host", true, host))
        assertTrue(isSkillBridgeOriginAllowed("https://$host:443", true, host))
        assertFalse(isSkillBridgeOriginAllowed("https://$host", false, host))
        assertFalse(isSkillBridgeOriginAllowed("https://logged-in.example", true, host))
        assertFalse(isSkillBridgeOriginAllowed("https://${JsSkillRunner.skillOriginHost("other-skill")}", true, host))
        assertFalse(isSkillBridgeOriginAllowed("http://$host", true, host))
        assertFalse(isSkillBridgeOriginAllowed("https://$host:8443", true, host))
        assertFalse(isSkillBridgeOriginAllowed("https://$host.attacker.example", true, host))
        assertFalse(isSkillBridgeOriginAllowed("https://$host@attacker.example", true, host))
        assertFalse(isSkillBridgeOriginAllowed("https://attacker.example@$host", true, host))
        assertFalse(isSkillBridgeOriginAllowed("data:text/html,<script>bridge()</script>", true, host))
        assertFalse(isSkillBridgeOriginAllowed("null", true, host))
    }

    @Test fun `binary JS message cannot throw in native result listener`() {
        assertNull(skillBridgeResultText(WebMessageCompat(byteArrayOf(1, 2, 3))))
        assertEquals("{\"result\":\"ok\"}", skillBridgeResultText(WebMessageCompat("{\"result\":\"ok\"}")))
    }
}
