package dev.lemon.rex

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class TabStackFanGeometryTest {
    private val workspace = Size(400f, 800f)
    private val card = Size(48f, 38f)

    private fun layout(
        itemCount: Int,
        windowStart: Int,
        anchor: Rect,
        direction: FanDirection,
    ): FanLayout =
        computeFanLayout(
            itemCount = itemCount,
            windowStart = windowStart,
            anchor = anchor,
            workspace = workspace,
            direction = direction,
            cardSize = card,
            marginPx = 8f,
            gapPx = 12f,
            spreadStepPx = 50f,
            archPx = 10f,
            stackStepPrimaryPx = 5f,
            stackStepSecondaryPx = 2f,
        )

    @Test
    fun horizontalBarPopsUpNearBottomAndDownNearTop() {
        val bottomAnchor = Rect(160f, 730f, 240f, 780f)
        val topAnchor = Rect(160f, 20f, 240f, 70f)

        assertEquals(
            FanDirection.UP,
            resolveFanDirection(bottomAnchor, workspace, Orientation.Horizontal),
        )
        assertEquals(
            FanDirection.DOWN,
            resolveFanDirection(topAnchor, workspace, Orientation.Horizontal),
        )
    }

    @Test
    fun verticalRailPopsRightOnLeftEdgeAndLeftOnRightEdge() {
        val leftAnchor = Rect(8f, 350f, 56f, 400f)
        val rightAnchor = Rect(344f, 350f, 392f, 400f)

        assertEquals(
            FanDirection.RIGHT,
            resolveFanDirection(leftAnchor, workspace, Orientation.Vertical),
        )
        assertEquals(
            FanDirection.LEFT,
            resolveFanDirection(rightAnchor, workspace, Orientation.Vertical),
        )
    }

    @Test
    fun horizontalFanShowsThreePrimaryCardsAndStacksExtrasTightlyAtRightMost() {
        val result = layout(5, windowStart = 0, Rect(160f, 730f, 240f, 780f), FanDirection.UP)

        val primary = result.cards.filter { it.role == FanCardRole.PRIMARY }
        val trailing = result.cards.filter { it.role == FanCardRole.TRAILING_STACK }
        assertEquals(3, primary.size)
        assertEquals(2, trailing.size)
        assertEquals(0, result.windowStart)
        assertEquals(3, result.visibleEnd)

        assertTrue(primary[0].centerX < primary[1].centerX)
        assertTrue(primary[1].centerX < primary[2].centerX)
        assertTrue(primary[1].centerY < primary[0].centerY)
        assertTrue(primary[1].centerY < primary[2].centerY)

        // Overflow cards stack tightly behind the right-most card (5px/layer, not a separate slot).
        val rightMost = primary.last()
        trailing.forEachIndexed { layer, back ->
            assertEquals(rightMost.centerX + (layer + 1) * 5f, back.centerX, 0.01f)
            assertTrue(back.zIndex < rightMost.zIndex)
        }
    }

    @Test
    fun advancingWindowAlwaysShowsThreeCardsAndStacksPreviousOnOppositeSide() {
        val result = layout(5, windowStart = 3, Rect(160f, 730f, 240f, 780f), FanDirection.UP)

        val leading = result.cards.filter { it.role == FanCardRole.LEADING_STACK }
        val primary = result.cards.filter { it.role == FanCardRole.PRIMARY }
        val trailing = result.cards.filter { it.role == FanCardRole.TRAILING_STACK }

        assertEquals(2, result.windowStart)
        assertEquals(listOf(0, 1), leading.map { it.itemIndex })
        assertEquals(listOf(2, 3, 4), primary.map { it.itemIndex })
        assertTrue(trailing.isEmpty())

        val leftMost = primary.first()
        // Originally top-most card (2) sits on top on the opposite side, with 1 above 0 behind it.
        assertTrue(primary.all { it == leftMost || leftMost.zIndex > it.zIndex })
        leading.forEach { back ->
            val distance = leftMost.centerX - back.centerX
            assertTrue(distance in 5f..15f)
            assertTrue(back.zIndex < leftMost.zIndex)
        }
        assertTrue(leading[1].zIndex > leading[0].zIndex)
    }

    @Test
    fun verticalFanShowsThreePrimaryCardsAndStacksOppositeSidesAtBottomAndTop() {
        val firstPage = layout(6, windowStart = 0, Rect(8f, 350f, 56f, 400f), FanDirection.RIGHT)
        val primary0 = firstPage.cards.filter { it.role == FanCardRole.PRIMARY }
        val trailing0 = firstPage.cards.filter { it.role == FanCardRole.TRAILING_STACK }
        assertEquals(3, primary0.size)
        assertEquals(3, trailing0.size)
        val topMost = primary0.last()
        trailing0.forEachIndexed { layer, back ->
            assertEquals(topMost.centerY - (layer + 1) * 5f, back.centerY, 0.01f)
            assertTrue(back.zIndex < topMost.zIndex)
        }

        val secondPage = layout(6, windowStart = 3, Rect(8f, 350f, 56f, 400f), FanDirection.RIGHT)
        val leading1 = secondPage.cards.filter { it.role == FanCardRole.LEADING_STACK }
        val primary1 = secondPage.cards.filter { it.role == FanCardRole.PRIMARY }
        assertEquals(listOf(0, 1, 2), leading1.map { it.itemIndex })
        assertEquals(listOf(3, 4, 5), primary1.map { it.itemIndex })
        val bottomMost = primary1.first()
        leading1.forEach { back ->
            val distance = back.centerY - bottomMost.centerY
            assertTrue(distance in 5f..15f)
            assertTrue(back.zIndex < bottomMost.zIndex)
        }
    }

    @Test
    fun twelveItemsKeepThreeVisibleAndCapBothSideStacksToThreeLayers() {
        val middle = layout(12, windowStart = 3, Rect(160f, 730f, 240f, 780f), FanDirection.UP)

        val leading = middle.cards.filter { it.role == FanCardRole.LEADING_STACK }
        val primary = middle.cards.filter { it.role == FanCardRole.PRIMARY }
        val trailing = middle.cards.filter { it.role == FanCardRole.TRAILING_STACK }

        assertEquals(listOf(0, 1, 2), leading.map { it.itemIndex })
        assertEquals(listOf(3, 4, 5), primary.map { it.itemIndex })
        assertEquals(listOf(6, 7, 8, 9, 10, 11), trailing.map { it.itemIndex })
        assertEquals(3, leading.count { it.visibleInStack })
        assertEquals(3, trailing.count { it.visibleInStack })
    }

    @Test
    fun safeZoneSpansFromOutermostFanPointPlusPaddingToTabBarOuterEdge() {
        val bar = Rect(20f, 520f, 380f, 572f)
        val anchor = Rect(160f, 524f, 240f, 568f)
        val up =
            computeFanLayout(
                itemCount = 5,
                windowStart = 0,
                anchor = anchor,
                workspace = workspace,
                direction = FanDirection.UP,
                cardSize = card,
                marginPx = 8f,
                gapPx = 12f,
                spreadStepPx = 50f,
                archPx = 10f,
                stackStepPrimaryPx = 5f,
                stackStepSecondaryPx = 2f,
                safePadPx = 28f,
                barBounds = bar,
            )
        val minCardTop =
            up.cards
                .filter { it.visibleInStack }
                .minOf { it.centerY - (card.height / 2f) * it.scale }
        assertTrue(up.safeZone.top < minCardTop)
        assertEquals(bar.bottom, up.safeZone.bottom, 0.01f)
        assertTrue(up.safeZone.bottom < workspace.height)

        val railBar = Rect(8f, 240f, 56f, 440f)
        val railAnchor = Rect(8f, 350f, 56f, 400f)
        val right =
            computeFanLayout(
                itemCount = 5,
                windowStart = 0,
                anchor = railAnchor,
                workspace = workspace,
                direction = FanDirection.RIGHT,
                cardSize = card,
                marginPx = 8f,
                gapPx = 12f,
                spreadStepPx = 50f,
                archPx = 10f,
                stackStepPrimaryPx = 5f,
                stackStepSecondaryPx = 2f,
                safePadPx = 28f,
                barBounds = railBar,
            )
        val maxCardRight =
            right.cards
                .filter { it.visibleInStack }
                .maxOf { it.centerX + (card.width / 2f) * it.scale }
        assertEquals(railBar.left, right.safeZone.left, 0.01f)
        assertTrue(right.safeZone.right > maxCardRight)
        assertEquals(railBar.top - 28f, right.safeZone.top, 0.01f)
    }

    @Test
    fun fanClampsInsideWorkspaceMarginsFromCornerAnchor() {
        val cornerAnchor = Rect(8f, 740f, 56f, 788f)
        val result = layout(5, windowStart = 0, cornerAnchor, FanDirection.UP)

        result.cards.forEach { pose ->
            val halfW = (card.width / 2f) * pose.scale
            val halfH = (card.height / 2f) * pose.scale
            assertTrue(pose.centerX - halfW >= 7.9f)
            assertTrue(pose.centerX + halfW <= workspace.width - 7.9f)
            assertTrue(pose.centerY - halfH >= 7.9f)
            assertTrue(pose.centerY + halfH <= workspace.height - 7.9f)
        }
    }

    @Test
    fun swipeResolutionMatchesSpreadAxisForAllDirections() {
        for (dir in listOf(FanDirection.UP, FanDirection.DOWN)) {
            assertEquals(
                FanTapAction.PAGE_FORWARD,
                resolveFanSwipeAction(dir, Offset(-40f, 0f), thresholdPx = 24f),
            )
            assertEquals(
                FanTapAction.PAGE_BACKWARD,
                resolveFanSwipeAction(dir, Offset(40f, 0f), thresholdPx = 24f),
            )
            assertEquals(
                FanTapAction.NONE,
                resolveFanSwipeAction(dir, Offset(-10f, 0f), thresholdPx = 24f),
            )
            assertEquals(
                FanTapAction.NONE,
                resolveFanSwipeAction(dir, Offset(-40f, 50f), thresholdPx = 24f),
            )
        }
        for (dir in listOf(FanDirection.LEFT, FanDirection.RIGHT)) {
            assertEquals(
                FanTapAction.PAGE_FORWARD,
                resolveFanSwipeAction(dir, Offset(0f, 40f), thresholdPx = 24f),
            )
            assertEquals(
                FanTapAction.PAGE_BACKWARD,
                resolveFanSwipeAction(dir, Offset(0f, -40f), thresholdPx = 24f),
            )
            assertEquals(
                FanTapAction.NONE,
                resolveFanSwipeAction(dir, Offset(0f, 10f), thresholdPx = 24f),
            )
            assertEquals(
                FanTapAction.NONE,
                resolveFanSwipeAction(dir, Offset(50f, 40f), thresholdPx = 24f),
            )
        }
    }
}
