package dev.lemon.rex

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Resolve UI copy from the app's own resources so assertions follow the device locale. Literal
 * English assertions fail on a zh device as soon as the node renders a resource.
 */
internal fun appString(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

/** Plural copy for assertions, following the device locale. */
internal fun appQuantity(@PluralsRes id: Int, count: Int): String =
    InstrumentationRegistry.getInstrumentation()
        .targetContext
        .resources
        .getQuantityString(id, count, count)

/** Sheets slide in and popups animate; wait for the node to reach the screen. */
internal fun ComposeTestRule.awaitDisplayed(interaction: SemanticsNodeInteraction) {
    waitUntil(5_000) { runCatching { interaction.isDisplayed() }.getOrDefault(false) }
    interaction.assertIsDisplayed()
}
