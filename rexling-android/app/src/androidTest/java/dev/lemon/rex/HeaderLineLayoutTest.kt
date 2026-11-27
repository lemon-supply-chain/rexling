package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeaderLineLayoutTest {
    @get:Rule val ui = createComposeRule()
    private val titles =
        listOf(
            "Task title",
            "* Task title",
            "✳ Task title",
            "✻ Task title",
            "✽ Task title",
            "✢ Task title",
            "✳️ Task title",
            "⠋ Task title",
            "🚀 Task title",
        )

    private fun header(title: State<String>, scale: State<Float>) {
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                MaterialTheme {
                    Box(Modifier.width(340.dp).testTag("header")) {
                        SessionHeader(
                            listOf(SessionItem("a", "alpha · Mac")),
                            "a",
                            true,
                            true,
                            listOf(ProgramIdentity(ProgramKind.CLAUDE, "Claude Code")),
                            0,
                            {},
                            {},
                            {},
                            title = title.value,
                            serverIcons = mapOf("a" to ServerIcon.MACBOOK),
                            iconSlotWidth = tabRailWidth(TabRailStyle.ICONS),
                            menu = {},
                        )
                    }
                }
            }
        }
    }

    private fun bounds(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInRoot

    private fun text(value: String) = ui.onNodeWithText(value, useUnmergedTree = true)

    private fun baseline(node: SemanticsNodeInteraction): Float {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.positionInRoot().y + coordinates[FirstBaseline]
    }

    @Test
    fun claudeSpinnerChangesKeepBothHeaderRowsAndTheirBaselinesInPlace() {
        val title = mutableStateOf(titles.first())
        val scale = mutableFloatStateOf(1f)
        header(title, scale)
        for (fontScale in listOf(1f, 1.5f, 2f)) {
            ui.runOnIdle {
                scale.floatValue = fontScale
                title.value = titles.first()
            }
            val initial = text(title.value).fetchSemanticsNode().layoutInfo
            val original = initial.width to initial.height
            val base = baseline(text(title.value))
            val stripNode =
                ui.onNodeWithContentDescription(
                    appString(R.string.a11y_session_strip),
                    useUnmergedTree = true,
                )
            val strip = bounds(stripNode)
            val root = bounds(ui.onNodeWithTag("header"))
            val iconSlot =
                ui.onNodeWithContentDescription(appString(R.string.a11y_session_program_icon))
                    .assertWidthIsEqualTo(tabRailWidth(TabRailStyle.ICONS))
            val icon = ui.onNodeWithContentDescription("Claude Code", useUnmergedTree = true)
            assertEquals(
                with(ui.density) { 2.dp.roundToPx().toFloat() },
                bounds(icon).left - bounds(iconSlot).left,
                .5f,
            )
            val titleBottom = initial.coordinates.positionInRoot().y + initial.height
            val stripTop = stripNode.fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y
            assertTrue(
                "Header rows overlap at font scale $fontScale: $titleBottom > $stripTop",
                titleBottom <= stripTop + 1f,
            )
            for (value in titles.drop(1)) {
                ui.runOnIdle { title.value = value }
                // Glyph bounds can vary; the allocated line and placed baseline must not.
                val line = text(value).fetchSemanticsNode().layoutInfo
                assertEquals(value, original, line.width to line.height)
                assertEquals(value, base, baseline(text(value)), .6f)
                assertEquals(value, strip, bounds(stripNode))
                assertEquals(value, root, bounds(ui.onNodeWithTag("header")))
            }
        }
    }

    @Test
    fun textOpensTheSessionDrawerInBothHeaderLayouts() {
        var keyboard by mutableIntStateOf(0)
        var compact by mutableStateOf(CompactHeaderMode.NEVER)
        var drawerOpens = 0
        var menuOpens = 0
        var overlap = 0
        val title = "Task title"
        val session = "Shipyard · ShipPDF"
        val target =
            SessionTarget(
                ServerProfile.create("Shipyard", "https://shipyard", "shipyard"),
                SessionItem("a", "ShipPDF"),
            )
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MaterialTheme {
                    val haze = remember { HazeState() }
                    Column(Modifier.width(340.dp).height(150.dp)) {
                        SessionHeader(
                            SessionCatalog.strip(emptyList(), target),
                            target.key,
                            true,
                            true,
                            emptyList(),
                            0,
                            onSessions = { drawerOpens++ },
                            onSelect = {},
                            onLast = {},
                            title = title,
                            collapseForIme =
                                if (compact != CompactHeaderMode.NEVER)
                                    WindowInsets(bottom = keyboard)
                                else null,
                            alwaysCompact = compact == CompactHeaderMode.ALWAYS,
                            serverIcons = mapOf(target.key to ServerIcon.CLOUD),
                            headerHaze = haze,
                            onHeaderOverlap = { overlap = it },
                            modifier = Modifier.testTag("header").zIndex(1f),
                            menu = {
                                ChromeButton(
                                    ChromeIcon.MORE,
                                    appString(R.string.terminal_menu),
                                ) {
                                    menuOpens++
                                }
                            },
                        )
                        Box(
                            Modifier.weight(1f)
                                .fillMaxWidth()
                                .underWorkspaceHeader { overlap }
                                .hazeSource(haze),
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(Color.Red)
                                drawRect(
                                    Color.Blue,
                                    topLeft = Offset(size.width / 2f, 0f),
                                    size = Size(size.width / 2f, size.height),
                                )
                            }
                        }
                    }
                }
            }
        }
        val header = ui.onNodeWithTag("header").assertHeightIsEqualTo(44.dp)
        val fullHeight = bounds(header).height
        val strip =
            ui.onNodeWithContentDescription(
                appString(R.string.a11y_session_strip),
                useUnmergedTree = true,
            )
        ui.onNode(hasText(title) and hasText(session) and hasClickAction()).assertIsDisplayed()
        listOf(title, session).forEach { label -> text(label).performTouchInput { click() } }
        ui.runOnIdle {
            assertEquals(2, drawerOpens)
            keyboard = 300
        }
        strip.assertIsDisplayed()
        assertEquals(fullHeight, bounds(header).height)
        ui.runOnIdle { compact = CompactHeaderMode.WITH_KEYBOARD }
        strip.assertIsNotDisplayed()
        header.assertHeightIsEqualTo(36.dp)
        val inCompact = hasAnyAncestor(hasTestTag("Compact session header"))
        ui.onNode(hasContentDescription("Cloud") and inCompact, useUnmergedTree = true)
            .assertIsDisplayed()
        val backdrop = header.captureToImage().toPixelMap()
        val y = backdrop.height - 8
        val left = backdrop[backdrop.width / 4, y]
        val edge = backdrop[backdrop.width / 2, y]
        val right = backdrop[backdrop.width * 3 / 4, y]
        assertTrue(
            "The collapsed header should blur the backdrop edge: $left, $edge, $right",
            left.red > edge.red &&
                edge.red > right.red &&
                left.blue < edge.blue &&
                edge.blue < right.blue,
        )
        ui.onNode(hasText(title) and hasText(session) and inCompact)
            .assertIsDisplayed()
            .performTouchInput { click() }
        ui.onNode(hasContentDescription(appString(R.string.terminal_menu)) and inCompact)
            .performClick()
        ui.runOnIdle {
            assertEquals(3, drawerOpens)
            assertEquals(1, menuOpens)
            keyboard = 0
        }
        strip.assertIsDisplayed()
        assertEquals(fullHeight, bounds(header).height)
        ui.runOnIdle { compact = CompactHeaderMode.ALWAYS }
        strip.assertDoesNotExist()
        header.assertHeightIsEqualTo(36.dp)
        val compactHeight = bounds(header).height
        assertTrue(compactHeight < fullHeight)
        ui.onNode(hasText(title) and hasText(session) and inCompact).performTouchInput { click() }
        ui.runOnIdle {
            assertEquals(4, drawerOpens)
            keyboard = 300
        }
        assertEquals(compactHeight, bounds(header).height)
        ui.runOnIdle {
            assertTrue(overlap > 0)
            keyboard = 0
        }
        assertEquals(compactHeight, bounds(header).height)
    }

    @Test
    fun theImmersiveHeadingAndMenuFitBesideTheCamera() {
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MaterialTheme {
                    val cutout =
                        with(LocalDensity.current) {
                            IntRect(160.dp.roundToPx(), 0, 184.dp.roundToPx(), 46.dp.roundToPx())
                        }
                    Box(Modifier.width(360.dp).testTag("header")) {
                        SessionHeader(
                            listOf(SessionItem("a", "ShipPDF · Shipyard")),
                            "a",
                            true,
                            true,
                            emptyList(),
                            0,
                            onSessions = {},
                            onSelect = {},
                            onLast = {},
                            title = "A long pane title that should stay clear of the camera",
                            topCutout = cutout,
                            menu = {
                                ChromeButton(
                                    ChromeIcon.MORE,
                                    appString(R.string.terminal_menu),
                                ) {}
                            },
                        )
                    }
                }
            }
        }
        val header = bounds(ui.onNodeWithTag("header"))
        val strip =
            bounds(
                ui.onNodeWithContentDescription(
                    appString(R.string.a11y_session_strip),
                    useUnmergedTree = true,
                ),
            )
        val menu = bounds(ui.onNodeWithContentDescription(appString(R.string.terminal_menu)))
        with(ui.density) {
            assertTrue(
                strip.right <= header.left + 160.dp.toPx() &&
                    menu.left >= header.left + 184.dp.toPx(),
            )
            assertEquals(46.dp.toPx(), header.height, 1f)
        }
    }

    @Test
    fun theDirectoryShowsItsFullPathThenItsTailThenHidesAsTheHeaderNarrows() {
        var width by mutableStateOf(400.dp)
        var compact by mutableStateOf(false)
        val title = "Rexling client"
        val session = "Shipyard · Work"
        val target =
            SessionTarget(
                ServerProfile.create("Shipyard", "https://shipyard", "shipyard"),
                SessionItem("a", "Work"),
            )
        val path = "~/Projects/ShipPDF/rexling"
        val tail = "…/rexling"
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MaterialTheme {
                    Box(Modifier.width(width)) {
                        SessionHeader(
                            SessionCatalog.strip(emptyList(), target),
                            target.key,
                            true,
                            true,
                            emptyList(),
                            0,
                            onSessions = {},
                            onSelect = {},
                            onLast = {},
                            title = title,
                            alwaysCompact = compact,
                            directory = path,
                            serverIcons = mapOf(target.key to ServerIcon.CLOUD),
                            menu = {
                                ChromeButton(
                                    ChromeIcon.MORE,
                                    appString(R.string.terminal_menu),
                                ) {}
                            },
                        )
                    }
                }
            }
        }
        text(path).assertIsDisplayed()
        ui.runOnIdle { width = 263.dp }
        text(path).assertDoesNotExist()
        text(tail).assertIsDisplayed()
        ui.runOnIdle { width = 190.dp }
        text(tail).assertDoesNotExist()
        text(session).assertIsDisplayed()

        ui.runOnIdle {
            width = 400.dp
            compact = true
        }
        val icon = ui.onNodeWithContentDescription("Cloud", useUnmergedTree = true)
        val order = listOf(icon, text(title), text(session), text(path)).map(::bounds)
        for ((left, right) in order.zipWithNext()) assertTrue(left.right <= right.left)
    }

    @Test
    fun compactHeaderReplacesServerIconWithBreathingRainbowDotWhenWorking() {
        var activeWorking by mutableStateOf(false)
        var siblingWorking by mutableStateOf(false)
        var dark by mutableStateOf(false)
        val programs =
            listOf(
                ProgramIdentity(ProgramKind.CLAUDE, "Claude Code"),
                ProgramIdentity(ProgramKind.NEOVIM, "Neovim"),
            )
        ui.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1], motionEnabled = true) {
                Box(
                    Modifier.width(360.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("compact-working-header"),
                ) {
                    SessionHeader(
                        listOf(SessionItem("a", "Shipyard · Work")),
                        "a",
                        true,
                        true,
                        programs,
                        0,
                        onSessions = {},
                        onSelect = {},
                        onLast = {},
                        title = "Claude Code",
                        working = activeWorking,
                        isPaneWorking = { index ->
                            if (index == 0) activeWorking else siblingWorking
                        },
                        alwaysCompact = true,
                        directory = "~/Projects/rexling",
                        serverIcons = mapOf("a" to ServerIcon.MACBOOK),
                        menu = {
                            ChromeButton(
                                ChromeIcon.MORE,
                                appString(R.string.terminal_menu),
                            ) {}
                        },
                    )
                }
            }
        }

        val serverIcon =
            ui.onNodeWithContentDescription(ServerIcon.MACBOOK.label, useUnmergedTree = true)
        val indicator = ui.onNodeWithTag("Compact header working indicator", useUnmergedTree = true)
        serverIcon.assertIsDisplayed()
        indicator.assertDoesNotExist()
        val serverIconBounds = bounds(serverIcon)
        val titleBounds = bounds(text("Claude Code"))

        fun assertDotReplacesServerIconSlot() {
            serverIcon.assertDoesNotExist()
            indicator.assertIsDisplayed()
            val dotBounds = bounds(indicator)
            assertEquals(serverIconBounds.left, dotBounds.left, 1f)
            assertTrue(dotBounds.right <= titleBounds.left + 1f)
            assertEquals(titleBounds.left, bounds(text("Claude Code")).left, 1f)
        }

        // Any running pane in the current window activates the compact indicator.
        ui.runOnIdle { siblingWorking = true }
        assertDotReplacesServerIconSlot()

        ui.runOnIdle {
            siblingWorking = false
            activeWorking = true
        }
        assertDotReplacesServerIconSlot()

        fun assertRainbowAndSave(mode: String) {
            ui.waitForIdle()
            val pixels = indicator.captureToImage().toPixelMap()
            var hasRed = false
            var hasGreen = false
            var hasBlue = false
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val c = pixels[x, y]
                    if (c.red > .65f && c.red - maxOf(c.green, c.blue) > .25f) hasRed = true
                    if (c.green > .65f && c.green - maxOf(c.red, c.blue) > .25f) hasGreen = true
                    if (c.blue > .65f && c.blue - maxOf(c.red, c.green) > .25f) hasBlue = true
                }
            }
            assertTrue(
                "$mode: compact indicator must draw red, green, and blue rainbow hues",
                hasRed && hasGreen && hasBlue,
            )
            val bg = pixels[0, 0]
            val center = pixels[pixels.width / 2, pixels.height / 2]
            val centerDiff =
                maxOf(
                    abs(center.red - bg.red),
                    abs(center.green - bg.green),
                    abs(center.blue - bg.blue),
                )
            assertTrue(
                "$mode: compact indicator center must be solidly filled ($center vs bg $bg)",
                centerDiff > .25f && maxOf(center.red, center.green, center.blue) > .65f,
            )
            val bitmap =
                ui.onNodeWithTag("compact-working-header").captureToImage().asAndroidBitmap()
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "compact-header-working-$mode.png",
                )
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        assertRainbowAndSave("light")
        ui.runOnIdle { dark = true }
        assertRainbowAndSave("dark")
    }

    @Test
    fun bottomDividerHidesAboveRoundedTerminalAndDrawsWhenSquare() {
        var showBottomDivider by mutableStateOf(true)
        var background = Color.Unspecified
        ui.setContent {
            RexTheme(false, ThemeCatalog.defaults[1]) {
                background = MaterialTheme.colorScheme.background
                Box(Modifier.width(360.dp).background(background).testTag("divider-header")) {
                    SessionHeader(
                        listOf(SessionItem("a", "Shipyard · Work")),
                        "a",
                        true,
                        true,
                        listOf(ProgramIdentity.Terminal),
                        0,
                        {},
                        {},
                        {},
                        title = "Terminal",
                        showBottomDivider = showBottomDivider,
                        menu = {},
                    )
                }
            }
        }
        val withDivider = ui.onNodeWithTag("divider-header").captureToImage().toPixelMap()
        val y = withDivider.height - 1
        assertNotEquals(
            "Divider should draw across the bottom edge when enabled",
            background,
            withDivider[withDivider.width / 2, y],
        )

        ui.runOnIdle { showBottomDivider = false }
        val withoutDivider = ui.onNodeWithTag("divider-header").captureToImage().toPixelMap()
        for (x in listOf(0, withoutDivider.width / 2, withoutDivider.width - 1)) {
            assertEquals(
                "No bottom divider pixel expected at x=$x when hidden",
                background,
                withoutDivider[x, y],
            )
        }
    }
}
