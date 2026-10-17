package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.yaoyihan.nikonconnect.lut.LutGlView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class LutPreviewSurfaceTest {
    @Test
    fun originalAndFilterChangesKeepOneVisibleSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(192, 64, 32))
        }
        val identity = CubeLuts.parse("identity.cube", """
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent())
        val green = identity.copy(name = "green", values = FloatArray(24) { if (it % 3 == 1) 1f else 0f })
        val selected = mutableStateOf<CubeLut?>(null)
        val intensity = mutableFloatStateOf(1f)
        fun surfaces(): List<LutGlView> {
            fun collect(view: View): List<LutGlView> = when (view) {
                is LutGlView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                else -> emptyList()
            }
            var result = emptyList<LutGlView>()
            instrumentation.runOnMainSync { result = WindowInspector.getGlobalWindowViews().flatMap(::collect) }
            return result
        }
        fun awaitColor(expected: Int) {
            val deadline = System.nanoTime() + 5_000_000_000L
            var actual = 0
            do {
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull("Device screenshot unavailable", screenshot)
                actual = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                screenshot.recycle()
                if (listOf(16, 8, 0).all { shift ->
                        kotlin.math.abs(((actual shr shift) and 255) - ((expected shr shift) and 255)) <= 8
                    }) return
                Thread.sleep(50)
            } while (System.nanoTime() < deadline)
            assertEquals("Preview stayed blank or showed the wrong LUT", expected, actual)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PreviewDialog("regression.jpg", 0, null, original, false, {},
                        gpuSource = original, gpuLut = selected.value, gpuIntensity = intensity.floatValue)
                }
            }
            awaitColor(Color.rgb(192, 64, 32))
            val surface = surfaces().single()
            repeat(6) {
                for ((lut, color) in listOf(green to Color.GREEN, null to Color.rgb(192, 64, 32),
                        identity to Color.rgb(192, 64, 32))) {
                    instrumentation.runOnMainSync { selected.value = lut; intensity.floatValue = 1f }
                    awaitColor(color)
                    assertSame("Changing a LUT must not replace the Surface", surface, surfaces().single())
                }
                instrumentation.runOnMainSync { selected.value = green; intensity.floatValue = 0f }
                awaitColor(Color.rgb(192, 64, 32))
            }
        }
    }
}
