package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightlyUpdatePolicyTest {
    private val checkedAt = 1_800_000_000_000L
    private val sixHours = 6 * 60 * 60 * 1000L

    @Test fun `startup checks immediately with no saved attempt`() {
        assertTrue(shouldCheckNightly(null, checkedAt))
    }

    @Test fun `startup is throttled until exactly six hours since previous attempt`() {
        assertFalse(shouldCheckNightly(checkedAt, checkedAt))
        assertFalse(shouldCheckNightly(checkedAt, checkedAt + sixHours - 1))
        assertTrue(shouldCheckNightly(checkedAt, checkedAt + sixHours))
    }

    @Test fun `manual check bypasses the six hour interval`() {
        assertTrue(shouldCheckNightly(checkedAt, checkedAt + 1, force = true))
    }

    @Test fun `clock rollback does not bypass the startup rate limit`() {
        assertFalse(shouldCheckNightly(checkedAt, checkedAt - 1))
    }

    @Test fun `cache is invalidated when installed commit changes`() {
        val cache = NightlyUpdateCache(currentCommit = "a1234567", state = NightlyUpdateState(checkedAt = checkedAt))
        assertTrue(cache.matchesInstalledCommit("a1234567" + "0".repeat(32)))
        assertFalse(cache.matchesInstalledCommit("b1234567"))
    }
}
