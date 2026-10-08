package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SmsDeliveryReceiptTest {
    @Test fun allSentPartsReportSendingWithoutClaimingRecipientDelivery() {
        val receipt = SmsDeliveryBuffer().register("sent", 2)
        receipt.acknowledge(1, -1)
        receipt.acknowledge(0, -1)

        val json = smsOutcomeJson(receipt.snapshot(), 2)
        assertEquals("sent", json["status"]?.jsonPrimitive?.content)
        assertEquals("false", json["recipient_delivery_confirmed"].toString())
        assertEquals("false", json["automatic_retry_allowed"].toString())
    }

    @Test fun androidFailureKeepsSuccessfulPartsAndReportsItsExactResultCode() {
        val receipt = SmsDeliveryBuffer().register("failure", 3)
        receipt.acknowledge(0, -1)
        receipt.acknowledge(1, 2)

        val json = smsOutcomeJson(receipt.snapshot(), 3)
        assertEquals("android_error", json["status"]?.jsonPrimitive?.content)
        assertEquals("1", json["parts_sent"].toString())
        assertEquals("1", json["parts_unknown"].toString())
        val failure = json.getValue("failed_parts").jsonArray.single().jsonObject
        assertEquals("2", failure.getValue("part").toString())
        assertEquals("2", failure.getValue("android_result_code").toString())
        assertEquals("false", json["automatic_retry_allowed"].toString())
    }

    @Test fun missingSentCallbacksReportTimeoutInsteadOfGenericUnknown() = runBlocking {
        val receipt = SmsDeliveryBuffer().register("timeout", 2)
        receipt.acknowledge(0, -1)
        assertNull(withTimeoutOrNull(5) { receipt.await() })

        val json = smsOutcomeJson(receipt.snapshot(), 2)
        assertEquals("timeout", json["status"]?.jsonPrimitive?.content)
        assertEquals("1", json["parts_sent"].toString())
        assertEquals("1", json["parts_unknown"].toString())
        assertEquals("false", json["success"].toString())
        assertTrue(json["error"].toString().contains("Не отправляйте"))
    }

    @Test fun duplicateSuccessCannotReplaceFailureOrCompleteAnotherPart() = runBlocking {
        val receipt = SmsDeliveryBuffer().register("duplicate", 2)
        assertTrue(receipt.acknowledge(0, 1))
        assertFalse(receipt.acknowledge(0, -1))
        assertNull(withTimeoutOrNull(5) { receipt.await() })
        assertEquals(mapOf(0 to 1), receipt.snapshot().failedParts)
        assertEquals(1, receipt.snapshot().unknownParts)

        assertTrue(receipt.acknowledge(1, -1))
        val complete = receipt.await()
        assertFalse(complete.success)
        assertEquals(1, complete.sentParts)
        assertEquals(0, complete.unknownParts)
    }

    @Test fun removedRequestAndOutOfRangePartsCannotCompleteActiveReceipt() {
        val buffer = SmsDeliveryBuffer()
        buffer.register("finished", 1)
        buffer.remove("finished")
        val active = buffer.register("active", 1)
        assertFalse(buffer.acknowledge("finished", 0, -1))
        assertFalse(buffer.acknowledge("active", -1, -1))
        assertFalse(buffer.acknowledge("active", 1, -1))
        assertEquals(1, active.snapshot().unknownParts)
    }

    @Test fun deliveryCallbackCannotReplaceMissingSentCallback() {
        val receipt = SmsDeliveryBuffer().register("delivery-first", 1)
        assertTrue(receipt.acknowledgeDelivery(0, -1, 0, "3gpp"))

        val json = smsOutcomeJson(receipt.snapshot(timedOut = true), 1)
        assertEquals("timeout", json["status"]?.jsonPrimitive?.content)
        assertEquals("false", json["success"].toString())
        assertEquals("0", json["parts_sent"].toString())
        assertEquals("true", json["recipient_delivery_confirmed"].toString())
    }

    @Test fun deliveryBeforeSendingReturnsCurrentStateAfterBothStagesFinish() = runBlocking {
        val receipt = SmsDeliveryBuffer().register("out-of-order", 1)
        receipt.acknowledgeDelivery(0, -1, 0, "3gpp")
        receipt.acknowledge(0, -1)

        val current = receipt.awaitDelivery()
        assertEquals(1, current.sentParts)
        assertEquals(0, current.unknownParts)
        assertTrue(current.recipientDeliveryConfirmed)
    }

    @Test fun pendingGsmDeliveryCanBecomeDeliveredButFinalDuplicatesAreIgnored() = runBlocking {
        val receipt = SmsDeliveryBuffer().register("delivery", 1)
        receipt.acknowledge(0, -1)
        assertTrue(receipt.acknowledgeDelivery(0, -1, 0x20, "3gpp"))
        assertNull(withTimeoutOrNull(5) { receipt.awaitDelivery() })
        assertFalse(receipt.snapshot().recipientDeliveryConfirmed)

        assertTrue(receipt.acknowledgeDelivery(0, -1, 0, "3gpp"))
        assertFalse(receipt.acknowledgeDelivery(0, -1, 0x40, "3gpp"))
        assertTrue(receipt.awaitDelivery().recipientDeliveryConfirmed)
    }

    @Test fun cdmaNetworkAcceptanceIsDifferentFromCdmaDelivery() {
        val receipt = SmsDeliveryBuffer().register("cdma", 1)
        receipt.acknowledge(0, -1)
        receipt.acknowledgeDelivery(0, -1, 0, "3gpp2")
        assertFalse(receipt.snapshot().recipientDeliveryConfirmed)
        receipt.acknowledgeDelivery(0, -1, 2 shl 16, "3gpp2")
        assertTrue(receipt.snapshot().recipientDeliveryConfirmed)
    }

    @Test fun missingPduAndForwardedGsmReportDoNotClaimRecipientDelivery() {
        for (report in listOf(null, 1, 2)) {
            val receipt = SmsDeliveryBuffer().register("unconfirmed-$report", 1)
            receipt.acknowledge(0, -1)
            receipt.acknowledgeDelivery(0, -1, report, "3gpp")
            assertFalse(receipt.snapshot().recipientDeliveryConfirmed)
            assertTrue(receipt.snapshot().success)
        }
        val missingFormat = SmsDeliveryBuffer().register("missing-format", 1)
        missingFormat.acknowledge(0, -1)
        missingFormat.acknowledgeDelivery(0, -1, 0, null)
        assertFalse(missingFormat.snapshot().recipientDeliveryConfirmed)
    }

    @Test fun deliveryErrorDoesNotUndoConfirmedSendingAndRetainsReportCode() {
        val receipt = SmsDeliveryBuffer().register("delivery-error", 2)
        receipt.acknowledge(0, -1)
        receipt.acknowledge(1, -1)
        receipt.acknowledgeDelivery(0, -1, 0, "3gpp")
        receipt.acknowledgeDelivery(1, -1, 0x40, "3gpp")

        val json = smsOutcomeJson(receipt.snapshot(), 2)
        assertEquals("sent", json["status"]?.jsonPrimitive?.content)
        assertEquals("true", json["success"].toString())
        assertEquals("false", json["recipient_delivery_confirmed"].toString())
        assertEquals("delivery_error", json["delivery_status"]?.jsonPrimitive?.content)
        assertEquals("64", json.getValue("delivery_parts").jsonArray[1].jsonObject["report_status"].toString())
    }

    @Test fun waitingForDeliveryCanTimeOutWhileSendingRemainsConfirmed() {
        val receipt = SmsDeliveryBuffer().register("delivery-timeout", 2)
        receipt.acknowledge(0, -1)
        receipt.acknowledge(1, -1)
        receipt.acknowledgeDelivery(0, -1, 0, "3gpp")

        val json = smsOutcomeJson(receipt.snapshot(timedOut = true), 2)
        assertEquals("sent", json["status"]?.jsonPrimitive?.content)
        assertEquals("true", json["success"].toString())
        assertEquals("false", json["sending_timed_out"].toString())
        assertEquals("timeout", json["delivery_status"]?.jsonPrimitive?.content)
        assertEquals("1", json["parts_delivery_confirmed"].toString())
    }

    @Test fun modemFailureCodeIsPreservedAlongsideAndroidCode() {
        val receipt = SmsDeliveryBuffer().register("modem", 1)
        receipt.acknowledge(0, 1, 42)
        val failure = smsOutcomeJson(receipt.snapshot(), 1).getValue("failed_parts").jsonArray.single().jsonObject
        assertEquals("1", failure["android_result_code"].toString())
        assertEquals("42", failure["modem_error_code"].toString())
    }

    @Test fun nativeExceptionReportsOnlyItsSafeTypeAndPreservesPartialSubmission() {
        val receipt = SmsDeliveryBuffer().register("native", 2)
        receipt.acknowledge(0, -1)
        val exception = SecurityException("private recipient and body")
        val outcome = receipt.snapshot().copy(nativeErrorType = smsNativeExceptionType(exception))
        val json = smsOutcomeJson(outcome, 2)

        assertEquals("native_error", json["status"]?.jsonPrimitive?.content)
        assertEquals("SecurityException", json["native_error_type"]?.jsonPrimitive?.content)
        assertEquals("1", json["parts_sent"].toString())
        assertEquals("false", json["success"].toString())
        assertEquals("false", json["automatic_retry_allowed"].toString())
        assertFalse(json.toString().contains("private recipient and body"))
    }
}
