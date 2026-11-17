package dev.lemon.rex

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import androidx.annotation.RequiresApi
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.roundToInt

data class TerminalMetrics(val cellWidth: Float, val cellHeight: Float, val baseline: Float)

/** Nerd Font Mono keeps icon glyphs and text on the same fixed grid on every device. */
class TerminalFonts(private val context: Context,private val compositeFallback: Boolean=Build.VERSION.SDK_INT>=29) {
    private val faces=if(compositeFallback && Build.VERSION.SDK_INT>=29) Api29.faces(context.resources)
        else listOf(R.font.jetbrains_nerd_regular,R.font.jetbrains_nerd_bold,R.font.jetbrains_nerd_italic,R.font.jetbrains_nerd_bold_italic).map(context.resources::getFont)
    val regular: Typeface get()=faces[0]
    val bold: Typeface get()=faces[1]
    val italic: Typeface get()=faces[2]
    val boldItalic: Typeface get()=faces[3]
    private val symbols by lazy {context.resources.getFont(R.font.noto_sans_symbols2)}
    private val probe by lazy {Paint().apply {textSize=32f}}
    private val fallback=object: LinkedHashMap<Pair<Int,String>,Typeface>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int,String>,Typeface>?)=size>512
    }
    /** Android 9 has no custom font chains; resolve missing non-ASCII cells explicitly. */
    fun typefaceFor(style: Int,text: String): Typeface {
        val primary=faces[style and 3]
        if(compositeFallback && Build.VERSION.SDK_INT>=29 || text.all {it in ' '..'~'}) return primary
        return fallback.getOrPut((style and 3) to text) {
            probe.typeface=primary
            if(probe.hasGlyph(text)) primary else {
                probe.typeface=symbols
                if(probe.hasGlyph(text)) symbols else primary
            }
        }
    }

    @RequiresApi(29)
    private object Api29 {
        @Volatile private var cached: List<Typeface>?=null
        fun faces(resources: Resources): List<Typeface> = cached ?: synchronized(this) {
            cached ?: run {
                val ids=listOf(R.font.jetbrains_nerd_regular,R.font.jetbrains_nerd_bold,R.font.jetbrains_nerd_italic,R.font.jetbrains_nerd_bold_italic)
                fun style(index: Int)=FontStyle(if(index and 1!=0) 700 else 400,if(index and 2!=0) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                fun font(index: Int)=Font.Builder(resources,ids[index]).setWeight(if(index and 1!=0) 700 else 400).setSlant(style(index).slant).build()
                val builder=FontFamily.Builder(font(0));for(index in 1..3) builder.addFont(font(index))
                val primary=builder.build()
                val symbols=FontFamily.Builder(Font.Builder(resources,R.font.noto_sans_symbols2).build()).build()
                List(4) {index->Typeface.CustomFallbackBuilder(primary).addCustomFallback(symbols).setSystemFallback("monospace").setStyle(style(index)).build()}
                    .also {cached=it}
            }
        }
    }

    fun configure(paint: Paint, sizeSp: Float): TerminalMetrics {
        paint.typeface = regular
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
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
