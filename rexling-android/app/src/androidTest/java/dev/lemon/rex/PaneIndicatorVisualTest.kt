package dev.lemon.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaneIndicatorVisualTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun expandingTransferRendersInBothThemesAtAFixedSize() {
        var progress by mutableFloatStateOf(0f)
        var dark by mutableStateOf(false)
        ui.setContent {
            RexTheme(dark, ThemeCatalog.defaults[if (dark) 0 else 1]) {
                Surface {
                    Box(
                        Modifier.size(180.dp, 40.dp).testTag("Indicator preview"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PanePositionIndicator(0, 3, { progress })
                    }
                }
            }
        }
        val preview = ui.onNodeWithTag("Indicator preview")
        val before = preview.fetchSemanticsNode().boundsInRoot
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        for (step in 0..4) {
            ui.runOnIdle { progress = step / 4f }
            org.junit.Assert.assertEquals(before, preview.fetchSemanticsNode().boundsInRoot)
            File(cache, "pane-liquid-$step.png").outputStream().use {
                preview
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        ui.runOnIdle {
            dark = true
            progress = .5f
        }
        File(cache, "pane-liquid-dark.png").outputStream().use {
            preview.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
