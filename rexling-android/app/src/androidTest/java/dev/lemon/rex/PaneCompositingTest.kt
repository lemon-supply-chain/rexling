package dev.lemon.rex

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaneCompositingTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun scrimMatchesGroupFadeForOverlappingTextAndBackgroundsInBothThemes() {
        val dim = mutableFloatStateOf(0f)
        val light = mutableStateOf(false)
        ui.setContent {
            val backdrop = if (light.value) Color(0xfff3f4f8) else Color(0xff080b11)
            Row(Modifier.background(backdrop).testTag("scene")) {
                for (reference in listOf(true, false)) {
                    val effect =
                        if (reference) Modifier.graphicsLayer { alpha = 1f - dim.floatValue }
                        else Modifier.paneScrim(backdrop) { dim.floatValue }
                    Canvas(
                        Modifier.size(140.dp, 160.dp)
                            .then(if (reference) effect else Modifier.graphicsLayer {})
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xff274267))
                            .then(if (reference) Modifier else effect)
                            .testTag(if (reference) "reference" else "scrim"),
                    ) {
                        drawRect(
                            Color(0xffba6450),
                            size =
                                androidx.compose.ui.geometry.Size(
                                    size.width * .7f,
                                    size.height * .75f,
                                ),
                        )
                        drawRect(
                            Color(0xff50b477),
                            topLeft =
                                androidx.compose.ui.geometry.Offset(
                                    size.width * .25f,
                                    size.height * .25f,
                                ),
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            "overlap",
                            8f,
                            size.height * .5f,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.WHITE
                                textSize = 24f
                            },
                        )
                    }
                }
            }
        }
        for (theme in listOf(false, true)) for (amount in listOf(0f, .12f, .24f, .48f)) {
            ui.runOnIdle {
                light.value = theme
                dim.floatValue = amount
            }
            val scene = ui.onNodeWithTag("scene")
            val image = scene.captureToImage().asAndroidBitmap()
            val origin = scene.fetchSemanticsNode().boundsInRoot
            val a = ui.onNodeWithTag("reference").fetchSemanticsNode().boundsInRoot
            val b = ui.onNodeWithTag("scrim").fetchSemanticsNode().boundsInRoot
            val ax = (a.left - origin.left).roundToInt()
            val bx = (b.left - origin.left).roundToInt()
            val top = (a.top - origin.top).roundToInt()
            assertEquals(a.width, b.width, .1f)
            assertEquals(a.height, b.height, .1f)
            // Ignore rounded antialias edges; every interior pixel includes overlap.
            for (y in 24 until a.height.roundToInt() - 24) for (x in
                24 until a.width.roundToInt() - 24) {
                val old = image.getPixel(ax + x, top + y)
                val actual = image.getPixel(bx + x, top + y)
                for (shift in listOf(0, 8, 16, 24)) assertTrue(
                    "theme=$theme fade=$amount x=$x y=$y",
                    abs((old ushr shift and 255) - (actual ushr shift and 255)) <= 2,
                )
            }
        }
    }
}
