package dev.lemon.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepeatKeyTest {
    @get:Rule val ui = createComposeRule()
    private val sent = mutableListOf<String>()

    @Test
    fun heldBackspaceRepeatsAndStopsOnRelease() {
        ui.setContent {
            MaterialTheme {
                Box(
                    Modifier.width(184.dp)
                        .height(44.dp)
                        .semantics { contentDescription = "Repeat key" }
                        .repeatKey(true, "a") { sent += "backspace" },
                )
            }
        }
        ui.mainClock.autoAdvance = false
        ui.onNodeWithContentDescription("Repeat key").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(750)
        ui.runOnIdle {
            assertTrue(sent.size >= 3)
            assertTrue(sent.all { it == "backspace" })
        }
        ui.onNodeWithContentDescription("Repeat key").performTouchInput { up() }
        ui.mainClock.advanceTimeByFrame()
        val count = sent.size
        ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle { assertEquals(count, sent.size) }
    }

    @Test
    fun draggingAcrossARepeatKeyNeverTypes() {
        ui.setContent {
            MaterialTheme {
                Box(
                    Modifier.width(184.dp)
                        .height(44.dp)
                        .semantics { contentDescription = "Repeat key" }
                        .repeatKey(true, "") { sent += "backspace" },
                )
            }
        }
        ui.onNodeWithContentDescription("Repeat key").performTouchInput {
            swipe(
                androidx.compose.ui.geometry.Offset(width - 2f, height * .75f),
                androidx.compose.ui.geometry.Offset(2f, height * .75f),
                300,
            )
        }
        ui.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test
    fun aNewPaneCancelsHeldRepeatAndNeverRetargetsTheOldGesture() {
        val target = mutableStateOf("a")
        ui.setContent {
            val current = target.value
            MaterialTheme {
                Box(
                    Modifier.width(184.dp)
                        .height(44.dp)
                        .semantics { contentDescription = "Repeat key" }
                        .repeatKey(true, current) { sent += current },
                )
            }
        }
        ui.mainClock.autoAdvance = false
        ui.onNodeWithContentDescription("Repeat key").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(650)
        ui.runOnIdle {
            assertTrue(sent.isNotEmpty())
            target.value = "b"
        }
        ui.mainClock.advanceTimeByFrame()
        val count = sent.size
        ui.mainClock.advanceTimeBy(500)
        ui.runOnIdle {
            assertEquals(count, sent.size)
            assertTrue(sent.none { it == "b" })
        }
        ui.onNodeWithContentDescription("Repeat key").performTouchInput { up() }
    }

    @Test
    fun disabledRepeatCannotType() {
        ui.setContent {
            MaterialTheme {
                Box(
                    Modifier.width(184.dp)
                        .height(44.dp)
                        .semantics { contentDescription = "Repeat key" }
                        .repeatKey(false, "") { sent += "backspace" },
                )
            }
        }
        ui.onNodeWithContentDescription("Repeat key").assertIsNotEnabled().performTouchInput {
            longClick()
        }
        ui.runOnIdle { assertTrue(sent.isEmpty()) }
    }
}
