package com.yaoyihan.nikonconnect

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BridgeDockInputTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun plainDockRespondsToPhysicalTouch() = checkDock(capture = false)
    @Test fun capturedDockRespondsToPhysicalTouch() = checkDock(capture = true)

    @Test fun dockScrollsIndependentlyAndReversesWithoutJumping() {
        lateinit var motionScope: CoroutineScope
        lateinit var motion: PhotoDetailMotion
        var dismissed = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val scope = rememberCoroutineScope()
                SideEffect { motionScope = scope }
            } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                motion = PhotoDetailMotion(motionScope, null, { null }, { dismissed = true })
                motion.open()
            }
            compose.mainClock.advanceTimeBy(160)
            compose.runOnIdle { assertTrue(motion.dockSwapProgress in .001f.. .999f); assertFalse(motion.entered) }
            compose.mainClock.advanceTimeBy(192)
            compose.runOnIdle {
                assertEquals(1f, motion.dockSwapProgress, 0f)
                assertFalse(motion.entered)
                motion.close()
            }
            compose.mainClock.advanceTimeBy(160)
            compose.runOnIdle { assertTrue(motion.dockSwapProgress in .001f.. .999f); assertFalse(dismissed) }
            compose.mainClock.advanceTimeBy(224)
            compose.runOnIdle {
                assertEquals(0f, motion.dockSwapProgress, 0f)
                assertTrue(dismissed)
                dismissed = false
                motion = PhotoDetailMotion(motionScope, null, { null }, { dismissed = true })
                motion.open()
            }
            compose.mainClock.advanceTimeBy(160)
            compose.runOnIdle {
                val position = motion.dockSwapProgress
                motion.close()
                assertEquals(position, motion.dockSwapProgress, 0f)
            }
            compose.mainClock.advanceTimeBy(384)
            compose.runOnIdle { assertEquals(0f, motion.dockSwapProgress, 0f); assertTrue(dismissed) }
        }
    }

    @Test fun closingRevealsTileAndHandsOffBeforeRemovingDialog() {
        lateinit var motionScope: CoroutineScope
        lateinit var motion: PhotoDetailMotion
        var revealed = false
        var dismissed = false
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val source = PhotoPreviewSource(androidx.compose.ui.geometry.Rect(0f, 0f, 40f, 50f), bitmap, true) { revealed = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val scope = rememberCoroutineScope()
                SideEffect { motionScope = scope }
            } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                motion = PhotoDetailMotion(motionScope, source, { source }, { dismissed = true })
                motion.open()
            }
            compose.mainClock.advanceTimeBy(416)
            compose.runOnIdle {
                motion.close()
                assertTrue("The actual thumbnail must be visible during the return", revealed)
                assertFalse(dismissed)
            }
            compose.mainClock.advanceTimeUntil(timeoutMillis = 1000) { motion.handedOff }
            compose.runOnIdle {
                assertEquals(0f, motion.progress.value, 0f)
                assertEquals(0f, motion.dockSwapProgress, 0f)
                assertFalse("Keep the dialog until the underlying window has drawn", dismissed)
            }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { assertTrue(dismissed) }
        }
        bitmap.recycle()
    }

    @Test fun panelHidesDockFirstAndRestoresItAfterClosing() {
        lateinit var panelScope: CoroutineScope
        lateinit var panel: PhotoDetailPanelState
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val scope = rememberCoroutineScope()
                SideEffect { panelScope = scope }
            } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { panel = PhotoDetailPanelState(panelScope); panel.show() }
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle {
                assertTrue(panel.dockHiddenFraction in .001f.. .999f)
                assertEquals("The sheet waits for the dock to leave", 0f, panel.fraction, 0f)
            }
            compose.mainClock.advanceTimeBy(192)
            compose.runOnIdle { assertEquals(1f, panel.dockHiddenFraction, 0f); assertTrue(panel.fraction > 0f) }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { panel.close() }
            compose.mainClock.advanceTimeUntil(timeoutMillis = 1500) { panel.fraction == 0f }
            compose.runOnIdle { assertEquals("The dock waits for the sheet to leave", 1f, panel.dockHiddenFraction, 0f) }
            compose.mainClock.advanceTimeBy(288)
            compose.runOnIdle { assertFalse(panel.open); assertEquals(0f, panel.dockHiddenFraction, 0f); panel.show() }
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle {
                val before = panel.dockHiddenFraction
                panel.close()
                assertEquals(before, panel.dockHiddenFraction, 0f)
            }
            compose.mainClock.advanceTimeBy(384)
            compose.runOnIdle { assertFalse(panel.open); assertEquals(0f, panel.dockHiddenFraction, 0f) }
        }
    }

    @Test fun detailToolsReplaceDockAndExportStaysDirect() {
        val bitmap = android.graphics.Bitmap.createBitmap(24, 30, android.graphics.Bitmap.Config.ARGB_8888)
        var exports = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                BridgeTheme(AppColorTheme.DEEP_BLUE) {
                    PreviewDialog("test.jpg", 0L, bitmap, bitmap, false, {}, luts = listOf(LutEntry("regression", "回归预设")), selectLut = {}, selectWatermark = {}, loadPresetCover = { bitmap },
                        exportEdited = { exports++ }, localRecord = DownloadRecord("test.jpg", 0L, "content://test/image", 0L))
                }
            } }
            compose.onNodeWithContentDescription("导出").performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(1, exports) }
            listOf("色彩", "水印", "查看拍摄参数").forEach { tool ->
                compose.onNodeWithContentDescription(tool).performTouchInput { click(center) }
                compose.onNodeWithContentDescription("收起面板").assertIsDisplayed()
                if (tool == "色彩") {
                    compose.onNodeWithText("强度 50%").assertIsDisplayed()
                    compose.onAllNodesWithText("原始色彩").assertCountEquals(1)
                    compose.onNodeWithText("回归预设").assertDoesNotExist()
                    compose.onNodeWithContentDescription("回归预设").assertIsDisplayed()
                    val pixels = compose.onNodeWithContentDescription("原始色彩").captureToImage().toPixelMap()
                    assertEquals("Keep the whole 4:5 card inside the row", .8f, pixels.width.toFloat() / pixels.height, .02f)
                    val top = (0 until (pixels.height * .04f).toInt()).sumOf { y ->
                        ((pixels.width * .3f).toInt() until (pixels.width * .7f).toInt()).count { x -> pixels[x, y].red > .85f }
                    }
                    val bottom = ((pixels.height * .96f).toInt() until pixels.height).sumOf { y ->
                        ((pixels.width * .3f).toInt() until (pixels.width * .7f).toInt()).count { x -> pixels[x, y].red > .85f }
                    }
                    assertTrue("The upper white border must not be cropped", top > pixels.width / 5)
                    assertTrue("The lower white border must remain visible", bottom > pixels.width / 5)
                }
                compose.onNodeWithContentDescription("收起面板").performTouchInput { click(center) }
                compose.onNodeWithContentDescription("导出").assertIsDisplayed()
            }
            compose.onNodeWithContentDescription("导出").performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(2, exports) }
        }
        bitmap.recycle()
    }

    @Test fun detailButtonsAppearWhileOriginalIsStillLoading() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                BridgeTheme(AppColorTheme.PROFESSIONAL_GRAY) {
                    PreviewDialog("loading.jpg", 0L, null, null, true, {}, originalLoaded = false,
                        localRecord = DownloadRecord("loading.jpg", 0L, "content://test/image", 0L))
                }
            } }
            compose.onNodeWithContentDescription("色彩").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithContentDescription("水印").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithContentDescription("导出").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithContentDescription("查看原图").assertDoesNotExist()
        }
    }

    @Test fun dockHasVisibleIconsAtEveryScrollStage() {
        val progress = mutableFloatStateOf(0f)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                BridgeTheme(AppColorTheme.PROFESSIONAL_GRAY) {
                    val previous = remember { BridgeDockTransition().apply {
                        items = listOf(BridgeDockItem("old", "", Icons.Default.CameraAlt) {})
                        selected = "old"
                    } }
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        BridgeDockBar(listOf(BridgeDockItem("new", "", Icons.Default.ColorLens) {}), "new",
                            Modifier.fillMaxWidth().testTag("swappingDock"), previous = previous,
                            contentOffset = { progress.floatValue - 1f })
                    }
                }
            } }
            listOf(0f, .25f, .5f, .75f, 1f).forEach { stage ->
                compose.runOnIdle { progress.floatValue = stage }
                val pixels = compose.onNodeWithTag("swappingDock").captureToImage().toPixelMap()
                var brightPixels = 0
                // Exclude the shell and highlight borders; count the actual icon strokes.
                for (y in (pixels.height * .25f).toInt() until (pixels.height * .75f).toInt()) {
                    for (x in (pixels.width * .4f).toInt() until (pixels.width * .6f).toInt()) {
                        val color = pixels[x, y]
                        if (color.red > .55f && color.green > .55f && color.blue > .55f) brightPixels++
                    }
                }
                assertTrue("No visible icon at scroll progress $stage", brightPixels > 10)
            }
        }
    }

    @Test fun appNavigationChangesPagesByTouch() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("下载").performTouchInput { down(center); advanceEventTime(120); up() }
            compose.onAllNodesWithText("下载").assertCountEquals(2)
            compose.onNodeWithContentDescription("预设").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("收起全部预设").assertIsDisplayed()
            compose.onNodeWithContentDescription("相机").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("设置").assertIsDisplayed()
        }
    }

    private fun checkDock(capture: Boolean) {
        val selection = mutableStateOf("camera")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                BridgeTheme(AppColorTheme.PROFESSIONAL_GRAY) {
                    var selected by selection
                    val snapshot = remember { BridgeDockTransition() }
                    Box(Modifier.fillMaxSize()) {
                        Text("selected:$selected", Modifier.align(Alignment.TopCenter))
                        BridgeDockBar(listOf(
                            BridgeDockItem("camera", "相机", Icons.Default.CameraAlt) { selected = "camera" },
                            BridgeDockItem("downloads", "下载", Icons.Default.Download) { selected = "downloads" },
                            BridgeDockItem("presets", "预设", Icons.Default.ColorLens) { selected = "presets" }),
                            selected, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                                .padding(horizontal = BridgeDockSideGap, vertical = BridgeDockBottomGap)
                                .then(if (capture) Modifier.captureBridgeDock(snapshot) else Modifier),
                            snapshot = if (capture) snapshot else null)
                    }
                }
            } }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("下载").performTouchInput { down(center); advanceEventTime(120); up() }
            compose.runOnIdle { org.junit.Assert.assertEquals("downloads", selection.value) }
            compose.onNodeWithContentDescription("预设").performTouchInput { click(center) }
            compose.runOnIdle { org.junit.Assert.assertEquals("presets", selection.value) }
            repeat(3) {
                compose.onNodeWithContentDescription("下载").performTouchInput { click(center) }
                compose.runOnIdle { org.junit.Assert.assertEquals("downloads", selection.value) }
                compose.onNodeWithContentDescription("相机").performTouchInput { click(center) }
                compose.runOnIdle { org.junit.Assert.assertEquals("camera", selection.value) }
            }
        }
    }
}
