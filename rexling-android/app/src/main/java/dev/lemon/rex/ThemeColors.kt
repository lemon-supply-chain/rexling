package dev.lemon.rex

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Defaults keep the existing blue UI byte-for-byte; presets supply semantic colors. */
internal fun ColorScheme.withPreset(theme: AppTheme): ColorScheme {
    if(theme.ui.isEmpty()) return this
    fun c(key: String)=Color(theme.ui.getValue(key))
    return copy(primary=c("accentSolid"),onPrimary=c("accentOnSolid"),primaryContainer=c("accentContainer"),onPrimaryContainer=c("accentOnContainer"),
        secondary=c("accentSolid"),onSecondary=c("accentOnSolid"),secondaryContainer=c("accentContainer"),onSecondaryContainer=c("accentOnContainer"),
        tertiary=c("accentSolid"),onTertiary=c("accentOnSolid"),tertiaryContainer=c("accentContainer"),onTertiaryContainer=c("accentOnContainer"),
        background=c("canvas"),onBackground=c("content"),surface=c("surface"),onSurface=c("content"),surfaceVariant=c("surfaceRaised"),onSurfaceVariant=c("contentSecondary"),
        surfaceDim=c("canvas"),surfaceBright=c("surfaceRaised"),surfaceContainerLowest=c("canvas"),surfaceContainerLow=c("surface"),surfaceContainer=c("surfaceSubtle"),
        surfaceContainerHigh=c("surfaceRaised"),surfaceContainerHighest=c("surfaceRaised"),outline=c("strokeSubtle"),outlineVariant=c("stroke"),surfaceTint=Color.Transparent,
        error=c("dangerSolid"),onError=c("dangerOnSolid"),errorContainer=c("dangerContainer"),onErrorContainer=c("dangerOnContainer"))
}
