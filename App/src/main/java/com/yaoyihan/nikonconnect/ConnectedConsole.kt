@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yaoyihan.nikonconnect

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

internal data class ConnectedAppearance(
    val image: String = "", val text: String = "",
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f,
    val fontSize: Float = 52f, val alignment: Int = 0, val textColor: Int = -1,
    val brightness: Float = 1f, val veil: Float = .18f,
)

internal class ConnectedAppearanceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("connected_appearance", Context.MODE_PRIVATE)
    private val directory get() = context.filesDir.resolve("connected_backgrounds").apply { mkdirs() }
    private fun imageFile(key: String) = key.takeIf { it.matches(Regex("[a-f0-9-]+\\.jpg")) }?.let { directory.resolve(it) }
    fun load(): ConnectedAppearance = runCatching {
        val j = JSONObject(prefs.getString("appearance", "{}") ?: "{}")
        ConnectedAppearance(j.optString("image"), j.optString("text").lines().take(2).joinToString("\n").take(80),
            j.optDouble("zoom", 1.0).toFloat().coerceIn(1f, 3f), j.optDouble("panX", 0.0).toFloat().coerceIn(-1f, 1f), j.optDouble("panY", 0.0).toFloat().coerceIn(-1f, 1f),
            j.optDouble("fontSize", 52.0).toFloat().coerceIn(28f, 80f), j.optInt("alignment").coerceIn(0, 2), j.optInt("textColor", -1),
            j.optDouble("brightness", 1.0).toFloat().coerceIn(.5f, 1.3f), j.optDouble("veil", .18).toFloat().coerceIn(0f, .75f))
    }.getOrDefault(ConnectedAppearance())
    fun save(value: ConnectedAppearance) {
        val j = JSONObject().put("image", value.image).put("text", value.text)
            .put("zoom", value.zoom).put("panX", value.panX).put("panY", value.panY).put("fontSize", value.fontSize)
            .put("alignment", value.alignment).put("textColor", value.textColor).put("brightness", value.brightness).put("veil", value.veil)
        prefs.edit().putString("appearance", j.toString()).apply()
    }
    suspend fun importImage(uri: Uri): String {
        var target: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val source = loadDownloadThumbnail(context, uri.toString(), MediaKind.IMAGE, maxDimension = 2560)
                    ?: error("无法读取这张照片")
                val key = "${UUID.randomUUID()}.jpg"
                val file = imageFile(key)!!
                target = file
                val encodable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
                try {
                    file.outputStream().use { check(encodable.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                    key
                } finally { if (encodable !== source) encodable.recycle(); source.recycle() }
            }
        } catch (error: Exception) { target?.delete(); throw error }
    }
    suspend fun bitmap(key: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { imageFile(key)?.takeIf { it.isFile }?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }.getOrNull()
    }
    fun discard(key: String) { imageFile(key)?.delete() }
}

private fun DrawScope.posterImage(image: ImageBitmap, appearance: ConnectedAppearance) {
    val scale = maxOf(size.width / image.width, size.height / image.height) * appearance.zoom
    val width = image.width * scale
    val height = image.height * scale
    val left = (size.width - width) * .5f + appearance.panX * (width - size.width) * .5f
    val top = (size.height - height) * .5f + appearance.panY * (height - size.height) * .5f
    val matrix = ColorMatrix().apply { setToScale(appearance.brightness, appearance.brightness, appearance.brightness, 1f) }
    drawImage(image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
        colorFilter = ColorFilter.colorMatrix(matrix), filterQuality = FilterQuality.High)
}

@Composable
private fun PosterBackground(bitmap: Bitmap?, appearance: ConnectedAppearance, modifier: Modifier = Modifier) {
    Box(modifier.clipToBounds().bridgeBackground().drawWithContent {
        drawContent()
        if (bitmap != null) posterImage(bitmap.asImageBitmap(), appearance)
        drawRect(Color.Black.copy(alpha = appearance.veil))
    })
}

@Composable
private fun PosterTitle(appearance: ConnectedAppearance, fallback: String, modifier: Modifier = Modifier) {
    Text(appearance.text.ifBlank { fallback }, modifier.fillMaxWidth(),
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = appearance.fontSize.sp,
        lineHeight = (appearance.fontSize * 1.04f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        color = Color(appearance.textColor),
        textAlign = when (appearance.alignment) { 1 -> TextAlign.Center; 2 -> TextAlign.Right; else -> TextAlign.Left })
}

@Composable
internal fun ConnectedCameraConsole(vm: MainViewModel, session: CameraSession, busy: Boolean,
    openSettings: () -> Unit, openLighting: () -> Unit, refresh: () -> Unit, disconnect: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ConnectedAppearanceStore(context) }
    var saved by remember { mutableStateOf(store.load()) }
    var draft by remember { mutableStateOf(saved) }
    var editor by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val temporaryImages = remember { mutableListOf<String>() }
    val savedImage by rememberUpdatedState(saved.image)
    DisposableEffect(store) { onDispose { temporaryImages.filter { it != savedImage }.forEach(store::discard) } }
    val appearance = if (editor) draft else saved
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(appearance.image) { bitmap = store.bitmap(appearance.image) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            runCatching { store.importImage(uri) }.onSuccess { key ->
                temporaryImages += key
                draft = draft.copy(image = key, zoom = 1f, panX = 0f, panY = 0f)
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; vm.notify(it.message ?: "无法导入背景图片") }
            importing = false
        }
    }
    val config by vm.config.collectAsState()
    val photos by vm.photos.collectAsState()
    val loaded by vm.hasLoadedPhotos.collectAsState()
    val tasks by vm.downloadTasks.collectAsState()
    val taskCount = tasks.count { it.status == DownloadTaskStatus.QUEUED || it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING }
    val failedCount = tasks.count { it.status == DownloadTaskStatus.FAILED }
    val transferring = tasks.firstOrNull { it.status == DownloadTaskStatus.DOWNLOADING }
    val device = session.details.model?.takeIf { it.isNotBlank() } ?: session.name
    val backgroundLayer = rememberGraphicsLayer()
    val backdrop = remember(backgroundLayer) { BridgeBackdrop(backgroundLayer) }
    val recent = listOfNotNull(session.details.recentFocalLength, session.details.recentAperture, session.details.recentShutter, session.details.recentIso?.let { "ISO $it" }).joinToString(" · ")
    fun finishEditor(save: Boolean) {
        if (importing) return
        if (save) {
            val previous = saved.image
            store.save(draft)
            saved = draft
            if (previous != saved.image) store.discard(previous)
        }
        temporaryImages.filter { it != saved.image }.forEach(store::discard)
        temporaryImages.clear()
        draft = saved
        editor = false
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("connectedConsole")) {
        val previewAspect = maxWidth.value / maxHeight.value
        Box(Modifier.matchParentSize().bridgeBackdropSource(backdrop)) {
            PosterBackground(bitmap, appearance, Modifier.fillMaxSize())
            if (bitmap == null) Box(Modifier.matchParentSize().bridgeColorBloom(config.colorTheme))
        }
        val contentHeight = maxOf(maxHeight, 720.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).height(contentHeight).statusBarsPadding().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 112.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Link", Modifier.weight(1f), color = BridgeWhite, fontSize = 14.sp, letterSpacing = 2.sp)
                ConsoleIcon(Icons.Default.Palette, "自定义外观") { draft = saved; editor = true }
                ConsoleIcon(Icons.Default.Info, "设备详情") { info = true }
                ConsoleIcon(Icons.Default.Settings, "设置", openSettings)
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.Center) {
                if (appearance.text.isNotBlank()) Text(device, color = BridgeWhite.copy(alpha = .8f), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                PosterTitle(appearance, device, Modifier.testTag("connectedPosterTitle"))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(Color(0xFFAEE4CD), CircleShape))
                    Text("${session.transport.title} · ${if (busy) "工作中" else "已连接"}", Modifier.padding(start = 8.dp), color = BridgeWhite, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .bridgeFrostedPanel(backdrop, BridgeFrost, opacity = .5f).border(BridgeGlassEdge, RoundedCornerShape(28.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConsoleMetric("已读取文件", if (loaded || photos.isNotEmpty()) "${photos.size}" else "待读取", Modifier.weight(1f))
                    session.details.batteryPercent?.let { ConsoleMetric("相机电量", "$it%", Modifier.weight(1f)) }
                    ConsoleMetric("连接方式", session.transport.title, Modifier.weight(1f))
                }
                if (recent.isNotBlank()) Column {
                    Text("最近照片参数", color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall)
                    Text(recent, color = BridgeWhite, style = MaterialTheme.typography.bodyMedium)
                }
                Text(when { taskCount > 0 -> "正在传输 · $taskCount 项任务"; failedCount > 0 -> "$failedCount 项传输失败 · 可在下载页重试"; busy -> "相机正在处理，请稍候"; else -> "相机已就绪" },
                    color = BridgeWhite.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
                if (transferring != null && transferring.totalBytes > 0) {
                    LinearProgressIndicator(progress = { (transferring.downloadedBytes.toFloat() / transferring.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp), color = BridgeWhite, trackColor = BridgeWhite.copy(alpha = .15f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(refresh, Modifier.weight(1f), enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite)) { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Text("刷新", Modifier.padding(start = 5.dp)) }
                    OutlinedButton({ tools = true }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite)) { Text("工具") }
                    OutlinedButton(disconnect, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFC3BB))) { Text("断开") }
                }
            }
        }
        if (editor) AppearanceEditor(draft, { draft = it }, bitmap, device, previewAspect, importing,
            choose = { runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { vm.notify("无法打开相册选择器") } },
            cancel = { finishEditor(false) }, save = { finishEditor(true) })
    }
    if (info) BridgeGlassBottomSheet({ info = false }) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设备详情", style = MaterialTheme.typography.titleLarge, color = BridgeWhite)
            Text(session.name, color = BridgeWhite)
            val details = session.details
            listOfNotNull(details.manufacturer?.let { "品牌" to it }, details.deviceVersion?.let { "固件" to it },
                details.serialNumber?.let { "序列号" to "•••• ${it.takeLast(4)}" }, details.lensName?.let { "镜头" to it }, details.lensSpec?.let { "镜头规格" to it },
                details.recentCapturedAt?.let { "最近拍摄" to java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) }).forEach { (name, value) ->
                Row { Text(name, Modifier.weight(1f), color = BridgeWhite.copy(alpha = .6f)); Text(value, color = BridgeWhite) }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    if (tools) BridgeGlassBottomSheet({ tools = false }) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("工具", color = BridgeWhite, style = MaterialTheme.typography.titleLarge)
            TextButton({ tools = false; context.startActivity(android.content.Intent(context, GpsSyncActivity::class.java)) }) { Text("GPS 同步", color = BridgeWhite) }
            TextButton({ tools = false; openLighting() }) { Text("屏幕补光", color = BridgeWhite) }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable private fun ConsoleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, click: () -> Unit) {
    IconButton(click, Modifier.size(48.dp)) { Box(Modifier.size(34.dp).bridgeGlass(CircleShape), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(18.dp), tint = BridgeWhite) } }
}
@Composable private fun ConsoleMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier) { Text(label, color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall); Text(value, color = BridgeWhite, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun AppearanceEditor(value: ConnectedAppearance, change: (ConnectedAppearance) -> Unit, bitmap: Bitmap?, fallback: String,
    previewAspect: Float, importing: Boolean, choose: () -> Unit, cancel: () -> Unit, save: () -> Unit) {
    val latest by rememberUpdatedState(value)
    val update by rememberUpdatedState(change)
    val focus = LocalFocusManager.current
    BridgeGlassBottomSheet(cancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).imePadding()) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("自定义外观", Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.titleLarge)
                IconButton(cancel, enabled = !importing) { Icon(Icons.Default.Close, "取消外观编辑", tint = BridgeWhite) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.height(200.dp).aspectRatio(previewAspect).clip(RoundedCornerShape(14.dp)).testTag("appearancePreview")
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val current = latest
                                val next = (current.zoom * zoom).coerceIn(1f, 3f)
                                if (bitmap != null) {
                                    val scale = maxOf(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height) * next
                                    val extraX = (bitmap.width * scale - size.width) * .5f
                                    val extraY = (bitmap.height * scale - size.height) * .5f
                                    update(current.copy(zoom = next, panX = (current.panX + if (extraX > 1f) pan.x / extraX else 0f).coerceIn(-1f, 1f),
                                        panY = (current.panY + if (extraY > 1f) pan.y / extraY else 0f).coerceIn(-1f, 1f)))
                                }
                            }
                        }) {
                        PosterBackground(bitmap, value, Modifier.fillMaxSize())
                        PosterTitle(value.copy(fontSize = value.fontSize * .3f), fallback, Modifier.align(Alignment.Center).padding(6.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(choose, enabled = !importing) { Text(if (importing) "导入中…" else "选择背景图片") }
                        if (value.image.isNotEmpty()) TextButton({ change(value.copy(image = "", zoom = 1f, panX = 0f, panY = 0f)) }, enabled = !importing) { Text("移除背景图片", color = BridgeWhite) }
                        Text("拖动移动 · 双指缩放", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedTextField(value.text, { change(value.copy(text = it.lines().take(2).joinToString("\n").take(80))) }, Modifier.fillMaxWidth(),
                    label = { Text("展示文字（最多两排）") }, placeholder = { Text("留空显示相机型号") }, maxLines = 2, minLines = 2, colors = bridgeFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("左对齐", "居中", "右对齐").forEachIndexed { index, text -> FilterChip(value.alignment == index, { change(value.copy(alignment = index)) }, label = { Text(text) }) }
                }
                AppearanceSlider("字号", value.fontSize, 28f..80f) { change(value.copy(fontSize = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("白色" to -1, "墨色" to 0xFF19212A.toInt(), "暖白" to 0xFFFFE8CC.toInt()).forEach { (label, color) ->
                        FilterChip(value.textColor == color, { change(value.copy(textColor = color)) }, label = { Text(label) })
                    }
                }
                AppearanceSlider("背景亮度", value.brightness, .5f..1.3f) { change(value.copy(brightness = it)) }
                AppearanceSlider("遮罩深度", value.veil, 0f..0.75f) { change(value.copy(veil = it)) }
                if (bitmap != null) {
                    AppearanceSlider("图片缩放", value.zoom, 1f..3f) { change(value.copy(zoom = it)) }
                    AppearanceSlider("水平位置", value.panX, -1f..1f) { change(value.copy(panX = it)) }
                    AppearanceSlider("垂直位置", value.panY, -1f..1f) { change(value.copy(panY = it)) }
                    TextButton({ change(value.copy(zoom = 1f, panX = 0f, panY = 0f)) }) { Text("重置图片位置", color = BridgeWhite) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ focus.clearFocus(); cancel() }, Modifier.weight(1f), enabled = !importing) { Text("取消", color = BridgeWhite) }
                Button({ focus.clearFocus(); save() }, Modifier.weight(1f), enabled = !importing) { Text("保存外观") }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable private fun AppearanceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    Column { Text(label, color = BridgeWhite.copy(alpha = .8f), style = MaterialTheme.typography.labelMedium); BridgeSlider(value, change, valueRange = range) }
}
