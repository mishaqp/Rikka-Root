package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.reliability.SecretRedactor

/**
 * Required privacy adapter around Agent's fixed DOM readers. Website login remains in
 * the native profile; tools read a filtered clone, never form values or script/storage
 * payloads. This is not a guarantee against a website rendering a secret as public text.
 */
internal object BrowserReadSafety {
    private val webCredentials = Regex("""(?i)(https?://)[^/@\s]*:[^/@\s]*@""")

    /** Redact text values before JSON encoding, preserving the source result schema. */
    fun redactResult(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (_, item) -> redactResult(item) })
        is JsonArray -> JsonArray(value.map(::redactResult))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(
            SecretRedactor.redact(webCredentials.replace(value.content, "$1[redacted]@"))
        ) else value
    }

    val script = """
        var rikkaSensitiveNodes = 'script,style,noscript,template,form,input,textarea,select,[hidden],[aria-hidden="true"]';
        function rikkaSensitiveElement(node) {
            if (!node || node.nodeType !== 1) return false;
            if (node.closest(rikkaSensitiveNodes)) return true;
            var current = node;
            while (current && current.nodeType === 1) {
                var computed = window.getComputedStyle(current);
                if (computed.display === 'none' || computed.visibility === 'hidden' || computed.visibility === 'collapse') return true;
                current = current.parentElement;
            }
            return false;
        }
        function rikkaSafeClone(node) {
            if (!node || rikkaSensitiveElement(node)) return null;
            var copy = node.cloneNode(true);
            var originals = node.querySelectorAll('*');
            var copies = copy.querySelectorAll('*');
            for (var i = originals.length - 1; i >= 0; i--) {
                if (rikkaSensitiveElement(originals[i])) copies[i].remove();
            }
            return copy;
        }
        function rikkaSafeText(node) {
            var copy = rikkaSafeClone(node);
            return copy ? (copy.textContent || '').replace(/\s+/g, ' ').trim() : null;
        }
    """.trimIndent()

    /** Source raw-reader envelope, with the same cap and an explicit sensitive-root refusal. */
    fun rawTextScript(selector: String, maxChars: Int): String = """(function(){
        try {
            $script
            var el = document.querySelector(${JsonPrimitive(selector)});
            if (!el) return JSON.stringify({error:'selector_not_found', selector:${JsonPrimitive(selector)}});
            var t = rikkaSafeText(el);
            if (t === null) return JSON.stringify({error:'sensitive_selector'});
            var truncated = false;
            if (t.length > $maxChars) { t = t.substring(0, $maxChars); truncated = true; }
            return JSON.stringify({text:t, truncated:truncated});
        } catch(e) { return JSON.stringify({error:'js_failed'}); }
    })()"""

    fun bodyTextScript(): String = """(function(){
        try {
            $script
            return JSON.stringify(rikkaSafeText(document.body) || '');
        } catch(e) { return JSON.stringify(''); }
    })()"""

    /** Source links envelope, after removing the same sensitive subtrees as text/diffs. */
    fun linksScript(selector: String): String = """(function(){
        try {
            $script
            var root = document.querySelector(${JsonPrimitive(selector)});
            if (!root) return JSON.stringify({error:'selector_not_found', selector:${JsonPrimitive(selector)}});
            root = rikkaSafeClone(root);
            if (!root) return JSON.stringify({error:'sensitive_selector'});
            var anchors = root.querySelectorAll('a[href]');
            var out = [];
            for (var i=0; i<anchors.length && out.length<100; i++) {
                var a = anchors[i];
                var href = a.href || '';
                var text = (a.textContent || '').replace(/\s+/g,' ').trim();
                if (text.length>200) text = text.substring(0,200);
                out.push({href:href, text:text});
            }
            return JSON.stringify({links:out, count:out.length});
        } catch(e) { return JSON.stringify({error:'js_failed'}); }
    })()"""
}
