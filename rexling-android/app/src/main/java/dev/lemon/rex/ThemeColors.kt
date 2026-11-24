package dev.lemon.rex

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * UI colors of the two built-in themes, in the same Rex design vocabulary the preset themes bring
 * from `assets/themes/rex-builtins.json`, so `RexTheme` has one path for both.
 *
 * Only the historical colors are listed. `stroke` is absent because the built-in default has always
 * drawn `outlineVariant` from Material 3's own default, and `danger*` is absent because its
 * built-in default keeps drawing the library's error colors, which the app shows.
 * `surfaceContainerHigh` is not part of the preset vocabulary: it pins the surface step the
 * built-in default draws between the other tiers.
 */
internal object DefaultUiTokens {
    private fun rgb(value: Int) = 0xff000000.toInt() or (value and 0xffffff)

    val dark =
        mapOf(
            "accentSolid" to rgb(0x315cf4),
            "accentOnSolid" to rgb(0xffffff),
            "accentContainer" to rgb(0x17254d),
            "accentOnContainer" to rgb(0xbed0ff),
            "canvas" to rgb(0x090c11),
            "surface" to rgb(0x141922),
            "surfaceSubtle" to rgb(0x1b212c),
            "surfaceRaised" to rgb(0x232934),
            "surfaceContainerHigh" to rgb(0x1b212c),
            "content" to rgb(0xe5e9f2),
            "contentSecondary" to rgb(0x969fb1),
            "strokeSubtle" to rgb(0x2b3341),
        )
    val light =
        mapOf(
            "accentSolid" to rgb(0x3156f4),
            "accentOnSolid" to rgb(0xffffff),
            "accentContainer" to rgb(0xe4eaff),
            "accentOnContainer" to rgb(0x193dba),
            "canvas" to rgb(0xf3f4f9),
            "surface" to rgb(0xfdfdff),
            "surfaceSubtle" to rgb(0xeceef5),
            "surfaceRaised" to rgb(0xe9ebf2),
            "surfaceContainerHigh" to rgb(0xeceef5),
            "content" to rgb(0x171b26),
            "contentSecondary" to rgb(0x62697c),
            "strokeSubtle" to rgb(0xd8dce8),
        )
}

/** The app's one color derivation: library defaults, then whatever tokens the theme carries. */
internal fun rexColorScheme(dark: Boolean, ui: Map<String, Int>): ColorScheme =
    (if (dark) darkColorScheme() else lightColorScheme()).withTokens(ui)

/**
 * Maps Rex design tokens onto Material 3 color roles. A token fills every role it stands for, a
 * role with no token keeps the library default, and an absent token never invents a color.
 * `inverse*` and `scrim` have no token, so they always stay with the library. The optional
 * `surfaceContainerHigh` is applied last, so a theme can keep its own step between the tiers.
 */
internal fun ColorScheme.withTokens(ui: Map<String, Int>): ColorScheme {
    fun token(key: String) = ui[key]?.let { Color(it) }
    var scheme = copy(surfaceTint = Color.Transparent)
    token("accentSolid")?.let { c ->
        scheme = scheme.copy(primary = c, secondary = c, tertiary = c)
    }
    token("accentOnSolid")?.let { c ->
        scheme = scheme.copy(onPrimary = c, onSecondary = c, onTertiary = c)
    }
    token("accentContainer")?.let { c ->
        scheme = scheme.copy(primaryContainer = c, secondaryContainer = c, tertiaryContainer = c)
    }
    token("accentOnContainer")?.let { c ->
        scheme =
            scheme.copy(onPrimaryContainer = c, onSecondaryContainer = c, onTertiaryContainer = c)
    }
    token("canvas")?.let { c ->
        scheme = scheme.copy(background = c, surfaceDim = c, surfaceContainerLowest = c)
    }
    token("content")?.let { c -> scheme = scheme.copy(onSurface = c, onBackground = c) }
    token("surface")?.let { c -> scheme = scheme.copy(surface = c, surfaceContainerLow = c) }
    token("surfaceSubtle")?.let { c -> scheme = scheme.copy(surfaceContainer = c) }
    token("surfaceRaised")?.let { c ->
        scheme =
            scheme.copy(
                surfaceVariant = c,
                surfaceBright = c,
                surfaceContainerHigh = c,
                surfaceContainerHighest = c,
            )
    }
    token("contentSecondary")?.let { c -> scheme = scheme.copy(onSurfaceVariant = c) }
    token("strokeSubtle")?.let { c -> scheme = scheme.copy(outline = c) }
    token("stroke")?.let { c -> scheme = scheme.copy(outlineVariant = c) }
    token("dangerSolid")?.let { c -> scheme = scheme.copy(error = c) }
    token("dangerOnSolid")?.let { c -> scheme = scheme.copy(onError = c) }
    token("dangerContainer")?.let { c -> scheme = scheme.copy(errorContainer = c) }
    token("dangerOnContainer")?.let { c -> scheme = scheme.copy(onErrorContainer = c) }
    token("surfaceContainerHigh")?.let { c -> scheme = scheme.copy(surfaceContainerHigh = c) }
    return scheme
}
