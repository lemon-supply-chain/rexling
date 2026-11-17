package dev.lemon.rex

import androidx.compose.runtime.staticCompositionLocalOf

enum class Appearance { SYSTEM, DARK, LIGHT }

data class TerminalPalette(val dark: Boolean, val background: Int, val foreground: Int, val accent: Int, val ansi: List<Int>, val cursorText: Int = 0xffffffff.toInt()) {
    fun foreground(cell: TerminalCell): Int = when {
        cell.attributes and 128 != 0 -> foreground
        cell.attributes and 512 != 0 -> ansi.getOrNull((cell.attributes ushr 11) and 255) ?: cell.foreground
        else -> cell.foreground
    }
    fun background(cell: TerminalCell): Int = when {
        cell.attributes and 256 != 0 -> background
        cell.attributes and 1024 != 0 -> ansi.getOrNull((cell.attributes ushr 19) and 255) ?: cell.background
        else -> cell.background
    }
    companion object {
        private fun rgb(value: Long) = (0xff000000 or value).toInt()
        val Dark = TerminalPalette(true, rgb(0x10141b), rgb(0xdce1eb), rgb(0x315cf4),
            listOf(0x232833,0xf08088,0x7293ff,0xe9b65a,0x7293ff,0xbba2f7,0x83c8d7,0xdce1eb,0x929aaf,0xff9aa3,0x88a4ff,0xffcb75,0x88a4ff,0xcdb4ff,0x9ce1ec,0xf2f5fc).map { rgb(it.toLong()) })
        val Light = TerminalPalette(false, rgb(0xffffff), rgb(0x202432), rgb(0x3156f4),
            listOf(0x202432,0xc03e47,0x3156f4,0xa65b00,0x3156f4,0x873bff,0x087d91,0xe3e5ee,0x62697c,0xd13f50,0x3156f4,0x995200,0x3156f4,0x873bff,0x007d91,0xf4f5fa).map { rgb(it.toLong()) })
    }
}
internal val LocalTerminalPalette = staticCompositionLocalOf { TerminalPalette.Dark }
