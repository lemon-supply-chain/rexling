package dev.lemon.rex

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Defaults keep the existing blue UI byte-for-byte; presets supply semantic colors. */
internal fun ColorScheme.withPreset(theme: AppTheme): ColorScheme {
    if(theme.ui.isEmpty()) return this
    fun c(key: String)=Color(theme.ui.getValue(key))
    return copy(primary=c("accentSolid"),onPrimary=c("accentOnSolid"),primaryContainer=c("accentContainer"),onPrimaryContainer=c("accentOnContainer"),
        secondary=c("accentSecondary"),onSecondary=c("accentOnSecondary"),secondaryContainer=c("accentSecondaryContainer"),onSecondaryContainer=c("accentOnSecondaryContainer"),
        tertiary=c("accentTertiary"),onTertiary=c("accentOnTertiary"),tertiaryContainer=c("accentTertiaryContainer"),onTertiaryContainer=c("accentOnTertiaryContainer"),
        background=c("canvas"),onBackground=c("content"),surface=c("surface"),onSurface=c("content"),surfaceVariant=c("surfaceRaised"),onSurfaceVariant=c("contentSecondary"),
        surfaceDim=c("canvas"),surfaceBright=c("surfaceRaised"),surfaceContainerLowest=c("canvas"),surfaceContainerLow=c("surface"),surfaceContainer=c("surfaceSubtle"),
        surfaceContainerHigh=c("surfaceRaised"),surfaceContainerHighest=c("surfaceRaised"),outline=c("strokeSubtle"),outlineVariant=c("stroke"),surfaceTint=Color.Transparent,
        error=c("dangerSolid"),onError=c("dangerOnSolid"),errorContainer=c("dangerContainer"),onErrorContainer=c("dangerOnContainer"))
}

/** The app colour scheme for one appearance, shared with tests that check every preset. */
internal fun rexColorScheme(dark: Boolean,theme: AppTheme): androidx.compose.material3.ColorScheme {
    val base=if(dark) darkColorScheme(primary=Color(0xff315cf4),onPrimary=Color.White,
        background=Color(0xff090c11),surface=Color(0xff141922),onSurface=Color(0xffe5e9f2),
        surfaceVariant=Color(0xff232934),onSurfaceVariant=Color(0xff969fb1),outline=Color(0xff2b3341))
    else lightColorScheme(primary=Color(0xff3156f4),onPrimary=Color.White,
        background=Color(0xfff3f4f9),surface=Color(0xfffdfdff),onSurface=Color(0xff171b26),
        surfaceVariant=Color(0xffe9ebf2),onSurfaceVariant=Color(0xff62697c),outline=Color(0xffd8dce8))
    val container = if(dark) Color(0xff171d26) else Color(0xfff1f3f9)
    val containerLow = if(dark) Color(0xff121720) else Color(0xfff8f9fd)
    val containerHigh = if(dark) Color(0xff1b212c) else Color(0xffe9ebf2)
    val containerHighest = if(dark) Color(0xff232934) else Color(0xffe2e5ee)
    val primaryContainer = if(dark) Color(0xff17254d) else Color(0xffe4eaff)
    val onPrimaryContainer = if(dark) Color(0xffbed0ff) else Color(0xff193dba)
    // The default blue split into three jobs: action (authored blue), selection
    // (quiet tint of the same hue) and working (hue rotated 60 degrees). Derived by
    // scripts/derive_theme_roles.py, the same rule every preset uses.
    val secondary = if(dark) Color(0xffacc4f9) else Color(0xff4f6291)
    val onSecondary = if(dark) Color.Black else Color.White
    val secondaryContainer = if(dark) Color(0xff232d45) else Color(0xffd7e4ff)
    val onSecondaryContainer = if(dark) Color(0xffbfddff) else Color(0xff23366e)
    val tertiary = if(dark) Color(0xfffaaffb) else Color(0xff8f4a8f)
    val onTertiary = if(dark) Color.Black else Color.White
    val tertiaryContainer = if(dark) Color(0xff3b253b) else Color(0xfff7daf6)
    val onTertiaryContainer = if(dark) Color(0xffffc5ff) else Color(0xff572158)
    val scheme = base.copy(surfaceTint=Color.Transparent,
        surfaceDim=base.background,surfaceBright=containerHighest,
        surfaceContainerLowest=base.background,surfaceContainerLow=containerLow,
        surfaceContainer=container,surfaceContainerHigh=containerHigh,surfaceContainerHighest=containerHighest,
        primaryContainer=primaryContainer,onPrimaryContainer=onPrimaryContainer,
        secondary=secondary,onSecondary=onSecondary,secondaryContainer=secondaryContainer,onSecondaryContainer=onSecondaryContainer,
        tertiary=tertiary,onTertiary=onTertiary,tertiaryContainer=tertiaryContainer,onTertiaryContainer=onTertiaryContainer)
    return scheme.withPreset(theme)
}
