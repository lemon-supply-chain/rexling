package dev.lemon.rex

import kotlin.math.abs

/** One-finger horizontal navigation, kept distinct from taps and vertical drags. */
class PaneGesture(private val tapSlop: Float, private val swipeDistance: Float) {
    enum class Result {
        TAP,
        PREVIOUS,
        NEXT,
        NONE,
    }

    private var x = 0f
    private var y = 0f
    private var moved = false
    private var cancelled = true

    fun begin(x: Float, y: Float) {
        this.x = x
        this.y = y
        moved = false
        cancelled = false
    }

    fun move(x: Float, y: Float) {
        val dx = abs(x - this.x)
        val dy = abs(y - this.y)
        if (dx > tapSlop || dy > tapSlop) moved = true
        if (dy >= swipeDistance && dy > dx) cancelled = true
    }

    fun cancel() {
        cancelled = true
    }

    fun finish(x: Float, y: Float): Result {
        move(x, y)
        val dx = x - this.x
        val dy = y - this.y
        val result =
            when {
                cancelled -> Result.NONE
                !moved -> Result.TAP
                abs(dx) >= swipeDistance && abs(dx) > abs(dy) * 1.5f ->
                    if (dx < 0) Result.NEXT else Result.PREVIOUS
                else -> Result.NONE
            }
        cancelled = true
        return result
    }
}
