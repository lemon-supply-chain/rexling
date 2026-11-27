package dev.lemon.rex

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardBarLayoutTest {
    @Test
    fun keyWidthUsesMinimumWhenKeysOverflowAndStretchesWhenWide() {
        assertEquals(KeyboardBarMinKeyWidth, keyboardBarKeyWidth(250.dp, 7))
        assertEquals(67.333f, keyboardBarKeyWidth(210.dp, 3).value, .01f)
        assertEquals(KeyboardBarMinKeyWidth, keyboardBarKeyWidth(300.dp, 0))
    }

    @Test
    fun fadingEdgeStrengthsTrackScrollBounds() {
        assertEquals(0f to 0f, keyboardBarFadingEdgeStrengths(0, 0, 50f))
        assertEquals(0f to 0f, keyboardBarFadingEdgeStrengths(20, 100, 0f))

        assertEquals(0f to 1f, keyboardBarFadingEdgeStrengths(0, 200, 50f))

        val (startHalf, endHalf) = keyboardBarFadingEdgeStrengths(25, 200, 50f)
        assertEquals(.5f, startHalf, .001f)
        assertEquals(1f, endHalf, .001f)

        assertEquals(1f to 1f, keyboardBarFadingEdgeStrengths(100, 200, 50f))

        val (startNearEnd, endNearEnd) = keyboardBarFadingEdgeStrengths(180, 200, 50f)
        assertEquals(1f, startNearEnd, .001f)
        assertEquals(.4f, endNearEnd, .001f)

        assertEquals(1f to 0f, keyboardBarFadingEdgeStrengths(200, 200, 50f))
    }

    @Test
    fun sessionHeaderHidesDividerOnlyAboveRoundedTerminalContent() {
        // Portrait phone uses flush square panes and keeps the divider.
        assertTrue(sessionHeaderShowsBottomDivider(true, squarePaneCorners = true, false))
        // Landscape phone has rounded terminal corners and omits the full-width line.
        assertFalse(sessionHeaderShowsBottomDivider(true, squarePaneCorners = false, false))
        // Docked side rail is flush and keeps the divider.
        assertTrue(sessionHeaderShowsBottomDivider(true, squarePaneCorners = true, false))
        // Multi-pane tiles already sit inside rounded cards on the canvas.
        assertFalse(sessionHeaderShowsBottomDivider(true, squarePaneCorners = true, true))
        // Empty workspace preserves the divider below the session strip.
        assertTrue(sessionHeaderShowsBottomDivider(false, squarePaneCorners = false, false))
    }

    @Test
    fun workspaceBottomBarShapeMatchesTerminalCornerArc() {
        assertEquals(
            RoundedCornerShape(
                topStart = TerminalPaneCornerRadius,
                topEnd = TerminalPaneCornerRadius,
            ),
            workspaceBottomBarShape(roundedTop = true),
        )
        assertEquals(RectangleShape, workspaceBottomBarShape(roundedTop = false))
    }

    @Test
    fun workspaceBottomBarUsesRoundedGapOnlyWhenRounded() {
        assertEquals(WorkspaceBottomBarRoundedGap, workspaceBottomBarTopGap(roundedTop = true))
        assertEquals(0.dp, workspaceBottomBarTopGap(roundedTop = false))
    }
}
