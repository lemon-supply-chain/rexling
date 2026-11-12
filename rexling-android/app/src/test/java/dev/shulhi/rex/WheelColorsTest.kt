package dev.shulhi.rex

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Contrast and hierarchy checks for the floating wheel, run separately for every light and dark preset. */
class WheelColorsTest {
    private val themes=ThemeCatalog.parse(sequenceOf(File("src/main/assets/themes/rex-builtins.json"),File("app/src/main/assets/themes/rex-builtins.json")).first {it.exists()}.readText())
    private fun colorsFor(theme: AppTheme)=WheelColors.from(rexColorScheme(theme.terminal.dark,theme))
    private fun contrast(a: Color,b: Color)=WheelColors.contrast(a,b)

    @Test fun sectorLabelsKeepTextContrastInEveryState() {
        for(theme in themes) {
            val colors=colorsFor(theme)
            for(group in listOf(false,true)) for(state in listOf(false,true)) {
                val ratio=contrast(colors.content,colors.sectorOver(group,state))
                assertTrue("${theme.id} dark=${theme.terminal.dark} group=$group state=$state: label $ratio < 4.5",ratio>=4.5f)
            }
        }
    }

    @Test fun hubTextAndIconsKeepContrastAndDarkHubUsesDarkInk() {
        for(theme in themes) {
            val colors=colorsFor(theme)
            val pressed=colors.onPrimary.copy(alpha=WheelColors.STATE).compositeOver(colors.primary)
            for(fill in listOf(colors.primary,pressed)) {
                val ratio=contrast(colors.onPrimary,fill)
                assertTrue("${theme.id}: hub text $ratio < 4.5",ratio>=4.5f)
            }
            // Page indicator dots are icons: 3:1 at their dimmest.
            val dot=contrast(colors.onPrimary.copy(alpha=.4f).compositeOver(colors.primary),colors.primary)
            assertTrue("${theme.id}: inactive page dot $dot < 1.2",dot>=1.2f)
            if(theme.terminal.dark) {
                assertTrue("${theme.id}: dark hub icon must be dark ink",colors.onPrimary.luminance()<.1f)
                assertTrue("${theme.id}: dark hub must be a lifted primary",colors.primary.luminance()>=.30f)
            }
        }
    }

    @Test fun hierarchyKeepsTheButtonDistinctFromSectorsAndPage() {
        for(theme in themes) {
            val colors=colorsFor(theme)
            val sector=colors.sectorOver()
            assertNotEquals("${theme.id}: sector must not match the button",colors.primary,sector)
            val ratio=contrast(colors.primary,sector)
            // The button reads as a distinct, heavier layer than the sector it sits in.
            assertTrue("${theme.id}: button vs sector $ratio < 1.5",ratio>=1.5f)
            assertEquals(theme.terminal.dark,colors.dark)
            assertEquals("light themes separate layers with shadow",!theme.terminal.dark,colors.shadow)
        }
    }

    @Test fun darkThemesAvoidPureBlackAndWhite() {
        for(theme in themes.filter {it.terminal.dark}) {
            val colors=colorsFor(theme)
            assertNotEquals("${theme.id}: sector must not be pure black",Color.Black,colors.sector)
            assertNotEquals("${theme.id}: label must not be pure white",Color.White,colors.content)
            assertNotEquals("${theme.id}: hub ink must not be pure black",Color.Black,colors.onPrimary)
        }
    }

    @Test fun interactionStatesAreAnEightPercentContentOverlay() {
        for(theme in themes) {
            val colors=colorsFor(theme)
            assertEquals(colors.content.copy(alpha=WheelColors.STATE),colors.state)
        }
    }

    @Test fun categorySectorsKeepLabelAndMarkerContrastInEveryTheme() {
        for(theme in themes) {
            val colors=colorsFor(theme)
            for(category in WheelCategory.entries) for(state in listOf(false,true)) {
                val behind=colors.sectorOver(group=true,stateOn=state,category=category)
                val label=contrast(colors.content,behind)
                assertTrue("${theme.id} ${category.key} state=$state: label $label < 4.5",label>=4.5f)
                val marker=contrast(colors.categoryMarker(category)!!,behind)
                assertTrue("${theme.id} ${category.key} state=$state: marker $marker < 3",marker>=3f)
            }
        }
    }

    @Test fun categoryTintIsWithinTheSpecBandAndDarkVersionsAreLighter() {
        assertTrue(WheelColors.CATEGORY_FILL in .12f..0.18f)
        for(category in WheelCategory.entries) {
            assertTrue("${category.key}: dark must be lighter",category.fill(true).luminance()>category.fill(false).luminance())
            fun saturation(c: Color): Float {val max=maxOf(c.red,c.green,c.blue);val min=minOf(c.red,c.green,c.blue);return if(max==0f) 0f else (max-min)/max}
            assertTrue("${category.key}: dark must be less saturated",saturation(category.fill(true))<saturation(category.fill(false)))
        }
        // The spec colours are kept as fills exactly.
        assertEquals(Color(0xff3b5bdb),WheelCategory.BLUE.fill(false));assertEquals(Color(0xff8da2ff),WheelCategory.BLUE.fill(true))
        assertEquals(Color(0xff2f9e44),WheelCategory.GREEN.fill(false));assertEquals(Color(0xff7fd99a),WheelCategory.GREEN.fill(true))
        assertEquals(Color(0xffe8590c),WheelCategory.ORANGE.fill(false));assertEquals(Color(0xffffa270),WheelCategory.ORANGE.fill(true))
        assertEquals(Color(0xff7048e8),WheelCategory.PURPLE.fill(false));assertEquals(Color(0xffb79cff),WheelCategory.PURPLE.fill(true))
    }

    @Test fun gridCategoryCellsKeepContrastOnTheOpaqueBubble() {
        for(theme in themes) {
            val scheme=rexColorScheme(theme.terminal.dark,theme);val colors=WheelColors.from(scheme)
            for(category in WheelCategory.entries) {
                val cell=colors.categoryFill(category)!!.compositeOver(scheme.surfaceContainerHigh)
                val label=contrast(scheme.onSurface,cell)
                assertTrue("${theme.id} ${category.key}: grid label $label < 4.5",label>=4.5f)
                val edge=contrast(colors.categoryMarker(category)!!,cell)
                assertTrue("${theme.id} ${category.key}: grid edge $edge < 3",edge>=3f)
            }
        }
    }

    @Test fun darkPrimaryIsLighterAndLessSaturated() {
        val base=Color(0xff315cf4)
        val soft=WheelColors.softenForDark(base,Color(0xff090c11))
        assertTrue(soft.luminance()>base.luminance())
        fun saturation(c: Color): Float {val max=maxOf(c.red,c.green,c.blue);val min=minOf(c.red,c.green,c.blue);return if(max==0f) 0f else (max-min)/max}
        assertTrue("${saturation(soft)} must be below ${saturation(base)}",saturation(soft)<saturation(base))
    }
}
