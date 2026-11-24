package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class KeepScreenAwakeTest {
    @Test
    fun systemDefaultNeverOverridesTimeout() {
        assertFalse(KeepScreenAwake.SYSTEM.enabled(true, true))
    }

    @Test
    fun visibleModeIncludesIdleReadingButNeverBackground() {
        assertTrue(KeepScreenAwake.VISIBLE.enabled(true, false))
        KeepScreenAwake.entries.forEach { assertFalse(it.enabled(false, true)) }
    }

    @Test
    fun activeModeReleasesImmediatelyWhenActivityStops() {
        assertTrue(KeepScreenAwake.ACTIVE.enabled(true, true))
        assertFalse(KeepScreenAwake.ACTIVE.enabled(true, false))
    }
}
