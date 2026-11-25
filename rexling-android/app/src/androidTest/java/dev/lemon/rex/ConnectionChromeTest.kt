package dev.lemon.rex

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionChromeTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun takeoverNoticeIsContextualAndDisappearsWhenTheOtherOwnerLeaves() {
        val other = mutableStateOf(true)
        var takes = 0
        var capture = false
        ui.setContent {
            MaterialTheme {
                OtherOwnerNotice(
                    "b",
                    other.value,
                    true,
                    67 to 25,
                    { takes++ },
                    onVisibleChange = { capture = it },
                )
            }
        }
        ui.mainClock.autoAdvance = false
        ui.mainClock.advanceTimeBy(450)
        ui.onNodeWithText(appString(R.string.sized_by_another_client) + " · 67×25")
            .assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.take_over)).performClick()
        ui.runOnIdle {
            assertTrue(capture)
            assertEquals(1, takes)
            other.value = false
        }
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithText(appString(R.string.take_over)).assertDoesNotExist()
        ui.runOnIdle { assertFalse(capture) }
    }

    @Test
    fun cancelledOwnershipNoticeDoesNotResurrectAfterItsDelay() {
        val other = mutableStateOf(true)
        ui.setContent { MaterialTheme { OtherOwnerNotice("b", other.value, true, 67 to 25, {}) } }
        ui.mainClock.autoAdvance = false
        ui.mainClock.advanceTimeBy(100)
        ui.runOnIdle { other.value = false }
        ui.mainClock.advanceTimeBy(1000)
        ui.onNodeWithText(appString(R.string.take_over)).assertDoesNotExist()
    }

    @Test
    fun reconnectNoticeMakesPausedInputAndRetryExplicit() {
        val online = mutableStateOf<Boolean?>(false)
        var retries = 0
        ui.setContent {
            MaterialTheme { ConnectionNotice("reconnecting", online.value, { retries++ }) }
        }
        ui.onNodeWithText(appString(R.string.waiting_network_input_paused)).assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.retry_now)).assertIsNotEnabled()
        ui.runOnIdle { online.value = true }
        ui.onNodeWithText(appString(R.string.reconnecting_input_paused)).assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.retry_now)).performClick()
        ui.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun readyNoticeCancelsWhenInputStopsBeingReady() {
        val owned = mutableStateOf(true)
        ui.setContent { MaterialTheme { ReadyNotice(1, owned.value, true) } }
        ui.mainClock.autoAdvance = false
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithText(appString(R.string.ready_to_type)).assertIsDisplayed()
        ui.runOnIdle { owned.value = false }
        ui.mainClock.advanceTimeBy(2000)
        ui.onNodeWithText(appString(R.string.ready_to_type)).assertDoesNotExist()
    }
}
