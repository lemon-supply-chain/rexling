package dev.lemon.rex

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Picture
import android.os.Build
import android.view.SurfaceView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.animation.doOnEnd
import kotlin.math.hypot

@RequiresApi(Build.VERSION_CODES.S)
internal fun Activity.installLaunchReveal() {
    splashScreen.setOnExitAnimationListener { splash ->
        val icon = splash.iconView
        if (icon == null || icon is SurfaceView || icon.width == 0 || icon.height == 0 ||
            !ValueAnimator.areAnimatorsEnabled()
        ) {
            splash.remove()
            return@setOnExitAnimationListener
        }

        val iconPosition = IntArray(2)
        val splashPosition = IntArray(2)
        icon.getLocationInWindow(iconPosition)
        splash.getLocationInWindow(splashPosition)
        val logo = Picture().apply {
            val recording = beginRecording(icon.width, icon.height)
            try {
                icon.draw(recording)
            } finally {
                endRecording()
            }
        }
        val paper = LaunchPaper(
            this,
            getColor(R.color.launch_paper),
            logo,
            (iconPosition[0] - splashPosition[0]).toFloat(),
            (iconPosition[1] - splashPosition[1]).toFloat(),
        )
        splash.background = null
        splash.addView(paper, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        icon.visibility = View.INVISIBLE

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560L
            interpolator = LinearInterpolator()
            addUpdateListener { paper.progress = it.animatedFraction }
            doOnEnd { splash.remove() }
            start()
        }
    }
}

private class LaunchPaper(
    context: Context,
    private val paperColor: Int,
    private val logo: Picture,
    private val logoX: Float,
    private val logoY: Float,
) : View(context) {
    private class Piece(val side: Int) {
        val outline = Path()
        val puncture = Path()
    }

    private val upper = Piece(-1)
    private val lower = Piece(1)
    private val centerX = logoX + logo.width / 2f
    private val centerY = logoY + logo.height / 2f
    private val grain = floatArrayOf(0.2f, -0.5f, 0.7f, -0.9f, -0.3f, 1f, 0.5f, -0.8f, 0.3f, 0.9f, -0.6f)
    private val angle = 130f
    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0.035f) {
            drawSheet(canvas)
            return
        }

        val span = minOf(width, height).toFloat()
        val extent = hypot(width.toFloat(), height.toFloat())
        val tearing = ((progress - 0.035f) / 0.51f).coerceIn(0f, 1f)
        val release = ((progress - 0.38f) / 0.62f).coerceIn(0f, 1f)
        val departure = release * release
        buildPiece(upper, span, extent, tearing)
        buildPiece(lower, span, extent, tearing)
        drawPiece(canvas, upper, extent, departure)
        drawPiece(canvas, lower, extent, departure)
    }

    // The geometry is in diagonal coordinates. The claw opens the seam from
    // upper right to lower left while paper ahead of the tear stays joined.
    private fun buildPiece(piece: Piece, span: Float, extent: Float, tearing: Float) {
        val side = piece.side
        val start = -span * 0.48f - extent * 0.70f * tearing
        val front = -span * 0.40f + (extent + span * 0.40f) * tearing
        val wall = side * extent
        piece.outline.rewind()
        piece.outline.moveTo(-extent, wall)
        for (step in 0..96) {
            val x = extent * (step / 48f - 1f)
            val open = minOf((x - start) / (span * 0.11f), (front - x) / (span * 0.17f)).coerceIn(0f, 1f)
            val crack = grain[(step * 7 + step / 5) % grain.size] * span * 0.012f
            val y = crack + side * (span * (0.023f + 0.045f * tearing) * open - 0.6f)
            piece.outline.lineTo(x, y)
        }
        piece.outline.lineTo(extent, wall)
        piece.outline.close()

        piece.puncture.rewind()
        val chase = if (side < 0) 0f else 0.07f
        val length = ((tearing - chase) / 0.48f).coerceIn(0f, 1f)
        if (length == 0f) return
        val x = span * (if (side < 0) -0.41f else -0.33f)
        val y = side * span * 0.17f
        val reach = span * 0.58f * length
        val slit = span * 0.028f * length
        piece.puncture.moveTo(x, y)
        piece.puncture.lineTo(x + reach * 0.19f, y - slit * 0.49f)
        piece.puncture.lineTo(x + reach * 0.48f, y - slit)
        piece.puncture.lineTo(x + reach * 0.71f, y - slit * 0.73f)
        piece.puncture.lineTo(x + reach, y + slit * 0.19f)
        piece.puncture.lineTo(x + reach * 0.66f, y + slit * 0.76f)
        piece.puncture.lineTo(x + reach * 0.43f, y + slit * 0.34f)
        piece.puncture.lineTo(x + reach * 0.20f, y + slit * 0.56f)
        piece.puncture.close()
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, extent: Float, departure: Float) {
        val save = canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(angle + piece.side * 10f * departure)
        canvas.translate(piece.side * extent * departure * 0.09f, piece.side * extent * departure * 0.87f)

        canvas.clipPath(piece.outline)
        canvas.clipOutPath(piece.puncture)
        canvas.rotate(-angle)
        canvas.translate(-centerX, -centerY)
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        drawSheet(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawSheet(canvas: Canvas) {
        canvas.drawColor(paperColor)
        val save = canvas.save()
        canvas.translate(logoX, logoY)
        canvas.drawPicture(logo)
        canvas.restoreToCount(save)
    }
}
