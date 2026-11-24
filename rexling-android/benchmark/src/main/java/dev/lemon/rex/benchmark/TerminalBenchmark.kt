package dev.lemon.rex.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val APP = "dev.lemon.rex"

private fun MacrobenchmarkScope.preview() {
    startActivityAndWait()
    check(device.currentPackageName == APP)
    check(device.wait(Until.hasObject(By.text("Try preview")), 5000)) {
        "Use clean emulator app preferences for the offline benchmark"
    }
    device.findObject(By.text("Try preview")).click()
    check(
        device.wait(
            Until.hasObject(By.desc("Terminal output. Tap to take control and type.")),
            5000,
        ),
    )
    device.waitForIdle()
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun offlineTerminalJourney() =
        rule.collect(APP, includeInStartupProfile = true, maxIterations = 5, stableIterations = 2) {
            preview()
            check(device.currentPackageName == APP)
            device.findObject(By.desc("Terminal menu")).click()
            check(device.wait(Until.hasObject(By.desc("Preferences")), 3000))
            device.findObject(By.desc("Preferences")).click()
            device.wait(Until.hasObject(By.text("Appearance")), 3000)
            device.findObject(By.text("Appearance")).click()
            device.wait(Until.hasObject(By.text("Light")), 3000)
            device.findObject(By.text("Light")).click()
            device.waitForIdle()
        }
}

@RunWith(AndroidJUnit4::class)
class TerminalBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithProfile() =
        rule.measureRepeated(
            APP,
            listOf(StartupTimingMetric()),
            compilationMode =
                CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
            startupMode = StartupMode.COLD,
            iterations = 5,
        ) {
            startActivityAndWait()
        }

    @Test
    fun coldStartupWithoutProfile() =
        rule.measureRepeated(
            APP,
            listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = 5,
        ) {
            startActivityAndWait()
        }

    @Test
    fun terminalGestures() =
        rule.measureRepeated(
            APP,
            listOf(FrameTimingMetric()),
            compilationMode =
                CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
            iterations = 5,
            setupBlock = {
                killProcess()
                pressHome()
                preview()
            },
        ) {
            check(device.currentPackageName == APP)
            val w = device.displayWidth
            val h = device.displayHeight
            repeat(4) {
                device.swipe(w * 3 / 4, h / 2, w / 4, h / 2, 20)
                device.swipe(w / 4, h / 2, w * 3 / 4, h / 2, 20)
            }
        }
}
