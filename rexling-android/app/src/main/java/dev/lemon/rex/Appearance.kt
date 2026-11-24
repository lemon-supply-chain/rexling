package dev.lemon.rex

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.pow

enum class Appearance {
    SYSTEM,
    DARK,
    LIGHT,
}

data class TerminalPalette(
    val dark: Boolean,
    val background: Int,
    val foreground: Int,
    val accent: Int,
    val ansi: List<Int>,
    val cursorText: Int = 0xffffffff.toInt(),
) {
    fun foreground(cell: TerminalCell): Int =
        when {
            cell.attributes and 128 != 0 -> foreground
            cell.attributes and 512 != 0 ->
                ansi.getOrNull((cell.attributes ushr 11) and 255) ?: cell.foreground
            else -> cell.foreground
        }

    fun background(cell: TerminalCell): Int =
        when {
            cell.attributes and 256 != 0 -> background
            cell.attributes and 1024 != 0 ->
                ansi.getOrNull((cell.attributes ushr 19) and 255) ?: cell.background
            else -> cell.background
        }

    companion object {
        private fun rgb(value: Long) = (0xff000000 or value).toInt()

        val Dark =
            TerminalPalette(
                true,
                rgb(0x10141b),
                rgb(0xdce1eb),
                rgb(0x315cf4),
                listOf(
                        0x232833,
                        0xf08088,
                        0x7293ff,
                        0xe9b65a,
                        0x7293ff,
                        0xbba2f7,
                        0x83c8d7,
                        0xdce1eb,
                        0x929aaf,
                        0xff9aa3,
                        0x88a4ff,
                        0xffcb75,
                        0x88a4ff,
                        0xcdb4ff,
                        0x9ce1ec,
                        0xf2f5fc,
                    )
                    .map { rgb(it.toLong()) },
            )
        val Light =
            TerminalPalette(
                false,
                rgb(0xffffff),
                rgb(0x202432),
                rgb(0x3156f4),
                listOf(
                        0x202432,
                        0xc03e47,
                        0x3156f4,
                        0xa65b00,
                        0x3156f4,
                        0x873bff,
                        0x087d91,
                        0xe3e5ee,
                        0x62697c,
                        0xd13f50,
                        0x3156f4,
                        0x995200,
                        0x3156f4,
                        0x873bff,
                        0x007d91,
                        0xf4f5fa,
                    )
                    .map { rgb(it.toLong()) },
            )
    }
}

internal val LocalTerminalPalette = staticCompositionLocalOf { TerminalPalette.Dark }

/** Keep text readable where a theme color meets a color pinned by the terminal program. */
internal class TerminalTextColors {
    private val adjustedColors = HashMap<Long, Int>()

    fun foreground(palette: TerminalPalette, cell: TerminalCell): Int {
        val inverse = cell.attributes and 8 != 0
        val text = if (inverse) palette.background(cell) else palette.foreground(cell)
        val foregroundFollowsTheme =
            cell.attributes and 128 != 0 ||
                cell.attributes and 512 != 0 &&
                    ((cell.attributes ushr 11) and 255) < palette.ansi.size
        val backgroundFollowsTheme =
            cell.attributes and 256 != 0 ||
                cell.attributes and 1024 != 0 &&
                    ((cell.attributes ushr 19) and 255) < palette.ansi.size
        if (foregroundFollowsTheme == backgroundFollowsTheme) return text

        val surface = if (inverse) palette.foreground(cell) else palette.background(cell)
        val key = (text.toLong() shl 32) or (surface.toLong() and 0xffffffffL)
        adjustedColors[key]?.let {
            return it
        }
        val adjusted = readableColor(text, surface)
        if (adjustedColors.size >= 512) adjustedColors.clear()
        adjustedColors[key] = adjusted
        return adjusted
    }

    private fun readableColor(text: Int, surface: Int): Int {
        val surfaceLuminance = luminance(surface)
        if (contrast(luminance(text), surfaceLuminance) >= MIN_CONTRAST) return text
        val toward =
            if (contrast(0.0, surfaceLuminance) >= contrast(1.0, surfaceLuminance)) 0 else 255
        var low = 1
        var high = 255
        while (low < high) {
            val amount = (low + high) / 2
            val candidate = blend(text, toward, amount)
            if (contrast(luminance(candidate), surfaceLuminance) >= MIN_CONTRAST) high = amount
            else low = amount + 1
        }
        return blend(text, toward, low)
    }

    private fun blend(color: Int, toward: Int, amount: Int): Int {
        fun mix(shift: Int) =
            (((color ushr shift) and 255) * (255 - amount) + toward * amount + 127) / 255
        return (color and 0xff000000.toInt()) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private companion object {
        const val MIN_CONTRAST = 4.5
        val linear =
            DoubleArray(256) { channel ->
                val value = channel / 255.0
                if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }

        fun luminance(color: Int) =
            .2126 * linear[(color ushr 16) and 255] +
                .7152 * linear[(color ushr 8) and 255] +
                .0722 * linear[color and 255]

        fun contrast(first: Double, second: Double) =
            (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }
}
