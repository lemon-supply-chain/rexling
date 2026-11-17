package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.roundToInt

/**
 * Hardware canvases cannot blur before API 31, so the contact shadow is rendered
 * once per tile size into a small bitmap and then drawn as an image.
 */
internal object TileShadow {
    class Image(val bitmap: Bitmap, val pad: Int)

    private val cache=mutableMapOf<String,Image>()

    @Synchronized fun get(key: String,outline: Path,widthPx: Int,heightPx: Int,unitsToPx: Float,
        blurPx: Float,dyPx: Float,color: Int,alpha: Float): Image? {
        cache[key]?.let {return it}
        val pad=(blurPx*2.5f+dyPx+2f).roundToInt()
        val wide=widthPx+pad*2
        val tall=heightPx+pad*2
        if(wide<=0 || tall<=0) return null
        val bitmap=Bitmap.createBitmap(wide,tall,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPath(Path(outline).apply {
            transform(Matrix().apply {
                setScale(unitsToPx,unitsToPx)
                postTranslate(pad.toFloat(),pad.toFloat()+dyPx)
            })
        },Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color=color
            this.alpha=(alpha*255f).toInt().coerceIn(0,255)
            maskFilter=BlurMaskFilter(blurPx,BlurMaskFilter.Blur.NORMAL)
        })
        return Image(bitmap,pad).also {cache[key]=it}
    }
}
