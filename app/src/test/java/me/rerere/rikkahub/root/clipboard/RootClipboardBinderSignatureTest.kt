package me.rerere.rikkahub.root.clipboard

import android.app.Application
import android.content.ClipData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RootClipboardBinderSignatureTest {
    private fun arguments(setter: Boolean, vararg tail: Class<*>): Array<Any?> {
        val types = if (setter) arrayOf(ClipData::class.java, String::class.java, *tail)
            else arrayOf(String::class.java, *tail)
        return RootClipboardBridge.clipboardArguments(types, null, setter, 7)
    }
    @Test fun `legacy signatures contain calling package only`() {
        assertArrayEquals(arrayOf<Any?>("com.android.shell"), arguments(false))
        assertArrayEquals(arrayOf<Any?>(null, "com.android.shell"), arguments(true))
    }
    @Test fun `user-aware signatures retain intended profile`() {
        assertArrayEquals(arrayOf<Any?>("com.android.shell", 7), arguments(false, Int::class.javaPrimitiveType!!))
    }
    @Test fun `attribution-aware signatures keep null attribution and intended profile`() {
        assertArrayEquals(arrayOf<Any?>("com.android.shell", null, 7), arguments(false, String::class.java, Int::class.javaPrimitiveType!!))
    }
    @Test fun `device-aware signatures select primary device zero`() {
        assertArrayEquals(arrayOf<Any?>(null, "com.android.shell", null, 7, 0),
            arguments(true, String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!))
    }
    @Test fun `unrecognized OEM signatures fail instead of guessing Binder arguments`() {
        try {
            arguments(false, Boolean::class.javaPrimitiveType!!)
            fail("unsupported signature must fail before a Binder call")
        } catch (_: IllegalArgumentException) { }
    }
}
