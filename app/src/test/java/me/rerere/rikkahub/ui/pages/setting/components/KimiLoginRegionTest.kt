package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.rikkahub.data.kimi.KimiOAuthStatus
import me.rerere.rikkahub.data.kimi.KimiRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class KimiLoginRegionTest {
    @Test fun `restored explicit region survives loading an account from another region`() {
        val waiting = KimiOAuthStatus.Waiting("code", "https://www.kimi.com/", region = KimiRegion.CN)
        assertEquals(KimiRegion.CN, kimiLoginRegion(KimiRegion.CN, waiting, KimiRegion.GLOBAL))
        assertEquals(KimiRegion.CN, kimiLoginRegion(null, waiting, KimiRegion.GLOBAL))
    }

    @Test fun `cold load follows account until user explicitly selects a region`() {
        assertEquals(KimiRegion.CN, kimiLoginRegion(null, KimiOAuthStatus.Idle, null))
        assertEquals(KimiRegion.GLOBAL, kimiLoginRegion(null, KimiOAuthStatus.Idle, KimiRegion.GLOBAL))
        assertEquals(KimiRegion.CN, kimiLoginRegion(KimiRegion.CN, KimiOAuthStatus.Idle, KimiRegion.GLOBAL))
    }
}
