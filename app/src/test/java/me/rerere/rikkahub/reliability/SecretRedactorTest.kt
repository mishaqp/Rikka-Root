package me.rerere.rikkahub.reliability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug-report ZIP includes a logcat dump that goes through [SecretRedactor]. The
 * patterns are aggressive on false positives — a token-shaped string that gets blanked
 * by mistake is just lost noise; a token that slips through is a refund event.
 */
class SecretRedactorTest {

    @Test fun `Telegram bot tokens are redacted`() {
        val input = "TelegramBotService: posting with token 1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ_abc-def\n"
        val out = SecretRedactor.redact(input)
        assertFalse(out.contains("1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ_abc-def"))
        assertTrue(out.contains("[redacted-telegram-token]"))
    }

    @Test fun `Authorization header is redacted`() {
        val input = "Sending Authorization: Bearer abc.def.ghi.somelongthing\n"
        val out = SecretRedactor.redact(input)
        assertFalse(out.contains("abc.def.ghi.somelongthing"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test fun `X-Api-Key header is redacted case-insensitively`() {
        val input1 = "X-Api-Key: sk-proj-abcdefghijklmnopqrstuvwxyz123456\n"
        val input2 = "x-api-key=sk-proj-abcdefghijklmnopqrstuvwxyz123456\n"
        for (input in listOf(input1, input2)) {
            val out = SecretRedactor.redact(input)
            assertFalse(out.contains("sk-proj-abcdefghijklmnopqrstuvwxyz123456"))
        }
    }

    @Test fun `long hex strings are redacted`() {
        val input = "Saved fingerprint: a3f2c8d4e5b6a7c8d9e0f1a2b3c4d5e6\n"
        val out = SecretRedactor.redact(input)
        assertTrue(out.contains("[redacted-hex]"))
    }

    @Test fun `short hex strings pass through`() {
        val input = "Color: #ff0000\n"
        val out = SecretRedactor.redact(input)
        assertTrue(out.contains("ff0000"))
    }

    @Test fun `ssh url with embedded creds gets redacted`() {
        val input = "Connecting ssh://username:secret@10.0.0.5:22/\n"
        val out = SecretRedactor.redact(input)
        assertFalse(out.contains("username:secret@"))
        assertTrue(out.contains("ssh://[redacted]@"))
    }

    @Test fun `Cookie header is redacted`() {
        val input = "Cookie: sessionid=abcd1234efgh5678ijkl\n"
        val out = SecretRedactor.redact(input)
        assertTrue(out.contains("[redacted]"))
    }

    @Test fun `redactor is idempotent on already-redacted text`() {
        val once = SecretRedactor.redact("Authorization: Bearer abcdef.ghijkl.mnopqr.stuvwx")
        val twice = SecretRedactor.redact(once)
        // Running twice should yield identical output (no infinite-redact cycle, no
        // extra mangling of "[redacted]" markers).
        // Note: `[redacted]` itself is short alphanumeric; should not get re-matched
        // by any pattern.
        assertTrue(twice.contains("[redacted]"))
    }

    @Test fun `provider keys outside headers are redacted completely`() {
        val secrets = listOf("sk-proj-ABCDEFGHIJKLMNOPQRSTUVWXYZ123456_suffix", "gsk_synthetic-key", "xai-synthetic_key", "tp-synthetic_key", "AIzaSyAbCdEfGh-IjKlMn_OpQrStUvWxYz123456")
        for (secret in secrets) assertFalse("Key leaked: $secret", SecretRedactor.redact("failure: $secret").contains(secret))
    }
    @Test fun `standalone Bearer and password assignments are redacted`() {
        for ((text, secret) in listOf("Bearer synthetic.jwt.secret" to "synthetic.jwt.secret", "password=synthetic-secret" to "synthetic-secret", "{\"password\":\"two words secret\"}" to "two words secret", "{\"apiKey\":\"synthetic-secret\"}" to "synthetic-secret"))
            assertFalse(SecretRedactor.redact(text).contains(secret))
    }
    @Test fun `provider headers and query keys are redacted`() {
        for (header in listOf("x-goog-api-key", "xi-api-key", "api-key", "X-Subscription-Token"))
            assertFalse(SecretRedactor.redact("$header: short-key").contains("short-key"))
        assertFalse(SecretRedactor.redact("https://api.test/?key=short-key&mode=1").contains("short-key"))
    }
    @Test fun `complete PEM private key is redacted including short final line`() {
        val input = "-----BEGIN PRIVATE KEY-----\nABCDEFGHIJKLMNOPQRSTUVWXYZ123456\nshort-secret\n-----END PRIVATE KEY-----"
        val out = SecretRedactor.redact(input)
        assertFalse(out.contains("short-secret")); assertFalse(out.contains("BEGIN PRIVATE KEY"))
    }
    @Test fun `provider additions remain idempotent`() {
        val raw = "api-key: short-key\npassword=short-password\n{\"apiKey\":\"short-key\"}"
        val once = SecretRedactor.redact(raw)
        org.junit.Assert.assertEquals(once, SecretRedactor.redact(once))
    }
}
