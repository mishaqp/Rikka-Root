package me.rerere.rikkahub.skills.js

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunJsSecurityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `relative viewer uses current skill root and blocks traversal`() {
        val root = temporary.newFolder("first-skill")
        val script = root.resolve("scripts/index.html").also { it.parentFile.mkdirs(); it.writeText("html") }
        val safe = resolveSkillWebviewUrl("../assets/dashboard.html?view=1#top", script, root)
        assertEquals("file://${root.canonicalPath}/assets/dashboard.html?view=1#top", safe)
        assertNull(resolveSkillWebviewUrl("../../other-skill/private.html", script, root))
    }

    @Test fun `absolute private and other skill URLs cannot bypass per skill origin`() {
        val root = temporary.newFolder("first-skill")
        val script = root.resolve("index.html").also { it.writeText("html") }
        val foreign = temporary.newFolder("other-skill").resolve("private.html")
        assertNull(resolveSkillWebviewUrl(foreign.toURI().toString(), script, root))
        assertNull(resolveSkillWebviewUrl("content://app-private/credentials", script, root))
        assertNull(resolveSkillWebviewUrl("file://remote/private.html", script, root))
        assertNull(resolveSkillWebviewUrl("javascript:alert(1)", script, root))
        assertEquals("file://${root.canonicalPath}/assets/ui.html", resolveSkillWebviewUrl("file://${root.canonicalPath}/assets/ui.html", script, root))
    }

    @Test fun `secret echoed by script never enters textual or viewer metadata`() {
        val secret = "opaque-key-short"
        val result = redactSkillResultSecret(JsSkillRunner.ParsedResult(
            text = "result $secret", error = "error $secret", webviewUrl = "https://api.test/?key=$secret",
            imageBase64 = secret,
        ), secret)
        assertFalse(result.text!!.contains(secret))
        assertFalse(result.error!!.contains(secret))
        assertFalse(result.webviewUrl!!.contains(secret))
        assertNull(result.imageBase64)
        assertEquals("ordinary hash", redactSkillSecret("ordinary hash", ""))
    }
}
