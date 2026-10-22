package me.rerere.document

import org.xmlpull.v1.XmlPullParser

/** Preserve parser behavior while checking cancellation at each parsing step. */
internal fun XmlPullParser.withCancellation(checkActive: () -> Unit): XmlPullParser {
    val delegate = this
    return object : XmlPullParser by delegate {
        override fun next(): Int { checkActive(); return delegate.next().also { checkActive() } }
        override fun nextToken(): Int { checkActive(); return delegate.nextToken().also { checkActive() } }
        override fun nextTag(): Int { checkActive(); return delegate.nextTag().also { checkActive() } }
        override fun nextText(): String { checkActive(); return delegate.nextText().also { checkActive() } }
    }
}
