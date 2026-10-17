package com.yaoyihan.nikonconnect

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BridgeSliderTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun themeSliderKeepsTouchMappingCommitAndDisabledState() {
        val value = mutableFloatStateOf(0f)
        val enabled = mutableStateOf(true)
        val rtl = mutableStateOf(false)
        val theme = mutableStateOf(AppColorTheme.DARKROOM_ORANGE)
        var accent = Color.Unspecified
        var commits = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                BridgeTheme(theme.value) {
                    accent = BridgeEmber
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Box(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp, vertical = 64.dp)) {
                            BridgeSlider(value.floatValue, { value.floatValue = it }, Modifier.fillMaxWidth().testTag("slider"),
                                enabled = enabled.value, valueRange = -1f..1f, onValueChangeFinished = { commits++ })
                        }
                    }
                }
            } }
            val slider = compose.onNodeWithTag("slider")
            for (colorTheme in AppColorTheme.entries) {
                compose.runOnIdle { theme.value = colorTheme; value.floatValue = 0f }
                val pixels = slider.captureToImage().toPixelMap()
                val actual = pixels[pixels.width / 4, pixels.height / 2]
                assertEquals(accent.red, actual.red, .025f)
                assertEquals(accent.green, actual.green, .025f)
                assertEquals(accent.blue, actual.blue, .025f)
            }
            slider.performTouchInput { swipe(center, Offset(width * .8f, center.y), 220) }
            compose.runOnIdle { assertTrue(value.floatValue > .5f); assertEquals(1, commits) }
            slider.performTouchInput { down(Offset(width * .25f, center.y)); advanceEventTime(100); up() }
            compose.runOnIdle { assertTrue(value.floatValue < -.4f); assertEquals(2, commits) }
            compose.runOnIdle { enabled.value = false }
            slider.performTouchInput { down(Offset(width * .75f, center.y)); advanceEventTime(100); up() }
            compose.runOnIdle { assertTrue(value.floatValue < -.4f); assertEquals(2, commits) }
            compose.runOnIdle { enabled.value = true; rtl.value = true }
            slider.performTouchInput { down(Offset(width * .25f, center.y)); advanceEventTime(100); up() }
            compose.runOnIdle { assertTrue(value.floatValue > .4f); assertEquals(3, commits) }
        }
    }
}
