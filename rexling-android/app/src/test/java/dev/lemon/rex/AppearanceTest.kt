package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    private fun cell(flags: Int, fg: Int = 0xff123456.toInt(), bg: Int = 0xff654321.toInt()) =
        TerminalCell(0, 0, "X", fg, bg, flags, 1)

    @Test
    fun defaultColorsFollowAppearanceAndExplicitRgbIsPreserved() {
        for (theme in listOf(TerminalPalette.Dark, TerminalPalette.Light)) {
            assertEquals(theme.foreground, theme.foreground(cell(128)))
            assertEquals(theme.background, theme.background(cell(256)))
            assertEquals(0xff123456.toInt(), theme.foreground(cell(0)))
            assertEquals(0xff654321.toInt(), theme.background(cell(0)))
        }
        assertNotEquals(TerminalPalette.Dark.background, TerminalPalette.Light.background)
    }

    @Test
    fun ansiColorsUseTheirIndexAndExtendedColorsStayExact() {
        val fg = cell(512 or (2 shl 11))
        val bg = cell(1024 or (4 shl 19))
        for (theme in listOf(TerminalPalette.Dark, TerminalPalette.Light)) {
            assertEquals(theme.ansi[2], theme.foreground(fg))
            assertEquals(theme.ansi[4], theme.background(bg))
            assertEquals(0xff123456.toInt(), theme.foreground(cell(512 or (200 shl 11))))
        }
    }

    @Test
    fun aPinnedLightPromptRemainsReadableAfterSwitchingToAnAmberTheme() {
        val textColors = TerminalTextColors()
        val prompt = cell(128, 0xff202328.toInt(), 0xfff4f4f4.toInt())
        val amber = TerminalPalette.Dark.copy(foreground = 0xfff5c67c.toInt())
        assertEquals(
            TerminalPalette.Light.foreground,
            textColors.foreground(TerminalPalette.Light, prompt),
        )
        val resolved = textColors.foreground(amber, prompt)
        val red = (resolved ushr 16) and 255
        val green = (resolved ushr 8) and 255
        val blue = resolved and 255
        assertTrue(
            "The text stays amber while darkening against the pale prompt",
            red in 100..135 && red > green && green > blue,
        )
        assertEquals(prompt.background, amber.background(prompt))
        assertEquals(
            TerminalPalette.Light.foreground,
            textColors.foreground(TerminalPalette.Light, prompt),
        )
    }

    @Test
    fun pinnedTextOnThemedBackgroundAdjustsInNormalAndInvertedCells() {
        val textColors = TerminalTextColors()
        val dark = TerminalPalette.Dark
        val foreground = 0xff202328.toInt()
        val pinnedText = cell(256, foreground, 0xff151a20.toInt())
        val adjusted = textColors.foreground(dark, pinnedText)
        assertTrue((adjusted and 255) > 110)
        assertEquals(foreground, textColors.foreground(dark, pinnedText.copy(attributes = 0)))
        assertEquals(
            adjusted,
            textColors.foreground(
                dark.copy(foreground = dark.background),
                pinnedText.copy(attributes = 8 or 128, background = foreground),
            ),
        )
        assertEquals(
            dark.foreground,
            textColors.foreground(dark, pinnedText.copy(attributes = 128 or 256)),
        )
    }
}
