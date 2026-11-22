package dev.lemon.rex

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * The built-in themes used to be a dozen hexes inside `RexTheme`. They are token maps now, so this
 * pins what they drew before the consolidation: roles Rex supplies from tokens keep their exact
 * colors, roles it never tokenised stay with Material 3, and `onBackground` is the one role that
 * moves deliberately.
 */
class ThemeColorsTest {
    private val roleOf=listOf<Pair<String,(ColorScheme)->Color>>(
        "primary" to {it.primary},"onPrimary" to {it.onPrimary},
        "primaryContainer" to {it.primaryContainer},"onPrimaryContainer" to {it.onPrimaryContainer},
        "secondary" to {it.secondary},"onSecondary" to {it.onSecondary},
        "secondaryContainer" to {it.secondaryContainer},"onSecondaryContainer" to {it.onSecondaryContainer},
        "tertiary" to {it.tertiary},"onTertiary" to {it.onTertiary},
        "tertiaryContainer" to {it.tertiaryContainer},"onTertiaryContainer" to {it.onTertiaryContainer},
        "background" to {it.background},"onBackground" to {it.onBackground},
        "surface" to {it.surface},"onSurface" to {it.onSurface},
        "surfaceVariant" to {it.surfaceVariant},"onSurfaceVariant" to {it.onSurfaceVariant},
        "surfaceTint" to {it.surfaceTint},"surfaceDim" to {it.surfaceDim},"surfaceBright" to {it.surfaceBright},
        "surfaceContainerLowest" to {it.surfaceContainerLowest},"surfaceContainerLow" to {it.surfaceContainerLow},
        "surfaceContainer" to {it.surfaceContainer},"surfaceContainerHigh" to {it.surfaceContainerHigh},
        "surfaceContainerHighest" to {it.surfaceContainerHighest},
        "inverseSurface" to {it.inverseSurface},"inverseOnSurface" to {it.inverseOnSurface},"inversePrimary" to {it.inversePrimary},
        "error" to {it.error},"onError" to {it.onError},"errorContainer" to {it.errorContainer},"onErrorContainer" to {it.onErrorContainer},
        "outline" to {it.outline},"outlineVariant" to {it.outlineVariant},"scrim" to {it.scrim})

    /** What Rex draws from tokens. Anything else must keep the Material 3 default it had. */
    private fun tokenized(dark: Boolean)=if(dark) mapOf(
        "primary" to 0xff315cf4,"onPrimary" to 0xffffffff,
        "primaryContainer" to 0xff17254d,"onPrimaryContainer" to 0xffbed0ff,
        "secondary" to 0xff315cf4,"onSecondary" to 0xffffffff,
        "secondaryContainer" to 0xff17254d,"onSecondaryContainer" to 0xffbed0ff,
        "tertiary" to 0xff315cf4,"onTertiary" to 0xffffffff,
        "tertiaryContainer" to 0xff17254d,"onTertiaryContainer" to 0xffbed0ff,
        "background" to 0xff090c11,"onBackground" to 0xffe5e9f2,
        "surface" to 0xff141922,"onSurface" to 0xffe5e9f2,
        "surfaceVariant" to 0xff232934,"onSurfaceVariant" to 0xff969fb1,
        "surfaceTint" to 0x00000000,"surfaceDim" to 0xff090c11,"surfaceBright" to 0xff232934,
        "surfaceContainerLowest" to 0xff090c11,"surfaceContainerLow" to 0xff141922,
        "surfaceContainer" to 0xff1b212c,"surfaceContainerHigh" to 0xff1b212c,"surfaceContainerHighest" to 0xff232934,
        "outline" to 0xff2b3341)
    else mapOf(
        "primary" to 0xff3156f4,"onPrimary" to 0xffffffff,
        "primaryContainer" to 0xffe4eaff,"onPrimaryContainer" to 0xff193dba,
        "secondary" to 0xff3156f4,"onSecondary" to 0xffffffff,
        "secondaryContainer" to 0xffe4eaff,"onSecondaryContainer" to 0xff193dba,
        "tertiary" to 0xff3156f4,"onTertiary" to 0xffffffff,
        "tertiaryContainer" to 0xffe4eaff,"onTertiaryContainer" to 0xff193dba,
        "background" to 0xfff3f4f9,"onBackground" to 0xff171b26,
        "surface" to 0xfffdfdff,"onSurface" to 0xff171b26,
        "surfaceVariant" to 0xffe9ebf2,"onSurfaceVariant" to 0xff62697c,
        "surfaceTint" to 0x00000000,"surfaceDim" to 0xfff3f4f9,"surfaceBright" to 0xffe9ebf2,
        "surfaceContainerLowest" to 0xfff3f4f9,"surfaceContainerLow" to 0xfffdfdff,
        "surfaceContainer" to 0xffeceef5,"surfaceContainerHigh" to 0xffeceef5,"surfaceContainerHighest" to 0xffe9ebf2,
        "outline" to 0xffd8dce8)

    private fun assertBuiltIn(dark: Boolean) {
        val scheme=rexColorScheme(dark,if(dark) DefaultUiTokens.dark else DefaultUiTokens.light)
        val library=if(dark) darkColorScheme() else lightColorScheme()
        val expected=tokenized(dark)
        roleOf.forEach {(name,get)->
            val actual=get(scheme).toArgb()
            expected[name]?.let {assertEquals(name,it.toInt(),actual)}
                ?: assertEquals("$name keeps the Material 3 default",get(library).toArgb(),actual)}
    }

    @Test fun builtInDarkThemeDrawsWhatItDrewBefore() = assertBuiltIn(true)
    @Test fun builtInLightThemeDrawsWhatItDrewBefore() = assertBuiltIn(false)

    @Test fun onlyOnBackgroundMoves() {
        // The built-in themes left onBackground to Material 3 while all 41 presets map it from
        // content. Text drawn straight on the canvas takes the role through
        // contentColorFor(background), so the built-in themes now colour it like every preset.
        for(dark in listOf(true,false)) {
            val tokens=if(dark) DefaultUiTokens.dark else DefaultUiTokens.light
            assertEquals(Color(tokens.getValue("content")).toArgb(),rexColorScheme(dark,tokens).onBackground.toArgb())
        }
        val library=darkColorScheme()
        assertNotEquals(library.onBackground.toArgb(),rexColorScheme(true,DefaultUiTokens.dark).onBackground.toArgb())
    }

    @Test fun aThemeWithoutTokensIsPlainMaterial3() {
        // Compared role by role: ColorScheme does not compare equal across copy().
        val library=darkColorScheme();val scheme=rexColorScheme(true,emptyMap())
        roleOf.forEach {(name,get)->
            assertEquals(name,if(name=="surfaceTint") 0 else get(library).toArgb(),get(scheme).toArgb())}
    }

    /** The mapping the 41 presets went through before the consolidation, kept so the rewrite is
     *  provably inert for them rather than only asserted against my own vocabulary. Everything the
     *  old RexTheme pre-fill put on the base was overwritten by tokens, so this is library defaults
     *  plus the token mapping. */
    private fun legacyPreset(base: ColorScheme,ui: Map<String,Int>): ColorScheme {
        fun c(key: String)=Color(ui.getValue(key))
        return base.copy(primary=c("accentSolid"),onPrimary=c("accentOnSolid"),
            primaryContainer=c("accentContainer"),onPrimaryContainer=c("accentOnContainer"),
            secondary=c("accentSolid"),onSecondary=c("accentOnSolid"),
            secondaryContainer=c("accentContainer"),onSecondaryContainer=c("accentOnContainer"),
            tertiary=c("accentSolid"),onTertiary=c("accentOnSolid"),
            tertiaryContainer=c("accentContainer"),onTertiaryContainer=c("accentOnContainer"),
            background=c("canvas"),onBackground=c("content"),surface=c("surface"),onSurface=c("content"),
            surfaceVariant=c("surfaceRaised"),onSurfaceVariant=c("contentSecondary"),surfaceTint=Color.Transparent,
            surfaceDim=c("canvas"),surfaceBright=c("surfaceRaised"),
            surfaceContainerLowest=c("canvas"),surfaceContainerLow=c("surface"),surfaceContainer=c("surfaceSubtle"),
            surfaceContainerHigh=c("surfaceRaised"),surfaceContainerHighest=c("surfaceRaised"),
            outline=c("strokeSubtle"),outlineVariant=c("stroke"),
            error=c("dangerSolid"),onError=c("dangerOnSolid"),errorContainer=c("dangerContainer"),onErrorContainer=c("dangerOnContainer"))
    }

    @Test fun everyPresetDrawsExactlyWhatItDrewBefore() {
        val path=sequenceOf("src/main/assets/themes/rex-builtins.json","app/src/main/assets/themes/rex-builtins.json").map(::File).first {it.exists()}.path
        ThemeCatalog.parse(File(path).readText()).drop(2).forEach {theme-> // drop the two built-ins, which have their own golden
            val dark=theme.terminal.dark
            val before=legacyPreset(if(dark) darkColorScheme() else lightColorScheme(),theme.ui)
            val after=rexColorScheme(dark,theme.ui)
            roleOf.forEach {(name,get)->assertEquals("${theme.id} $name",get(before).toArgb(),get(after).toArgb())}
        }
    }

}
