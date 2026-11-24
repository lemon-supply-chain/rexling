package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerFrostTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun drawersAndMenuBlurTheBackdropOnceAndFreezeAgainstLiveHostChanges() {
        val choice = mutableIntStateOf(-1)
        val dark = mutableStateOf(false)
        val green = mutableStateOf(false)
        val captureBackdrop = mutableStateOf(true)
        val server = ServerProfile.create("Test Mac", "https://fixture.example", "mac")
        val target =
            SessionTarget(
                server,
                SessionItem("s", "Development"),
                SessionDetail("this device", 1, 1),
            )
        val window = WindowItem("w", "Project", listOf(BlockItem("b", "shell")))
        val dockWindow = window.copy(layout = listOf(PanePlacement("b", 0f, 0f, 1f, 1f)))
        val record =
            ActivityRecord.parse(
                JSONObject()
                    .put("session", "s")
                    .put("sessionLabel", "Development")
                    .put("windowLabel", "Project")
                    .put("block", "b")
                    .put("state", "blocked")
                    .put("kind", "permission")
                    .put("title", "Review changes")
                    .put("app", "codex"),
            )!!
        ui.setContent {
            RexTheme(dark.value, ThemeCatalog.defaults[if (dark.value) 0 else 1]) {
                val haze = remember { HazeState() }
                Box(Modifier.fillMaxSize()) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .then(
                                if (captureBackdrop.value) Modifier.hazeSource(haze) else Modifier,
                            ),
                    ) {
                        if (green.value) drawRect(Color.Green)
                        else {
                            val stripe = 2.dp.toPx()
                            repeat((size.width / stripe).toInt() + 1) { x ->
                                drawRect(
                                    if (x % 2 == 0) Color.Red else Color.Blue,
                                    androidx.compose.ui.geometry.Offset(x * stripe, 0f),
                                    androidx.compose.ui.geometry.Size(stripe, size.height),
                                )
                            }
                        }
                    }
                    when (choice.intValue) {
                        0 ->
                            ActivitySheet(
                                listOf(
                                    ActivitySource(server, true, false, "", false, listOf(record)),
                                ),
                                {},
                                {},
                                {},
                                hazeState = haze,
                            )
                        1 ->
                            TabPaneSwitcher(
                                emptyList(),
                                "",
                                "",
                                emptyMap(),
                                emptyMap(),
                                emptyMap(),
                                { 0 },
                                true,
                                true,
                                true,
                                {},
                                {},
                                {},
                                { _, _ -> },
                                {},
                                {},
                                {},
                                hazeState = haze,
                                sessions =
                                    SwitcherSessions(1, "") {
                                        SessionsPage(
                                            listOf(target),
                                            target.key,
                                            "",
                                            true,
                                            "",
                                            "",
                                            {},
                                            { _, _ -> true },
                                            {},
                                            {},
                                        )
                                    },
                                openSessions = true,
                            )
                        2 ->
                            TabPaneSwitcher(
                                listOf(window),
                                "w",
                                "b",
                                mapOf("b" to ProgramIdentity.resolve(listOf("codex"))),
                                mapOf("b" to "Review changes"),
                                mapOf("b" to "~/Dev/project"),
                                { 0 },
                                true,
                                true,
                                true,
                                {},
                                {},
                                {},
                                { _, _ -> },
                                {},
                                {},
                                {},
                                hazeState = haze,
                            )
                        3 ->
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 12.dp),
                            ) {
                                DropdownMenu(
                                    true,
                                    {},
                                    modifier =
                                        Modifier.drawerFrost(haze, RoundedCornerShape(16.dp)),
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = Color.Transparent,
                                    tonalElevation = 0.dp,
                                ) {
                                    TerminalMenuItem(
                                        "Preferences",
                                        ChromeIcon.SETTINGS,
                                        onClick = {},
                                    )
                                    TerminalMenuItem("Attach image", ChromeIcon.IMAGE, onClick = {})
                                }
                            }
                        4 ->
                            DockPaneSheet(
                                dockWindow,
                                mapOf("b" to ProgramIdentity.resolve(listOf("fish"))),
                                emptyMap(),
                                true,
                                haze,
                                onDock = { _, _ -> true },
                                onClose = {},
                            )
                    }
                }
            }
        }
        // The invalidator must stop by itself; a build that keeps re-invalidating
        // every host draw used to pass the pixel checks below.
        fun disarm(): PopupFrostInvalidator {
            repeat(120) {
                PopupFrostInvalidator.lastSeen?.get()?.let { if (!it.isListening) return it }
                ui.waitForIdle()
                Thread.sleep(8)
            }
            val last = PopupFrostInvalidator.lastSeen?.get()
            assertNotNull("Frost invalidator was never created", last)
            assertFalse("Frost invalidator never disarmed", last!!.isListening)
            return last
        }
        for (isDark in listOf(false, true)) for (kind in 0..4) {
            ui.runOnIdle { choice.intValue = -1 }
            ui.waitForIdle()
            ui.runOnIdle {
                dark.value = isDark
                green.value = false
                captureBackdrop.value = true
                choice.intValue = kind
            }
            ui.waitForIdle()
            val overlay = ui.onNodeWithTag("Frosted overlay")
            overlay.assertIsDisplayed()
            // The content must move with the sheet, outside any pre-offset clip.
            when (kind) {
                0 -> {
                    ui.onNodeWithText("Activity").assertIsDisplayed()
                    ui.onNodeWithText("Review changes").assertIsDisplayed()
                }
                1 -> ui.onNodeWithText("Development").assertIsDisplayed()
                2 -> ui.onNodeWithContentDescription("Tab Project").assertIsDisplayed()
                3 -> ui.onNodeWithText("Preferences").assertIsDisplayed()
                4 -> ui.onNodeWithText("Dock pane").assertIsDisplayed()
            }
            if (isDark && kind in 0..1) {
                var text: androidx.compose.ui.text.TextLayoutResult? = null
                ui.onNodeWithText(if (kind == 0) "Activity" else "Sessions").performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
                ) { get ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    get(results)
                    text = results.single()
                }
                assertTrue(
                    "Dark drawer headings must retain light foregrounds",
                    text!!.layoutInput.style.color.luminance() > .3f,
                )
            }
            fun sample(): Color {
                val image = overlay.captureToImage()
                return image.toPixelMap()[(image.width * .25f).toInt(), 6]
            }
            val image = overlay.captureToImage()
            val pixelMap = image.toPixelMap()
            val x1 = (image.width * .25f).toInt()
            val x2 = (x1 + 4).coerceAtMost(image.width - 1)
            val p1 = pixelMap[x1, 6]
            val p2 = pixelMap[x2, 6]
            assertTrue(
                "Overlay $kind must mix red and blue stripes in dark=$isDark: $p1",
                p1.red > .12f && p1.blue > .12f,
            )
            assertTrue(
                "Overlay $kind must blur stripes with low color variance across period: $p1 vs $p2",
                kotlin.math.abs(p1.red - p2.red) < .25f,
            )
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "drawer-frost-$kind-${if(isDark) "dark" else "light"}.png",
                )
                .outputStream()
                .use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            ui.runOnIdle { green.value = true }
            ui.waitForIdle()
            Thread.sleep(80)
            val updated = sample()
            assertTrue(
                "Overlay $kind must remain frozen on initial backdrop: $updated",
                minOf(updated.red, updated.blue) > updated.green + .04f,
            )
            val frost = disarm()
            assertTrue(
                "Overlay $kind must stop invalidating: ${frost.invalidationCount}",
                frost.invalidationCount <= 10,
            )
            if (kind == 4) {
                ui.runOnIdle { captureBackdrop.value = false }
                val fallback = sample()
                assertTrue(
                    "Dock pane must keep its surface when the backdrop is unavailable: $fallback",
                    fallback.green < maxOf(fallback.red, fallback.blue) + .04f,
                )
                assertFalse(
                    "Dock pane frost must stay disarmed without a source",
                    frost.isListening,
                )
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(
                        instrumentation.targetContext.cacheDir,
                        "dock-pane-sheet-${if(isDark) "dark" else "light"}.png",
                    )
                    .outputStream()
                    .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        // A drawer whose haze never gets a source must still disarm after bounded attempts.
        ui.runOnIdle {
            dark.value = false
            green.value = false
            captureBackdrop.value = false
            choice.intValue = 4
        }
        ui.waitForIdle()
        val cold = disarm()
        assertTrue(
            "Cold frost must stop invalidating: ${cold.invalidationCount}",
            cold.invalidationCount <= 10,
        )
        ui.runOnIdle { choice.intValue = -1 }
    }
}
