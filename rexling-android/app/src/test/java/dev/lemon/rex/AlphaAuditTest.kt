package dev.lemon.rex

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha is a medium, not a state.
 *
 * Blending an ink with alpha shrinks its contrast against whatever sits behind
 * it, which is exactly what role pairs exist to prevent. Alpha stays legal for
 * scrims, artwork and fade animations; every other `copy(alpha` must carry an
 * `// alpha-ok <reason>` marker so the exception is visible in review.
 */
class AlphaAuditTest {
    /** Files whose alpha is a material property (glass, artwork, HUD, camera). */
    private val renderingFiles = setOf(
        "PaneFrost.kt",
        "PaneCompositing.kt",
        "TileMaterial.kt",
        "ProgramIcon.kt",
        "ProgramStack.kt",
        "ProgramVectorPicture.kt",
        "RainbowMarquee.kt",
        "WheelColors.kt",
        "WorkingIndicator.kt",
        "NeovimShortcutWheel.kt",
        "NeovimShortcutUi.kt",
        "QrScanner.kt",
    )

    @Test
    fun alphaNeverCarriesState() {
        val sources = listOf("src/main/java/dev/lemon/rex", "app/src/main/java/dev/lemon/rex")
            .map(::File)
            .first { it.isDirectory }
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in renderingFiles }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> line.contains("copy(alpha") && !line.contains("alpha-ok") }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "Alpha is carrying a state; use a color role or mark the line alpha-ok:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}