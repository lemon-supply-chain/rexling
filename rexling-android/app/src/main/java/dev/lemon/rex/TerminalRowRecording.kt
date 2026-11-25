package dev.lemon.rex

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.RequiresApi

/** Retain hardware display lists rather than replaying Pictures into every frame. */
internal interface TerminalRowRecording {
    val valid: Boolean

    fun record(width: Int, height: Int, draw: (Canvas) -> Unit)

    fun draw(canvas: Canvas)

    fun release()

    companion object {
        fun create(hardware: Boolean): TerminalRowRecording =
            if (hardware && Build.VERSION.SDK_INT >= 29) HardwareRow() else PictureRow()
    }
}

private class PictureRow : TerminalRowRecording {
    private val picture = Picture()
    override var valid = false
        private set

    override fun record(width: Int, height: Int, draw: (Canvas) -> Unit) {
        val canvas = picture.beginRecording(width, height)
        try {
            draw(canvas)
        } finally {
            picture.endRecording()
        }
        valid = true
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPicture(picture)
    }

    override fun release() {
        valid = false
    }
}

@RequiresApi(29)
private class HardwareRow : TerminalRowRecording {
    private val node =
        RenderNode("Terminal row").apply {
            // Glyphs are clipped inside each row; the view clips the outside edge.
            // Avoid additional fractional row-edge clips while panes are moving.
            setClipToBounds(false)
        }
    override val valid: Boolean
        get() = node.hasDisplayList()

    override fun record(width: Int, height: Int, draw: (Canvas) -> Unit) {
        node.setPosition(0, 0, width, height)
        val canvas = node.beginRecording(width, height)
        try {
            draw(canvas)
        } finally {
            node.endRecording()
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRenderNode(node)
    }

    override fun release() {
        node.discardDisplayList()
    }
}
