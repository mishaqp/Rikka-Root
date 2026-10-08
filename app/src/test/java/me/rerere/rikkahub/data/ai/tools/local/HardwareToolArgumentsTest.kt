package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class HardwareToolArgumentsTest {
    private fun args(source: String) = Json.parseToJsonElement(source).jsonObject

    @Test fun defaultAndValidVibrationsHaveBoundedTimings() {
        assertArrayEquals(longArrayOf(0, 500), validateVibrationArguments(args("{}")))
        assertArrayEquals(longArrayOf(0, 5000), validateVibrationArguments(args("{\"duration_ms\":5000}")))
        assertArrayEquals(longArrayOf(0, 100, 20, 200), validateVibrationArguments(args("{\"pattern\":[0,100,20,200]}")))
    }

    @Test fun invalidVibrationCannotReachAndroid() {
        listOf("{\"duration_ms\":0}", "{\"duration_ms\":-1}", "{\"duration_ms\":5001}",
            "{\"duration_ms\":\"500\"}", "{\"pattern\":[]}", "{\"pattern\":[0,0]}",
            "{\"pattern\":[0,100,-1]}", "{\"pattern\":[0,5000,1,5000]}",
            "{\"pattern\":[0,\"100\"]}", "{\"pattern\":false}",
            "{\"pattern\":[0,100],\"duration_ms\":100}",
            "{\"pattern\":[0,100,0,100,0,100,0,100,0,100,0,100,0,100,0,100,0,100,0,100,0]}")
            .forEach { source -> assertThrows(source, IllegalArgumentException::class.java) { validateVibrationArguments(args(source)) } }
    }

    @Test fun brightnessNeverTurnsTheScreenCompletelyBlack() {
        assertEquals(1, brightnessValue(Int.MIN_VALUE))
        assertEquals(1, brightnessValue(0))
        assertEquals(128, brightnessValue(128))
        assertEquals(255, brightnessValue(Int.MAX_VALUE))
    }

    @Test fun volumeRespectsDeviceMinimumAndRoundsWithinRange() {
        assertEquals(0, volumeStep(0, 0, 15))
        assertEquals(8, volumeStep(50, 0, 15))
        assertEquals(15, volumeStep(100, 0, 15))
        assertEquals(1, volumeStep(0, 1, 5))
        assertEquals(3, volumeStep(50, 1, 5))
        assertThrows(IllegalArgumentException::class.java) { volumeStep(101, 0, 15) }
        assertThrows(IllegalArgumentException::class.java) { volumeStep(50, 10, 5) }
    }

    @Test fun systemVolumeCannotBypassRingAccessThroughAndroidAliases() {
        assertTrue(volumeNeedsDndAccess("ring"))
        assertTrue(volumeNeedsDndAccess("notification"))
        assertTrue(volumeNeedsDndAccess("system"))
        assertFalse(volumeNeedsDndAccess("media"))
        assertFalse(volumeNeedsDndAccess("alarm"))
        assertFalse(volumeNeedsDndAccess("voice_call"))
    }

    @Test fun wallpaperDecodeCapsBothPixelsAndLongestSide() {
        assertEquals(1, wallpaperSampleSize(1920, 1080))
        assertEquals(4, wallpaperSampleSize(8000, 8000))
        assertEquals(4, wallpaperSampleSize(16000, 1))
        val sample = wallpaperSampleSize(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue((Int.MAX_VALUE.toLong() / sample) * (Int.MAX_VALUE.toLong() / sample) <= 4_194_304L)
        assertThrows(IllegalArgumentException::class.java) { wallpaperSampleSize(0, 100) }
    }

    @Test fun imageInputCannotAllocatePastTheByteLimit() {
        val input = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(input, readBoundedImage(ByteArrayInputStream(input), 4))
        assertThrows(IllegalArgumentException::class.java) { readBoundedImage(ByteArrayInputStream(input), 3) }
    }
}
