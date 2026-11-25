package dev.lemon.rex

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.ceil
import kotlin.math.floor

internal data class TerminalKeyboardFocus(
    val top: Float,
    val cursorBottom: Float,
    val contentBottom: Float,
)

/** Keep the viewport anchored while the cursor is hidden during a repaint or scrollback. */
internal class TerminalImePanState {
    private var lastFocus: TerminalKeyboardFocus? = null
    private var viewport: Pair<Int, Int>? = null
    private var offset = 0

    fun position(focus: TerminalKeyboardFocus?, visibleHeight: Int, covered: Int): Int {
        if (focus != null) lastFocus = focus
        val geometry = visibleHeight to covered
        if (viewport != geometry) {
            viewport = geometry
            offset = terminalImePan(lastFocus, visibleHeight, covered)
        } else if (focus != null && covered > 0) {
            // Once the keyboard settles, do not recenter on every cursor move.
            // When the cursor leaves the viewport, reveal the prompt footer and
            // status lines too if they fit above the keyboard; an in-viewport
            // cursor step (an editor arrow) keeps the current offset.
            val content = ceil(focus.contentBottom - visibleHeight).toInt()
            val cursor = ceil(focus.cursorBottom - visibleHeight).toInt()
            val row = (focus.cursorBottom - focus.top).coerceAtLeast(1f)
            val overshoot = focus.cursorBottom - (offset + visibleHeight)
            offset =
                when {
                    focus.top < offset -> floor(focus.top).toInt()
                    overshoot <= 0f -> offset
                    // A cursor that jumped well past the edge (a prompt at the bottom)
                    // brings its footer and status lines; a one-row arrow does not.
                    overshoot > row && content > offset && content <= covered -> content
                    else -> cursor
                }.coerceIn(0, covered)
        }
        return offset
    }
}

/** How much of the window bottom the keyboard covers above the navigation bar, in px. */
private fun androidx.compose.ui.unit.Density.keyboardCover(
    ime: WindowInsets,
    navigation: WindowInsets,
) = (ime.getBottom(this) - navigation.getBottom(this)).coerceAtLeast(0)

/**
 * Only the visible window shrinks for the IME; the PTY keeps its full-height grid.
 *
 * [keyboardChrome] is the full height of chrome shown only with the keyboard (an auto-hidden key
 * toolbar, laid out by [keyboardRevealed]), in px. It is revealed as far as the keyboard covers, so
 * counting the same amount as covered keeps one terminal height, and one PTY size, on every frame.
 */
@Composable
internal fun Modifier.terminalImeViewport(
    ime: WindowInsets,
    navigation: WindowInsets,
    anchor: ImeAnchor = ImeAnchor.CURSOR,
    keyboardChrome: () -> Int = { 0 },
    extendsUnderNavigation: Boolean = false,
    focus: () -> TerminalKeyboardFocus?,
): Modifier {
    if (anchor == ImeAnchor.RESIZE) {
        return layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { child.placeRelative(0, 0) }
        }
    }
    return stableTerminalViewport(
        coverage = { keyboardCoverage(ime, navigation, keyboardChrome, extendsUnderNavigation) },
        focus = focus,
    )
}

internal fun androidx.compose.ui.unit.Density.keyboardCoverage(
    ime: WindowInsets,
    navigation: WindowInsets,
    keyboardChrome: () -> Int,
    extendsUnderNavigation: Boolean = false,
): Int {
    val keyboard = keyboardCover(ime, navigation)
    val covered = if (extendsUnderNavigation) ime.getBottom(this) else keyboard
    return covered + minOf(keyboardChrome().coerceAtLeast(0), keyboard)
}

@Composable
internal fun Modifier.stableTerminalViewport(
    panToCursor: Boolean = true,
    coverage: androidx.compose.ui.unit.Density.() -> Int,
    focus: () -> TerminalKeyboardFocus?,
): Modifier {
    val pan = remember(panToCursor) { TerminalImePanState() }
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedHeight) {
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { child.placeRelative(0, 0) }
        } else {
            val covered = coverage().coerceAtLeast(0)
            val fullHeight = constraints.maxHeight + covered
            val child =
                measurable.measure(constraints.copy(minHeight = fullHeight, maxHeight = fullHeight))
            layout(child.width, constraints.maxHeight) {
                // Read cursor state during placement, not composition or measurement.
                child.placeRelative(
                    0,
                    if (panToCursor) -pan.position(focus(), constraints.maxHeight, covered) else 0,
                )
            }
        }
    }
}

internal fun terminalImePan(focus: TerminalKeyboardFocus?, visibleHeight: Int, covered: Int): Int {
    if (focus == null || covered <= 0) return 0
    // Include status/help lines below the prompt when they fit alongside it.
    // A distant status bar must not push a top-of-screen cursor out of view.
    val bottom =
        if (focus.contentBottom - focus.top <= visibleHeight) focus.contentBottom
        else focus.cursorBottom
    val limit = minOf(covered, floor(focus.top).toInt().coerceAtLeast(0))
    return ceil((bottom - visibleHeight).coerceAtLeast(0f)).toInt().coerceAtMost(limit)
}

/**
 * Shows only as much of this chrome as the keyboard currently covers, so it rides in and out with
 * the keyboard's own animation instead of switching on a visibility flag a frame or two late.
 * [onFullHeight] receives the unclipped height for [terminalImeViewport].
 */
internal fun Modifier.keyboardRevealed(
    ime: WindowInsets,
    navigation: WindowInsets,
    onFullHeight: (Int) -> Unit,
): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val child = measurable.measure(constraints.copy(minHeight = 0))
        onFullHeight(child.height)
        val shown = minOf(child.height, keyboardCover(ime, navigation))
        layout(child.width, shown) { child.placeRelative(0, 0) }
    }
