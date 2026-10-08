package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmsDeliveryTest {
    @Test fun partialOrUnknownSubmissionTellsTheModelNeverToRetryAutomatically() {
        val json=smsOutcomeJson(SmsDeliveryResult(1,1,emptyMap()),2)
        assertEquals("false",json["success"].toString())
        assertEquals("false",json["automatic_retry_allowed"].toString())
        assertEquals("false",json["recipient_delivery_confirmed"].toString())
        assertTrue(json["error"].toString().contains("Не отправляйте"))
    }
    @Test fun onlyAllConfirmedPartsMeanSuccessAndDuplicatesAreIgnored() = runBlocking {
        val buffer = SmsDeliveryBuffer()
        val receipt = buffer.register("one",3)
        assertTrue(buffer.acknowledge("one",0,-1))
        assertFalse(buffer.acknowledge("one",0,-1))
        assertEquals(2,receipt.snapshot().unknownParts)
        assertFalse(receipt.snapshot().success)
        assertTrue(buffer.acknowledge("one",2,-1))
        assertTrue(buffer.acknowledge("one",1,-1))
        assertTrue(receipt.await().success)
    }

    @Test fun failedPartsAndUnconfirmedPartsNeverReportSentSuccess() {
        val buffer = SmsDeliveryBuffer()
        val receipt = buffer.register("one",3)
        buffer.acknowledge("one",0,-1); buffer.acknowledge("one",1,2)
        val partial=receipt.snapshot()
        assertEquals(1,partial.sentParts); assertEquals(1,partial.unknownParts); assertEquals(mapOf(1 to 2),partial.failedParts)
        assertFalse(partial.success)
    }

    @Test fun lateForeignAndInvalidPartCallbacksCannotAffectAnotherSms() {
        val buffer = SmsDeliveryBuffer()
        buffer.register("old",1); buffer.remove("old")
        val current = buffer.register("current",1)
        assertFalse(buffer.acknowledge("old",0,-1)); assertFalse(buffer.acknowledge("unknown",0,-1))
        assertFalse(buffer.acknowledge("current",-1,-1)); assertFalse(buffer.acknowledge("current",1,-1))
        assertEquals(1,current.snapshot().unknownParts)
    }
}
