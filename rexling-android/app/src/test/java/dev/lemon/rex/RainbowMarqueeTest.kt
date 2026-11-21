package dev.lemon.rex

import androidx.compose.runtime.mutableFloatStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainbowMarqueeTest {
    @Test
    fun marqueePropertiesForwardStateValues() {
        val hueState = mutableFloatStateOf(0.42f)
        val glintState = mutableFloatStateOf(0.75f)
        val marquee = RainbowMarquee(hueState, glintState)

        assertEquals(0.42f, marquee.hue, 1e-4f)
        assertEquals(0.75f, marquee.glint, 1e-4f)
    }

    @Test
    fun lightBreathingCyclesWithinExpectedRange() {
        val hueState = mutableFloatStateOf(0f)
        val glintState = mutableFloatStateOf(0f)
        val marquee = RainbowMarquee(hueState, glintState)

        // At glint 0.25, sin(0.25 * 2 * PI) = sin(PI / 2) = 1.0 -> max breath (1.0) -> light = 0.17
        glintState.floatValue = 0.25f
        assertEquals(0.17f, marquee.light, 1e-3f)

        // At glint 0.75, sin(0.75 * 2 * PI) = sin(3 * PI / 2) = -1.0 -> min breath (0.0) -> light = 0.10
        glintState.floatValue = 0.75f
        assertEquals(0.10f, marquee.light, 1e-3f)

        // Across 100 sample points, light is always in [0.10f, 0.17f]
        for (i in 0..100) {
            glintState.floatValue = i / 100f
            assertTrue("light should be >= 0.10f", marquee.light >= 0.0999f)
            assertTrue("light should be <= 0.17f", marquee.light <= 0.1701f)
        }
    }
}
