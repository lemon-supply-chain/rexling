package dev.lemon.rex

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingTabBarEdgeTest {
    private val barSize = IntSize(100, 200)
    private val areaWidth = 400f
    private val areaHeight = 800f
    private val margin = 8f
    private val snapZone = 32f
    private val snapVelocity = 600f
    private val tuckVelocity = 900f

    @Test
    fun releasingInsideSnapZoneMagneticallySnapsToNearestEdge() {
        val leftSnap =
            resolveFloatingPark(
                releasedAt = Offset(margin + 28f, 300f),
                velocityPxPerSec = Offset.Zero,
                barSize = barSize,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                snapZonePx = snapZone,
                snapVelocityPxPerSec = snapVelocity,
                tuckVelocityPxPerSec = tuckVelocity,
                hideAtEdges = true,
                barOrientation = Orientation.Vertical,
            )
        assertEquals(0f, leftSnap.position.x, 0.001f)
        assertEquals(FloatingEdge.LEFT, leftSnap.snappedEdge)
        assertFalse(leftSnap.tuckImmediately)

        val rightFree = areaWidth - margin - barSize.width
        val rightSnap =
            resolveFloatingPark(
                releasedAt = Offset(rightFree - 25f, 300f),
                velocityPxPerSec = Offset.Zero,
                barSize = barSize,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                snapZonePx = snapZone,
                snapVelocityPxPerSec = snapVelocity,
                tuckVelocityPxPerSec = tuckVelocity,
                hideAtEdges = true,
                barOrientation = Orientation.Vertical,
            )
        assertEquals(1f, rightSnap.position.x, 0.001f)
        assertEquals(FloatingEdge.RIGHT, rightSnap.snappedEdge)
        assertFalse(rightSnap.tuckImmediately)
    }

    @Test
    fun releasingInCenterWithoutFlickStaysFreeFloating() {
        val center =
            resolveFloatingPark(
                releasedAt = Offset(150f, 300f),
                velocityPxPerSec = Offset(120f, 0f),
                barSize = barSize,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                snapZonePx = snapZone,
                snapVelocityPxPerSec = snapVelocity,
                tuckVelocityPxPerSec = tuckVelocity,
                hideAtEdges = true,
                barOrientation = Orientation.Vertical,
            )
        assertNull(center.snappedEdge)
        assertFalse(center.tuckImmediately)
        assertTrue(center.position.x > 0.1f && center.position.x < 0.9f)
    }

    @Test
    fun flickVelocitySnapsAndFastEdgeFlickTucksImmediately() {
        val mediumFlickLeft =
            resolveFloatingPark(
                releasedAt = Offset(80f, 300f),
                velocityPxPerSec = Offset(-650f, 50f),
                barSize = barSize,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                snapZonePx = snapZone,
                snapVelocityPxPerSec = snapVelocity,
                tuckVelocityPxPerSec = tuckVelocity,
                hideAtEdges = true,
                barOrientation = Orientation.Vertical,
            )
        assertEquals(0f, mediumFlickLeft.position.x, 0.001f)
        assertEquals(FloatingEdge.LEFT, mediumFlickLeft.snappedEdge)
        assertFalse(mediumFlickLeft.tuckImmediately)

        val fastFlickBottom =
            resolveFloatingPark(
                releasedAt = Offset(150f, areaHeight - margin - barSize.height - 20f),
                velocityPxPerSec = Offset(20f, 1100f),
                barSize = barSize,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                snapZonePx = snapZone,
                snapVelocityPxPerSec = snapVelocity,
                tuckVelocityPxPerSec = tuckVelocity,
                hideAtEdges = true,
                barOrientation = Orientation.Horizontal,
            )
        assertEquals(1f, fastFlickBottom.position.y, 0.001f)
        assertEquals(FloatingEdge.BOTTOM, fastFlickBottom.snappedEdge)
        assertTrue(fastFlickBottom.tuckImmediately)

        val crossAxisFlick =
            resolveFloatingPark(
                releasedAt = Offset(150f, 300f),
                velocityPxPerSec = Offset(-700f, 900f),
                barSize = barSize,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                snapZonePx = snapZone,
                snapVelocityPxPerSec = snapVelocity,
                tuckVelocityPxPerSec = tuckVelocity,
                hideAtEdges = true,
                barOrientation = Orientation.Vertical,
            )
        assertNull(crossAxisFlick.snappedEdge)
    }

    @Test
    fun snappedNormalizedPositionStaysPinnedAcrossWidthChanges() {
        val narrowEdge =
            resolveRestingEdge(
                position = Offset(1f, .5f),
                resting = Offset(300f, 300f),
                barSize = IntSize(48, 200),
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                edgeZonePx = 16f,
                hideAtEdges = true,
                barOrientation = Orientation.Vertical,
            )
        val expandedEdge =
            resolveRestingEdge(
                position = Offset(1f, .5f),
                resting = Offset(180f, 300f),
                barSize = IntSize(200, 200),
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                edgeZonePx = 16f,
                hideAtEdges = true,
                barOrientation = Orientation.Vertical,
            )
        assertEquals(FloatingEdge.RIGHT, narrowEdge)
        assertEquals(FloatingEdge.RIGHT, expandedEdge)
        assertNull(
            resolveRestingEdge(
                position = Offset(0f, .5f),
                resting = Offset(8f, 300f),
                barSize = IntSize(360, 52),
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                marginPx = margin,
                edgeZonePx = 16f,
                hideAtEdges = true,
                barOrientation = Orientation.Horizontal,
            ),
        )
    }

    @Test
    fun swipeTowardAndAwayFromEdgeFollowsDirectionalThresholds() {
        val threshold = 24f
        assertTrue(isSwipeTowardEdge(FloatingEdge.LEFT, Offset(-32f, 4f), threshold))
        assertFalse(isSwipeTowardEdge(FloatingEdge.LEFT, Offset(32f, 4f), threshold))
        assertTrue(isSwipeAwayFromEdge(FloatingEdge.LEFT, Offset(32f, 4f), threshold))

        assertTrue(isSwipeTowardEdge(FloatingEdge.RIGHT, Offset(32f, 4f), threshold))
        assertTrue(isSwipeAwayFromEdge(FloatingEdge.RIGHT, Offset(-32f, 4f), threshold))

        assertTrue(isSwipeTowardEdge(FloatingEdge.TOP, Offset(4f, -32f), threshold))
        assertTrue(isSwipeAwayFromEdge(FloatingEdge.TOP, Offset(4f, 32f), threshold))

        assertTrue(isSwipeTowardEdge(FloatingEdge.BOTTOM, Offset(4f, 32f), threshold))
        assertTrue(isSwipeAwayFromEdge(FloatingEdge.BOTTOM, Offset(4f, -32f), threshold))
        assertFalse(isSwipeTowardEdge(FloatingEdge.BOTTOM, Offset(40f, 32f), threshold))
    }
}
