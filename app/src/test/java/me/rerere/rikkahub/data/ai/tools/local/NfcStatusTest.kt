package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.*
import org.junit.Test

class NfcStatusTest {
    @Test fun readyRequiresHardwareEnabledForegroundAndNoOtherSession() {
        fun ready(available: Boolean = true, enabled: Boolean = true, foreground: Boolean = true, busy: Boolean = false) =
            nfcStatusPayload(available, enabled, foreground, busy)["ready_to_read"]!!.jsonPrimitive.boolean
        assertTrue(ready())
        assertFalse(ready(available = false))
        assertFalse(ready(enabled = false))
        assertFalse(ready(foreground = false))
        assertFalse(ready(busy = true))
    }

    @Test fun statusDoesNotClaimOrStartAnNfcSession() = runBlocking {
        val buffer = NfcResultBuffer()
        assertFalse(buffer.isBusy())
        val session = buffer.register("existing", false, 30)
        assertTrue(buffer.isBusy())
        assertTrue(nfcStatusPayload(true, true, true, buffer.isBusy())["session_active"]!!.jsonPrimitive.boolean)
        assertTrue(buffer.claim("existing"))
        buffer.cancel("existing")
        session.await()
        assertFalse(buffer.isBusy())
    }
}
