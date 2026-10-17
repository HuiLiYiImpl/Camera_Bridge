package com.yaoyihan.nikonconnect

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConnectionLaunchTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun introGathersThenRevealsAndCanBeSkipped() {
        lateinit var scope: CoroutineScope
        lateinit var intro: ConnectionIntroState
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val remembered = rememberCoroutineScope()
                SideEffect { scope = remembered }
            } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { intro = ConnectionIntroState(scope, true); intro.start() }
            compose.mainClock.advanceTimeBy(752)
            compose.runOnIdle {
                assertTrue(intro.fallTime > 0f)
                assertEquals(0f, intro.gatherProgress, 0f)
                assertEquals(0f, intro.revealProgress, 0f)
            }
            compose.mainClock.advanceTimeBy(336)
            compose.runOnIdle { assertTrue(intro.gatherProgress > 0f); assertEquals(0f, intro.revealProgress, 0f) }
            compose.mainClock.advanceTimeBy(384)
            compose.runOnIdle { assertTrue(intro.revealProgress > 0f) }
            compose.mainClock.advanceTimeBy(1100)
            compose.runOnIdle {
                assertTrue(intro.complete)
                intro.start()
                assertEquals(1f, intro.gatherProgress, 0f)
                intro = ConnectionIntroState(scope, true)
                intro.start()
            }
            compose.mainClock.advanceTimeBy(224)
            compose.runOnIdle { intro.skip() }
            compose.mainClock.advanceTimeBy(192)
            compose.runOnIdle {
                assertTrue(intro.complete)
                assertEquals(1f, intro.gatherProgress, 0f)
                assertEquals(1f, intro.revealProgress, 0f)
                assertFalse("An activity recreated in the same process must not replay the opening", ConnectionLaunchSession.claim())
            }
        }
    }

    @Test fun photoIsVisibleOnlyInsideBrandLetters() {
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    BrandPhotoWordmark(bitmap, "NIKON", Modifier.size(320.dp, 200.dp).testTag("wordMask"))
                }
            } }
            val pixels = compose.onNodeWithTag("wordMask").captureToImage().toPixelMap()
            assertTrue("The rectangle around the letters must be transparent", pixels[0, 0].red < .1f)
            for (fraction in listOf(.28f, .72f)) {
                var runs = 0
                var wasRed = false
                for (x in 0 until pixels.width) {
                    val red = pixels[x, (pixels.height * fraction).toInt()].red > .8f
                    if (red && !wasRed) runs++
                    wasRed = red
                }
                assertTrue("Both title rows must expose separate photo strokes", runs >= 2)
            }
            assertTrue("The gap between title rows stays transparent", (0 until pixels.width).all { pixels[it, pixels.height / 2].red < .1f })
        }
        bitmap.recycle()
    }

    @Test fun connectionFlowsAndReturnToHomeStayUsable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(timeoutMillis = 5000) { compose.onAllNodesWithText("Wi-Fi").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("NIKON").performTouchInput { click(center) }
            compose.waitUntil(timeoutMillis = 5000) { compose.onAllNodesWithContentDescription("NIKON 品牌头图").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("SONY").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("SONY 品牌头图").assertIsDisplayed()
            compose.onNodeWithText("NIKON").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("NIKON 品牌头图").assertIsDisplayed()
            compose.onNodeWithText("Wi-Fi").performTouchInput { click(center) }
            compose.onNodeWithText("Wi‑Fi 连接").assertIsDisplayed()
            compose.onNodeWithText("返回").performTouchInput { click(center) }
            compose.onNodeWithText("USB").performTouchInput { click(center) }
            compose.onNodeWithText("USB 连接").assertIsDisplayed()
            compose.onNodeWithText("返回").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("下载").performTouchInput { click(center) }
            compose.onAllNodesWithText("下载").assertCountEquals(2)
            compose.onNodeWithContentDescription("相机").performTouchInput { click(center) }
            compose.onNodeWithText("跳过").assertDoesNotExist()
            compose.onNodeWithText("连接相机").assertIsDisplayed()
            compose.onNodeWithContentDescription("设置").assertIsDisplayed()
            val image = compose.onNodeWithTag("connectionLanding").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "connection-home-preview.png")
                .outputStream().use { stream -> image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
        }
    }
    @Test fun connectionSheetsOpenAndDismissWithGlassMaterial() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun snapshot(name: String) {
            val image = compose.onNodeWithTag("bridgeGlassSheet").captureToImage().asAndroidBitmap()
            File(context.filesDir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("工具").performClick()
            compose.onNodeWithText("GPS 同步").assertIsDisplayed()
            compose.onNodeWithText("屏幕补光").assertIsDisplayed()
            snapshot("connection-tools-glass.png")
            androidx.test.espresso.Espresso.pressBackUnconditionally()
            compose.onNodeWithContentDescription("管理已保存设备").performClick()
            compose.onAllNodesWithText("已保存设备").onLast().assertIsDisplayed()
            snapshot("connection-devices-glass.png")
            compose.onNodeWithText("新增快捷连接").performClick()
            compose.onNodeWithText("相机 Wi‑Fi 名称（SSID）").assertIsDisplayed()
            compose.onNodeWithText("保存快捷连接").assertIsDisplayed()
            compose.onNodeWithText("使用系统 Wi‑Fi 设置").assertIsDisplayed()
            snapshot("connection-shortcut-glass.png")
            androidx.test.espresso.Espresso.pressBackUnconditionally()
            compose.onNodeWithText("添加连接").assertIsDisplayed()
        }
    }

}
