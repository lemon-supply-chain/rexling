package dev.lemon.rex

import android.content.Context
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import android.util.TypedValue
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.roundToInt

data class TerminalMetrics(val cellWidth: Float, val cellHeight: Float, val baseline: Float)

/** Nerd Font Mono keeps icon glyphs and text on the same fixed grid on every device. */
class TerminalFonts(
    private val context: Context,
    private val compositeFallback: Boolean = Build.VERSION.SDK_INT >= 29,
) {
    private val faces =
        if (compositeFallback && Build.VERSION.SDK_INT >= 29) Api29.faces(context.resources)
        else List(4) { context.resources.getFont(R.font.jetbrains_nerd_regular) }
    val regular: Typeface
        get() = faces[0]

    val bold: Typeface
        get() = faces[1]

    val italic: Typeface
        get() = faces[2]

    val boldItalic: Typeface
        get() = faces[3]

    /**
     * Android 9 cannot synthesize a weight or slant from a single font file, while the API 29+
     * fallback family applies the requested [FontStyle]. Callers fake bold and slant while this is
     * true.
     */
    val syntheticStyles: Boolean
        get() = !(compositeFallback && Build.VERSION.SDK_INT >= 29)

    private val symbols by lazy { context.resources.getFont(R.font.noto_sans_symbols2) }
    private val probe by lazy { Paint().apply { textSize = 32f } }
    private val fallback =
        object : LinkedHashMap<Pair<Int, String>, Typeface>(64, .75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Pair<Int, String>, Typeface>?,
            ) = size > 512
        }

    /** Android 9 has no custom font chains; resolve missing non-ASCII cells explicitly. */
    fun typefaceFor(style: Int, text: String): Typeface {
        val primary = faces[style and 3]
        if (compositeFallback && Build.VERSION.SDK_INT >= 29 || text.all { it in ' '..'~' })
            return primary
        return fallback.getOrPut((style and 3) to text) {
            probe.typeface = primary
            if (probe.hasGlyph(text)) primary
            else {
                probe.typeface = symbols
                if (probe.hasGlyph(text)) symbols else primary
            }
        }
    }

    @RequiresApi(29)
    private object Api29 {
        @Volatile private var cached: List<Typeface>? = null

        fun faces(resources: Resources): List<Typeface> =
            cached
                ?: synchronized(this) {
                    cached
                        ?: run {
                            fun style(index: Int) =
                                FontStyle(
                                    if (index and 1 != 0) 700 else 400,
                                    if (index and 2 != 0) FontStyle.FONT_SLANT_ITALIC
                                    else FontStyle.FONT_SLANT_UPRIGHT,
                                )
                            val primary =
                                FontFamily.Builder(
                                        Font.Builder(resources, R.font.jetbrains_nerd_regular)
                                            .build(),
                                    )
                                    .build()
                            val symbols =
                                FontFamily.Builder(
                                        Font.Builder(resources, R.font.noto_sans_symbols2).build(),
                                    )
                                    .build()
                            List(4) { index ->
                                    Typeface.CustomFallbackBuilder(primary)
                                        .addCustomFallback(symbols)
                                        .setSystemFallback("monospace")
                                        .setStyle(style(index))
                                        .build()
                                }
                                .also { cached = it }
                        }
                }
    }

    fun configure(paint: Paint, sizeSp: Float): TerminalMetrics {
        paint.typeface = regular
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
        paint.textSize =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sizeSp,
                context.resources.displayMetrics,
            )
        paint.textScaleX = 1f
        paint.isAntiAlias = true
        paint.isSubpixelText = true
        paint.hinting = Paint.HINTING_ON
        paint.fontFeatureSettings = "'liga' 0, 'calt' 0, 'kern' 0"
        val advance = paint.measureText("M").coerceAtLeast(1f)
        val width = advance.roundToInt().coerceAtLeast(1).toFloat()
        // Match glyph advance to the integer pixel grid instead of adding tracking.
        paint.textScaleX = width / advance
        val metrics = paint.fontMetrics
        val height = ceil(metrics.descent - metrics.ascent).coerceAtLeast(1f)
        return TerminalMetrics(width, height, -metrics.ascent)
    }
}
