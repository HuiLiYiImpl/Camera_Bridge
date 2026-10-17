package com.yaoyihan.nikonconnect

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yaoyihan.nikonconnect.lut.LutBinaryCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ColorPresetScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun identity() = CubeLuts.parse("identity", "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1")

    @Test fun presetLayoutKeepsFilterThroughDismissRestoreAndPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var vm: MainViewModel
        var initialized = false
        var original = emptyList<LutEntry>()
        val entries = (1..7).map { LutEntry("ui_test_${UUID.randomUUID()}", "测试预设 $it", useCount = (8 - it).toLong()) }
        val directory = context.filesDir.resolve("luts").apply { mkdirs() }
        try {
            entries.forEach { entry -> directory.resolve("${entry.id}.nlut").outputStream().use { LutBinaryCodec.write(identity(), it) } }
            compose.runOnUiThread {
                vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
                original = vm.luts.value
                initialized = true
                vm.luts.value = entries
            }
            compose.onNodeWithContentDescription("预设").performClick()
            compose.onNodeWithText("全部预设").assertDoesNotExist()
            compose.onNodeWithContentDescription("收起全部预设").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("测试预设 1原图").fetchSemanticsNodes().isNotEmpty() }
            saveScreenshot(context, "preset-library.png")
            compose.onNodeWithContentDescription("导入与管理").performClick()
            compose.onNodeWithText("导入预设").assertIsDisplayed()
            compose.onNodeWithText("水印预设").assertIsDisplayed()
            saveScreenshot(context, "preset-glass-menu.png", "presetGlassMenu")
            androidx.test.espresso.Espresso.pressBackUnconditionally()
            compose.onNodeWithContentDescription("搜索预设").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("测试预设 2")
            compose.onNodeWithContentDescription("收起全部预设").performClick()
            compose.onNodeWithText("全部预设").assertIsDisplayed().performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("测试预设 2")
            compose.onNodeWithContentDescription("收起搜索").performClick()
            compose.onNodeWithContentDescription("预览当前预设").performClick()
            compose.onNodeWithText("原图", substring = false).assertIsDisplayed()
            compose.onAllNodesWithText("查看效果").onLast().performClick()
            compose.onNodeWithText("预设效果 · 50%").assertIsDisplayed()
            compose.onNodeWithText("查看原图").assertIsDisplayed().performClick()
            compose.onNodeWithText("原图", substring = false).assertIsDisplayed()
            compose.runOnIdle { assertTrue(vm.luts.value.all { it.useCount == entries.first { original -> original.id == it.id }.useCount }) }
            saveScreenshot(context, "preset-preview.png")
        } finally {
            compose.runOnUiThread { if (initialized) vm.luts.value = original }
            entries.forEach { directory.resolve("${it.id}.nlut").delete() }
        }
    }

    @Test fun persistedCoverAndOldMetadataRemainCompatible() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.cacheDir.resolve("preset_test_${UUID.randomUUID()}").apply { mkdirs() }
        val preferenceName = "preset_test_${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferenceName, mode)
        }
        val prefs = isolated.getSharedPreferences("ignored", Context.MODE_PRIVATE)
        val key = UUID.randomUUID().toString()
        try {
            prefs.edit().putString("luts", """[{"id":"legacy","name":"旧预设"}]""").commit()
            val store = AppStore(isolated)
            val legacy = store.luts().single()
            assertEquals(0L, legacy.useCount)
            assertEquals("", legacy.coverKey)
            val image = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val source = root.resolve("source.png")
            source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            LutPreviewImages.import(isolated, Uri.fromFile(source), identity(), key)
            val saved = legacy.copy(coverKey = key, useCount = 7, favorite = true)
            store.saveLuts(listOf(saved))
            assertEquals(saved, AppStore(isolated).luts().single())
            source.delete() // The preview must survive loss of the originally selected URI.
            val cover = LutPreviewImages.load(isolated, saved) { error("A persisted cover should not decode the LUT again") }
            assertNotNull(cover.original); assertNotNull(cover.graded)
            assertEquals(40, cover.original!!.width)
            assertTrue(Color.red(cover.graded!!.getPixel(10, 10)) > 240)
            assertTrue(Color.green(cover.graded.getPixel(10, 10)) < 10)
            val broken = root.resolve("broken.png").apply { writeText("not an image") }
            try {
                LutPreviewImages.import(isolated, Uri.fromFile(broken), identity(), UUID.randomUUID().toString())
                fail("Invalid image accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(saved, store.luts().single())
        } finally {
            LutPreviewImages.remove(isolated, key)
            root.deleteRecursively()
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun saveScreenshot(context: Context, name: String, tag: String? = null) {
        val node = if (tag != null) compose.onNodeWithTag(tag) else compose.onAllNodes(isRoot()).onLast()
        val image = node.captureToImage().asAndroidBitmap()
        context.getExternalFilesDir("ui-preview")?.resolve(name)?.outputStream()?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
