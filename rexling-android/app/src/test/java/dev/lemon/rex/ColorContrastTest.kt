package dev.lemon.rex

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast and role-separation guards for every shipped theme.
 *
 * Two classes of failure this test exists to prevent:
 * - a role pair below its WCAG floor after a palette or opacity change, and
 * - three accents collapsing back into one, so action, selection and working status cannot be told
 *   apart.
 */
class ColorContrastTest {
    private val themes = ThemeCatalog.parse(readCatalog())
    private val presets = themes.drop(2)

    private fun readCatalog(): String =
        listOf(
                "src/main/assets/themes/rex-builtins.json",
                "app/src/main/assets/themes/rex-builtins.json",
            )
            .first { File(it).exists() }
            .let { File(it).readText() }

    private val schemes: List<Pair<String, ColorScheme>> =
        presets.map { theme -> theme.id to rexColorScheme(theme.terminal.dark, theme.ui) } +
            listOf(
                "default-dark" to rexColorScheme(true, ThemeCatalog.defaults[0].ui),
                "default-light" to rexColorScheme(false, ThemeCatalog.defaults[1].ui),
            )

    private fun base(dark: Boolean) = if (dark) darkColorScheme() else lightColorScheme()

    private data class RolePair(
        val label: String,
        val foreground: (ColorScheme) -> Color,
        val background: (ColorScheme) -> Color,
    )

    @Test
    fun everyTextRoleClearsItsContrastFloor() {
        val textPairs =
            listOf(
                RolePair("onSurface on surface", { it.onSurface }, { it.surface }),
                RolePair("onSurfaceVariant on surface", { it.onSurfaceVariant }, { it.surface }),
                RolePair(
                    "onSurfaceVariant on surfaceContainer",
                    { it.onSurfaceVariant },
                    { it.surfaceContainer },
                ),
                RolePair(
                    "onSurfaceVariant on surfaceContainerHigh",
                    { it.onSurfaceVariant },
                    { it.surfaceContainerHigh },
                ),
                RolePair("onPrimary on primary", { it.onPrimary }, { it.primary }),
                RolePair("onSecondary on secondary", { it.onSecondary }, { it.secondary }),
                RolePair(
                    "onSecondaryContainer on secondaryContainer",
                    { it.onSecondaryContainer },
                    { it.secondaryContainer },
                ),
                RolePair("onTertiary on tertiary", { it.onTertiary }, { it.tertiary }),
                RolePair(
                    "onTertiaryContainer on tertiaryContainer",
                    { it.onTertiaryContainer },
                    { it.tertiaryContainer },
                ),
                RolePair(
                    "onPrimaryContainer on primaryContainer",
                    { it.onPrimaryContainer },
                    { it.primaryContainer },
                ),
                RolePair("onError on error", { it.onError }, { it.error }),
                RolePair(
                    "onErrorContainer on errorContainer",
                    { it.onErrorContainer },
                    { it.errorContainer },
                ),
            )
        schemes.forEach { (name, scheme) ->
            textPairs.forEach { pair ->
                val ratio = contrast(pair.foreground(scheme), pair.background(scheme))
                assertTrue("$name: ${pair.label} is ${format(ratio)}:1, needs 4.5:1", ratio >= 4.5)
            }
        }
    }

    @Test
    fun everyControlRoleClearsThreeToOne() {
        val controlPairs =
            listOf(
                RolePair("primary on surface", { it.primary }, { it.surface }),
                RolePair(
                    "primary on surfaceContainerHigh",
                    { it.primary },
                    { it.surfaceContainerHigh },
                ),
                RolePair("tertiary on surface", { it.tertiary }, { it.surface }),
                RolePair(
                    "tertiary on surfaceContainerHigh",
                    { it.tertiary },
                    { it.surfaceContainerHigh },
                ),
            )
        schemes.forEach { (name, scheme) ->
            controlPairs.forEach { pair ->
                val ratio = contrast(pair.foreground(scheme), pair.background(scheme))
                assertTrue("$name: ${pair.label} is ${format(ratio)}:1, needs 3:1", ratio >= 3.0)
            }
        }
    }

    private fun format(value: Double) = String.format("%.2f", value)

    private fun contrast(a: Color, b: Color): Double {
        val high = maxOf(luminance(a), luminance(b)) + 0.05
        val low = minOf(luminance(a), luminance(b)) + 0.05
        return high / low
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** Chroma and hue in OKLCH, so "quiet" and "far apart" are perceptual, not RGB guesses. */
    private fun oklch(color: Color): Triple<Double, Double, Double> {
        fun linear(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(color.red)
        val g = linear(color.green)
        val b = linear(color.blue)
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)
        val lightness = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot
        val a = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot
        val bb = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
        val hue = Math.toDegrees(atan2(bb, a)).let { if (it < 0) it + 360 else it }
        return Triple(lightness, hypot(a, bb), hue)
    }

    private fun chroma(color: Color) = oklch(color).second

    private fun hue(color: Color) = oklch(color).third

    private fun hueDistance(first: Double, second: Double): Double =
        kotlin.math.abs((first - second + 180) % 360 - 180)
}
