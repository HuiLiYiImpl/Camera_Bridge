@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.yaoyihan.nikonconnect

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
internal fun AiPhotoStatus(controller: AiPhotoController, photoKey: String, open: () -> Unit) {
    val tasks by controller.tasks.collectAsState()
    val task = tasks.lastOrNull { it.photoKey == photoKey && it.active } ?: return
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = BridgeEmber, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(task.message, Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

@Composable
internal fun AiStyleDialog(
    controller: AiPhotoController, photoKey: String, name: String, source: Bitmap, rotation: Int,
    share: (DownloadRecord) -> Unit, settingsContent: @Composable (String?, () -> Unit) -> Unit, dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val config by controller.config.collectAsState()
    val busy by controller.configBusy.collectAsState()
    val tasks by controller.tasks.collectAsState()
    var style by remember(photoKey) { mutableStateOf(AiPhotoStyle.WATERCOLOR) }
    var title by remember(photoKey) { mutableStateOf("") }
    var postcardBack by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var skillInfo by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("link_ai_image_ui", android.content.Context.MODE_PRIVATE) }
    val active = tasks.count { it.active }
    val generateInteraction = remember { MutableInteractionSource() }
    fun submit() {
        submitting = true
        val failure = controller.generate(photoKey, name, source, rotation, style, title, postcardBack)
        submitting = false
        if (failure == null) dismiss() else error = failure
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (Build.VERSION.SDK_INT <= 28 && result[Manifest.permission.WRITE_EXTERNAL_STORAGE] != true) {
            error = "保存相册需要存储权限，尚未提交生成"
        } else submit()
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = BridgeSurface, contentColor = BridgeWhite) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("选择 AI 风格", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("后台处理中 $active/3", color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
            }
            IconButton({ settings = true }) { Icon(Icons.Default.Settings, "默认配置与供应商", tint = BridgeWhite) }
            IconButton(dismiss) { Icon(Icons.Default.Close, "关闭", tint = BridgeWhite) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(config.profileName, color = BridgeWhite.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium)
                Text(config.model.ifBlank { "未选择默认模型" }, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall)
            }
            items(AiPhotoStyle.entries, key = { it.assetName }) { choice ->
                val selected = choice == style
                Surface(Modifier.fillMaxWidth().clickable(enabled = !submitting) { style = choice; error = null },
                    shape = RoundedCornerShape(8.dp), color = if (selected) BridgeEmber.copy(alpha = .12f) else BridgeWhite.copy(alpha = .08f),
                    border = BorderStroke(1.dp, if (selected) BridgeEmber else BridgeWhite.copy(alpha = .1f))) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(24.dp), tint = if (selected) BridgeEmber else BridgeWhite.copy(alpha = .6f))
                        Spacer(Modifier.width(12.dp))
                        Text(choice.title, Modifier.weight(1f), color = BridgeWhite, fontWeight = FontWeight.SemiBold)
                        if (selected) Icon(Icons.Default.Check, "已选择", tint = BridgeEmber)
                    }
                }
            }
            if (style == AiPhotoStyle.ZINE_POSTCARD) item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!postcardBack, { postcardBack = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("正面") }
                    SegmentedButton(postcardBack, { postcardBack = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("背面") }
                }
            }
            item {
                OutlinedTextField(title, { if (it.length <= 80) title = it }, Modifier.fillMaxWidth(), label = { Text("图片标题（可选）") },
                    singleLine = true, colors = bridgeFieldColors())
                TextButton({ skillInfo = true }) { Icon(Icons.Default.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Skill 来源与许可证") }
                Text("生成会上传照片的压缩副本，费用由你的模型账号承担。完成后自动保存相册。",
                    color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
                aiSubmissionError(tasks, photoKey)?.let { Text(it, color = BridgeEmber, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Button({
            if (config.key.isBlank()) settings = true
            else {
                val needed = buildList {
                    if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notification_asked", false) &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        prefs.edit().putBoolean("notification_asked", true).apply()
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                if (needed.isEmpty()) submit() else permissions.launch(needed.toTypedArray())
            }
        }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).bridgePress(generateInteraction),
            interactionSource = generateInteraction,
            enabled = !busy && !submitting && aiSubmissionError(tasks, photoKey) == null,
            colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (config.key.isBlank()) "配置 API" else "生成并保存相册")
        }
        Spacer(Modifier.navigationBarsPadding().imePadding())
    }
    if (settings) settingsContent(config.profileId.takeIf { config.key.isBlank() }) { settings = false }
    if (skillInfo) AiSkillInfoDialog(style) { skillInfo = false }
}

@Composable
private fun AiSkillInfoDialog(style: AiPhotoStyle, dismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val license = remember(style) {
        runCatching { context.assets.open("ai_skills/${style.assetName}/LICENSE").bufferedReader().use { it.readText() } }
            .getOrDefault("上游仓库未提供 LICENSE。作者保留权利；分发或商业使用前请取得作者授权。")
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AiDialogWindow()
        Surface(Modifier.fillMaxSize(), color = BridgeNight) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Skill 来源", Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.titleLarge)
                    IconButton(dismiss) { Icon(Icons.Default.Close, "关闭来源说明", tint = BridgeWhite) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(style.title, color = BridgeWhite, style = MaterialTheme.typography.titleMedium)
                    Text(style.assetName, color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ uriHandler.openUri("https://github.com/${style.repository}") }) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text(style.repository)
                    }
                    if (style == AiPhotoStyle.GATHERED_ZINE) Text("Visual Skill by @Zeejay0\n仅限个人非商业用途；收费、广告支持或公司项目须先获得作者书面许可。", color = BridgeEmber)
                    Text(license, color = BridgeWhite, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AiImagePreview(bitmap: Bitmap, rotation: Int, modifier: Modifier) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val rotated = rotation % 180 != 0
        val width = if (rotated) bitmap.height else bitmap.width
        val height = if (rotated) bitmap.width else bitmap.height
        val fit = minOf(maxWidth.value / width, maxHeight.value / height)
        // Fit the rotated bounds before measuring the unrotated image layer.
        ZoomableImage(bitmap, rotation, Modifier.requiredSize((bitmap.width * fit).dp, (bitmap.height * fit).dp))
    }
}


@Composable
internal fun AiDialogWindow() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        window?.let {
            // Match the physical window so Compose's full-height content is not shifted below the status bar.
            it.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)
            WindowCompat.setDecorFitsSystemWindows(it, false)
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            it.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        onDispose { }
    }
}
