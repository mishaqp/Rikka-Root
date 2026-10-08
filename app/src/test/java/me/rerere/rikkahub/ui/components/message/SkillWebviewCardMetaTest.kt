package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [hasSkillWebviewMeta] - the predicate used by [ChatMessageToolStep] to
 * decide whether a `run_js` tool's nested output Text parts should be rendered as a
 * skill webview card. It must agree with the extraction logic actually used to render
 * the card (see #38: nested webview parts never rendered because nothing checked them).
 */
class SkillWebviewCardMetaTest {

    @Test
    fun `well-formed webview metadata returns true`() {
        val part = UIMessagePart.Text(
            text = "Open the piano",
            metadata = buildJsonObject {
                put("rikkahub.webview", buildJsonObject {
                    put("url", "https://example.com/piano")
                    put("iframe", true)
                    put("source", "js_skill:virtual-piano")
                })
            },
        )
        assertTrue(part.hasSkillWebviewMeta())
    }

    @Test
    fun `no metadata returns false`() {
        val part = UIMessagePart.Text(text = "plain output")
        assertFalse(part.hasSkillWebviewMeta())
    }

    @Test
    fun `unrelated metadata returns false`() {
        val part = UIMessagePart.Text(
            text = "plain output",
            metadata = buildJsonObject {
                put("some.other.key", JsonPrimitive("value"))
            },
        )
        assertFalse(part.hasSkillWebviewMeta())
    }

    @Test
    fun `malformed webview block missing url returns false`() {
        val part = UIMessagePart.Text(
            text = "broken",
            metadata = buildJsonObject {
                put("rikkahub.webview", buildJsonObject {
                    put("iframe", true)
                })
            },
        )
        assertFalse(part.hasSkillWebviewMeta())
    }

    @Test
    fun `webview block with blank url returns false`() {
        val part = UIMessagePart.Text(
            text = "broken",
            metadata = buildJsonObject {
                put("rikkahub.webview", buildJsonObject {
                    put("url", "")
                })
            },
        )
        assertFalse(part.hasSkillWebviewMeta())
    }
    @Test
    fun `non-object metadata cannot crash the message renderer`() {
        val part = UIMessagePart.Text(
            text = "broken",
            metadata = buildJsonObject { put("rikkahub.webview", JsonPrimitive("file:///data/data/private")) },
        )
        assertFalse(part.hasSkillWebviewMeta())
    }

    @Test
    fun `object url cannot crash the message renderer`() {
        val part = UIMessagePart.Text(
            text = "broken",
            metadata = buildJsonObject {
                put("rikkahub.webview", buildJsonObject { put("url", buildJsonObject {}) })
            },
        )
        assertFalse(part.hasSkillWebviewMeta())
    }

    @Test
    fun `local URLs require scoped viewer classification`() {
        assertTrue(isLocalWebviewUrl("file:///data/data/private/file.html"))
        assertTrue(isLocalWebviewUrl("CONTENT://private-provider/document/1"))
        assertFalse(isLocalWebviewUrl("https://example.com/file:/test"))
    }

}
