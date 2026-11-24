package dev.lemon.rex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutSheetTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun displayedBuildMatchesTheInstalledApkAndCanBeCopied() {
        val info = AppBuildInfo.current
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(installed.versionName, info.version)
        assertEquals(installed.longVersionCode, info.number.toLong())
        assertTrue(info.version.matches(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")))
        assertTrue(info.revision == "unknown" || info.revision.matches(Regex("[0-9a-f]{7,40}")))
        var copied = ""
        ui.setContent { MaterialTheme { AboutContent(info, {}, { copied = it }, {}) } }
        ui.onNodeWithText("Rexling").assertIsDisplayed()
        ui.onNodeWithText(info.version).assertIsDisplayed()
        ui.onNodeWithText("${info.number} · ${info.type}").assertIsDisplayed()
        ui.onNodeWithText(info.commit).assertIsDisplayed()
        ui.onNodeWithText(appString(R.string.copy_build_info)).performClick()
        ui.runOnIdle {
            assertTrue(copied.startsWith("Rexling "))
            assertTrue(copied.contains(info.version))
            assertTrue(copied.contains("build ${installed.longVersionCode}"))
            assertTrue(copied.contains(info.type))
            assertTrue(copied.contains(info.commit))
        }
    }

    @Test
    fun aboutProvidesProjectAndLicenseLinksAndCloses() {
        val links = mutableListOf<String>()
        var closed = false
        ui.setContent { MaterialTheme { AboutSheetForTest({ closed = true }, { links.add(it) }) } }
        for (label in
            listOf(
                appString(R.string.source_code_github),
                appString(R.string.apache_license),
                appString(R.string.opensource_notices),
            )) ui.onNodeWithText(label).performScrollTo().performClick()
        ui.runOnIdle {
            assertEquals(
                listOf(
                    "https://github.com/shulhi/rexling",
                    "https://github.com/shulhi/rexling/blob/main/LICENSE",
                    "https://github.com/shulhi/rexling/blob/main/THIRD_PARTY_NOTICES.md",
                ),
                links,
            )
        }
        ui.onNodeWithContentDescription(appString(R.string.close_about))
            .performScrollTo()
            .performClick()
        ui.runOnIdle { assertTrue(closed) }
    }
}

@androidx.compose.runtime.Composable
private fun AboutSheetForTest(onClose: () -> Unit, onOpen: (String) -> Unit) {
    AboutContent(AppBuildInfo("2026.11.03", 2, "release", "1234567890", false), onClose, {}, onOpen)
}
