package dev.lemon.rex

/** Fractional local scrollback movement; application wheel events never use this model. */
internal class TerminalScrollMotion {
    var remainder = 0f
        private set

    var target: Long? = null
        private set

    fun reset() {
        remainder = 0f
        target = null
    }

    fun settle() {
        remainder = 0f
    }

    fun drag(pixels: Float, scroll: TerminalScroll, height: Float): Int {
        if (!pixels.isFinite() || !height.isFinite() || height <= 0 || scroll.available == 0L) {
            reset()
            return 0
        }
        remainder += pixels
        val lines = (remainder / height).toInt()
        remainder -= lines * height
        val from = (target ?: scroll.offset).coerceIn(0, scroll.available)
        val next = (from + lines).coerceIn(0, scroll.available)
        if (next == 0L && remainder < 0 || next == scroll.available && remainder > 0) remainder = 0f
        target = next
        return (next - from).toInt()
    }

    fun slide(
        scroll: TerminalScroll,
        height: Float,
        above: Int,
        below: Int,
        active: Boolean,
    ): Float {
        var pending = target?.let { it - scroll.offset } ?: 0L
        if (!active && (pending == 0L || kotlin.math.abs(pending) > maxOf(above, below) + 2)) {
            target = null
            pending = 0
        }
        return (pending * height + remainder).coerceIn(-above * height, below * height)
    }
}
