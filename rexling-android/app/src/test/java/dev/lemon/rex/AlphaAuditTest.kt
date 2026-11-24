package dev.lemon.rex

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha is a medium, not a state.
 *
 * Blending an ink with alpha shrinks its contrast against whatever sits behind it, which is exactly
 * what role pairs exist to prevent. Alpha stays legal for scrims, artwork and fade animations;
 * every other `copy(alpha` must carry an `// alpha-ok <reason>` marker so the exception is visible
 * in review.
 *
 * ktfmt wraps long calls, so the marker can sit on the closing paren or the block brace of the
 * statement rather than on the `copy(alpha` line; accept a window of a few lines around the call.
 */
class AlphaAuditTest {
    /** How many lines around `copy(alpha` may hold the marker of the same statement. */
    private val markerWindow = 8

    /** Files whose alpha is a material property (glass, artwork, HUD, camera, new layouts). */
    private val renderingFiles =
        setOf(
            "PaneFrost.kt",
            "PaneCompositing.kt",
            "ProgramIcon.kt",
            "ProgramStack.kt",
            "ProgramVectorPicture.kt",
            "RainbowMarquee.kt",
            "WheelColors.kt",
            "WorkingIndicator.kt",
            "NeovimShortcutWheel.kt",
            "NeovimShortcutUi.kt",
            "QrScanner.kt",
            "MultiPaneLayout.kt",
            "TabRail.kt",
            "TerminalActionsDrawer.kt",
            "SwitcherSwipeRow.kt",
            "AllPanesPage.kt",
            "TerminalChrome.kt",
            "SessionRow.kt",
            "TabPaneSwitcher.kt",
            "SettingsComponents.kt",
        )

    @Test
    fun alphaNeverCarriesState() {
        val sources =
            listOf("src/main/java/dev/lemon/rex", "app/src/main/java/dev/lemon/rex")
                .map(::File)
                .first { it.isDirectory }
        val offenders =
            sources
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name !in renderingFiles }
                .flatMap { file ->
                    val lines = file.readLines()
                    lines
                        .withIndex()
                        .filter { (_, line) -> line.contains("copy(alpha") }
                        .filterNot { (index, _) ->
                            val from = maxOf(0, index - markerWindow)
                            val to = minOf(lines.lastIndex, index + markerWindow)
                            (from..to).any { lines[it].contains("alpha-ok") }
                        }
                        .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
                }
                .toList()
        assertTrue(
            "Alpha is carrying a state; use a color role or mark the call alpha-ok:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
