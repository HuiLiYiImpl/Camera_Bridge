package com.yaoyihan.nikonconnect

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ConnectedConsoleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun appearanceSurvivesOriginalRemovalAndReload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.cacheDir.resolve("console_test_${UUID.randomUUID()}").apply { mkdirs() }
        val preference = "console_test_${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preference, mode)
        }
        try {
            val store = ConnectedAppearanceStore(isolated)
            assertEquals(ConnectedAppearance(), store.load())
            val input = root.resolve("source.jpg")
            val image = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            input.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }; image.recycle()
            val key = store.importImage(Uri.fromFile(input))
            input.delete()
            val desired = ConnectedAppearance(image = key, text = "Ocean\nLight", zoom = 1.8f, panX = -.4f, panY = .2f, alignment = 2, brightness = .9f, veil = .3f)
            store.save(desired)
            val reloaded = ConnectedAppearanceStore(isolated)
            assertEquals(desired, reloaded.load())
            val decoded = reloaded.bitmap(key)!!
            assertEquals(1800, decoded.width)
            assertTrue(android.graphics.Color.red(decoded.getPixel(900, 600)) > 240)
            decoded.recycle()
            assertNull(reloaded.bitmap("../source.jpg"))
        } finally { root.deleteRecursively(); context.deleteSharedPreferences(preference) }
    }

    @Test fun consoleCustomTextCanCancelSaveAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("connected_appearance", Context.MODE_PRIVATE)
        val previous = prefs.getString("appearance", null)
        lateinit var vm: MainViewModel
        var initialized = false
        val originalSession = arrayOfNulls<CameraSession>(1)
        var background = ""
        try {
            prefs.edit().clear().commit()
            val source = context.cacheDir.resolve("console-ui-background.png")
            context.assets.open("ai_skills/photo-to-zine-postcard/assets/forest-homestead.png").use { input ->
                val original = android.graphics.BitmapFactory.decodeStream(input)!!
                val photo = Bitmap.createBitmap(original, 60, 76, 900, 670)
                source.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
                photo.recycle(); original.recycle()
            }
            background = runBlocking { ConnectedAppearanceStore(context).importImage(Uri.fromFile(source)) }
            source.delete()
            ConnectedAppearanceStore(context).save(ConnectedAppearance(image = background))
            compose.runOnUiThread {
                vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
                originalSession[0] = vm.session.value
                initialized = true
                vm.session.value = CameraSession("Nikon Z f", "USB", 0, ConnectionTransport.USB,
                    CameraDetails(model = "Nikon Z f", batteryPercent = 78, recentFocalLength = "85mm", recentAperture = "f/1.8", recentShutter = "1/30s", recentIso = 100))
            }
            compose.onNodeWithTag("connectedConsole").assertIsDisplayed()
            compose.onNodeWithText("进入相册").assertDoesNotExist()
            compose.onNodeWithText("CAMERA / CONNECT").assertDoesNotExist()
            compose.onNodeWithText("最近照片参数").assertIsDisplayed()
            compose.onNodeWithText("78%").assertIsDisplayed()
            compose.onNodeWithContentDescription("自定义外观").performClick()
            compose.onNodeWithText("展示文字（最多两排）").performTextInput("Cancel me")
            compose.onNodeWithContentDescription("取消外观编辑").performClick()
            assertEquals("", ConnectedAppearanceStore(context).load().text)
            compose.onNodeWithContentDescription("自定义外观").performClick()
            compose.onNodeWithText("展示文字（最多两排）").performTextInput("Ocean\nLight")
            compose.onNodeWithText("展示文字（最多两排）").performImeAction()
            compose.onNodeWithText("保存外观").performClick()
            assertEquals("Ocean\nLight", ConnectedAppearanceStore(context).load().text)
            compose.onNodeWithText("Ocean\nLight").assertIsDisplayed()
            val preview = compose.onNodeWithTag("connectedConsole").captureToImage().asAndroidBitmap()
            context.filesDir.resolve("connected-console-preview.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("自定义外观").performClick()
            compose.onNodeWithText("展示文字（最多两排）").assertTextContains("Ocean\nLight")
            compose.onNodeWithText("字形透图").assertDoesNotExist()
            compose.onNodeWithContentDescription("取消外观编辑").performClick()
        } finally {
            compose.runOnUiThread { if (initialized) vm.session.value = originalSession[0] }
            ConnectedAppearanceStore(context).discard(background)
            if (previous == null) prefs.edit().remove("appearance").commit() else prefs.edit().putString("appearance", previous).commit()
        }
    }
}
