package me.rerere.rikkahub.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.local.evaluateJavascript
import me.rerere.rikkahub.data.ai.tools.local.parseJsResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Executes production reader JS in the existing QuickJS JVM engine. The fixture models
 * clone/removal/ancestor/style DOM operations; real Chromium and Mozilla parsing remain
 * device checks. It deliberately includes secret text and throws on native-store access.
 */
class BrowserReadSafetyTest {
    @Test fun `raw text excludes scripts forms values and hidden descendants without mutating page`() = runBlocking {
        val result = evaluate(BrowserReadSafety.rawTextScript("body", 8000)).jsonObject
        assertEquals("Public article Public link", result.getValue("text").jsonPrimitive.content)
        assertNoCanary(result)
        val original = evaluate("JSON.stringify(document.body.textContent)").jsonPrimitive.content
        assertTrue("the live document is retained", original.contains(CANARY))
    }

    @Test fun `sensitive matched roots and ancestors are refused`() = runBlocking {
        for (selector in listOf("#script", "#style", "#noscript", "#template", "#form",
            "#password", "#textarea", "#select", "#hidden", "#aria", "#css-hidden", "#form-child")) {
            for (script in listOf(BrowserReadSafety.rawTextScript(selector, 8000), BrowserReadSafety.linksScript(selector))) {
                val result = evaluate(script).jsonObject
                assertEquals(selector, "sensitive_selector", result.getValue("error").jsonPrimitive.content)
                assertNoCanary(result)
            }
        }
    }

    @Test fun `links exclude form hidden and script subtrees plus nested sensitive text`() = runBlocking {
        val result = evaluate(BrowserReadSafety.linksScript("body")).jsonObject
        assertEquals("1", result.getValue("count").jsonPrimitive.content)
        val link = result.getValue("links").jsonArray.single().jsonObject
        assertEquals("https://example.org/article", link.getValue("href").jsonPrimitive.content)
        assertEquals("Public link", link.getValue("text").jsonPrimitive.content)
        assertNoCanary(result)
    }

    @Test fun `diff snapshots use the same filtered clone`() = runBlocking {
        val result = evaluate(BrowserReadSafety.bodyTextScript()).jsonPrimitive.content
        assertEquals("Public article Public link", result)
        assertFalse(result.contains(CANARY))
    }

    @Test fun `readability receives only a filtered document clone`() = runBlocking {
        val script = """
            function Readability(doc) { this.doc = doc; }
            Readability.prototype.parse = function() {
                return {textContent:this.doc.textContent, title:'Article'};
            };
            ${ReadabilityRunner.buildReadabilityScript("")}
        """.trimIndent()
        val result = evaluate(script).jsonObject
        assertEquals("Public article Public link", result.getValue("textContent").jsonPrimitive.content)
        assertNoCanary(result)
    }

    @Test fun `missing and failed DOM readers return no raw error text`() = runBlocking {
        assertEquals("selector_not_found", evaluate(BrowserReadSafety.rawTextScript("#missing", 8000))
            .jsonObject.getValue("error").jsonPrimitive.content)
        val result = evaluate(BrowserReadSafety.rawTextScript("[invalid", 8000)).jsonObject
        assertEquals("js_failed", result.getValue("error").jsonPrimitive.content)
        assertNoCanary(result)
        assertFalse(result.containsKey("detail"))
    }

    @Test fun `style inspection failure is closed rather than falling back to original text`() = runBlocking {
        val script = "window.getComputedStyle = function() { throw Error('$CANARY'); };\n" +
            BrowserReadSafety.rawTextScript("body", 8000)
        val result = evaluate(script).jsonObject
        assertEquals("js_failed", result.getValue("error").jsonPrimitive.content)
        assertNoCanary(result)
    }

    @Test fun `malformed JS result cannot expose the raw return value`() {
        val result = parseJsResult("$CANARY invalid JSON")
        assertEquals("js_parse_failed", result.getValue("error").jsonPrimitive.content)
        assertFalse(result.containsKey("raw"))
        assertNoCanary(result)
    }

    @Test fun `known secrets and URL passwords are redacted in text values before JSON encoding`() {
        val result = BrowserReadSafety.redactResult(buildJsonObject {
            put("count", 1)
            put("links", buildJsonArray {
                add(buildJsonObject {
                    put("href", "https://person:$CANARY@example.org/page?password=$CANARY")
                    put("text", "Bearer $CANARY")
                })
            })
            put("text", "password=$CANARY\nlocal ordinary article")
            put("success", true)
        }).jsonObject
        assertEquals("1", result.getValue("count").jsonPrimitive.content)
        assertEquals("true", result.getValue("success").jsonPrimitive.content)
        assertEquals(1, result.getValue("links").jsonArray.size)
        assertNoCanary(result)
        assertTrue(result.getValue("text").jsonPrimitive.content.contains("local ordinary article"))
        assertEquals(result, Json.parseToJsonElement(result.toString()))
    }

    @Test fun `clipping and JSON selector escaping retain source behavior`() = runBlocking {
        val clipped = evaluate(BrowserReadSafety.rawTextScript("body", 6)).jsonObject
        assertEquals("Public", clipped.getValue("text").jsonPrimitive.content)
        assertEquals("true", clipped.getValue("truncated").jsonPrimitive.content)
        val injected = evaluate(BrowserReadSafety.rawTextScript("#missing'); throw Error('$CANARY');//", 8000)).jsonObject
        assertEquals("selector_not_found", injected.getValue("error").jsonPrimitive.content)
    }

    private suspend fun evaluate(script: String): JsonElement {
        val outer = Json.parseToJsonElement(evaluateJavascript("$DOM_FIXTURE\n$script", 4_000)).jsonObject
        assertFalse("QuickJS failure: $outer", outer.containsKey("error"))
        return Json.parseToJsonElement(outer.getValue("result").jsonPrimitive.content)
    }

    private fun assertNoCanary(result: JsonElement) = assertFalse(result.toString(), result.toString().contains(CANARY))

    companion object {
        private const val CANARY = "PRIVATE_CANARY_123"

        private val DOM_FIXTURE = """
            class Element {
                constructor(tag, text, attrs, children, css) {
                    this.nodeType = tag === 'document' ? 9 : 1;
                    this.tagName = tag.toUpperCase(); this.ownText = text || '';
                    this.attrs = attrs || {}; this.children = children || []; this.css = css || {};
                    this.parentElement = null;
                    for (var child of this.children) child.parentElement = this;
                }
                get textContent() { return [this.ownText].concat(this.children.map(x => x.textContent)).join(' ').trim(); }
                get href() { return this.attrs.href || ''; }
                matches(selector) {
                    if (selector === '*') return true;
                    if (selector === 'a[href]') return this.tagName === 'A' && this.attrs.href !== undefined;
                    if (selector[0] === '#') return this.attrs.id === selector.slice(1);
                    if (selector === '[hidden]') return this.attrs.hidden !== undefined;
                    if (selector === '[aria-hidden="true"]') return this.attrs['aria-hidden'] === 'true';
                    if (selector === '[invalid') throw Error('$CANARY');
                    return this.tagName.toLowerCase() === selector;
                }
                closest(selector) {
                    var current = this;
                    while (current && current.nodeType === 1) {
                        if (selector.split(',').some(x => current.matches(x))) return current;
                        current = current.parentElement;
                    }
                    return null;
                }
                querySelectorAll(selector) {
                    var result = [];
                    for (var child of this.children) {
                        if (child.matches(selector)) result.push(child);
                        result = result.concat(child.querySelectorAll(selector));
                    }
                    return result;
                }
                querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
                cloneNode(deep) { return new Element(this.tagName.toLowerCase(), this.ownText, {...this.attrs},
                    deep ? this.children.map(x => x.cloneNode(true)) : [], {...this.css}); }
                remove() {
                    if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(x => x !== this);
                    this.parentElement = null;
                }
            }
            function el(tag, text, attrs, children, css) { return new Element(tag,text,attrs,children,css); }
            var canary = '$CANARY';
            var publicLink = el('a','Public link',{href:'https://example.org/article'},[el('input',canary,{id:'password'})]);
            var form = el('form','',{id:'form'},[el('span',canary,{id:'form-child'}),
                el('a',canary,{href:'https://example.org/private'}), el('textarea',canary,{id:'textarea'}),
                el('select','',{id:'select'},[el('option',canary)])]);
            var body = el('body','',{},[el('article','Public article'), publicLink,
                el('script',canary,{id:'script'}), el('style',canary,{id:'style'}),
                el('noscript',canary,{id:'noscript'}), el('template',canary,{id:'template'}), form,
                el('div',canary,{id:'hidden',hidden:''}), el('div',canary,{id:'aria','aria-hidden':'true'}),
                el('div',canary,{id:'css-hidden'},[],{display:'none'})]);
            globalThis.document = el('document','',{},[body]); document.body = body;
            Object.defineProperty(document, 'cookie', {get:function() {throw Error('cookie '+canary);}});
            globalThis.localStorage = {getItem:function() {throw Error('localStorage '+canary);}};
            globalThis.window = {getComputedStyle:function(node) {
                return {display:node.css.display || 'block', visibility:node.css.visibility || 'visible'};
            }};
        """.trimIndent()
    }
}
