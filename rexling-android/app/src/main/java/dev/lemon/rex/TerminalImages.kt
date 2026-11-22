package dev.lemon.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer

/** Prepare a bitmap on the packet decoder's dispatcher, before the frame reaches the view. */
internal fun prepareTerminalBitmap(width: Int,height: Int,data: ByteBuffer): Bitmap {
    val rgba=data.array()
    val start=data.arrayOffset()+data.position()
    val end=start+width*height*4
    var alpha=start+3
    while(alpha<end && rgba[alpha]==(-1).toByte()) alpha+=4
    return if(alpha>=end) {
        // Opaque RGBA bytes already match the native pixel layout of a tightly packed Android bitmap.
        Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {
            if(it.rowBytes==width*4) {
                it.copyPixelsFromBuffer(ByteBuffer.wrap(rgba,start,end-start))
                it.setHasAlpha(false)
            } else {
                it.setPixels(argbPixels(rgba,start,width*height),0,width,0,0,width,height)
            }
        }
    } else {
        Bitmap.createBitmap(argbPixels(rgba,start,width*height),width,height,Bitmap.Config.ARGB_8888)
    }
}

private fun argbPixels(rgba: ByteArray,start: Int,count: Int): IntArray = IntArray(count) {index->
    val pixel=start+index*4
    ((rgba[pixel+3].toInt() and 255) shl 24) or ((rgba[pixel].toInt() and 255) shl 16) or
        ((rgba[pixel+1].toInt() and 255) shl 8) or (rgba[pixel+2].toInt() and 255)
}

internal class TerminalImages {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun draw(canvas: Canvas,screen: TerminalScreen,layer: Int,left: Float,top: Float,right: Float,bottom: Float,cw: Float,ch: Float) {
        val sx=cw/screen.imageCellWidth.coerceAtLeast(1);val sy=ch/screen.imageCellHeight.coerceAtLeast(1)
        val save=canvas.save();canvas.clipRect(left,top,right,bottom)
        for(p in screen.images) {
            if(p.layer!=layer) continue
            val x=left+p.col*cw+p.offsetX*sx;val y=top+p.row*ch+p.offsetY*sy
            val target=RectF(x,y,x+p.width*sx,y+p.height*sy)
            if(!RectF.intersects(target,RectF(left,top,right,bottom))) continue
            canvas.drawBitmap(checkNotNull(p.image.bitmap),Rect(p.sourceX,p.sourceY,p.sourceX+p.sourceWidth,p.sourceY+p.sourceHeight),target,paint)
        }
        canvas.restoreToCount(save)
    }
}
