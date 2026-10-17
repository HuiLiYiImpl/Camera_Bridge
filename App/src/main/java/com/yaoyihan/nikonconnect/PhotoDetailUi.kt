@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yaoyihan.nikonconnect

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal val PhotoDetailBackground = Color(0xFF15171A)
internal const val PhotoLutDefaultIntensity = .5f
private val QuarterTurnPhotoScale = object : ContentScale {
    override fun computeScaleFactor(srcSize: androidx.compose.ui.geometry.Size, dstSize: androidx.compose.ui.geometry.Size): ScaleFactor {
        val scale = minOf(dstSize.width / srcSize.height, dstSize.height / srcSize.width)
        return ScaleFactor(scale, scale)
    }
}
internal fun photoContentScale(rotation: Int): ContentScale = if (rotation % 180 == 0) ContentScale.Fit else QuarterTurnPhotoScale
internal enum class PhotoDetailTool(val title: String, val icon: ImageVector) {
    INFO("拍摄参数", Icons.Default.Info), COLOR("色彩", Icons.Default.ColorLens),
    WATERMARK("水印", Icons.Default.TextFields), AI("AI", Icons.Default.AutoAwesome)
}

/** A single extent drives the sheet and the photo; direct input never waits for a tween. */
internal class PhotoDetailPanelState(private val scope: CoroutineScope) {
    private val animated = Animatable(0f)
    private val dockHidden = Animatable(0f)
    private var dragged by mutableStateOf<Float?>(null)
    private var job: Job? = null
    var target by mutableFloatStateOf(0f)
        private set
    var height = 1f
    val fraction get() = dragged ?: animated.value
    val dockHiddenFraction get() = dockHidden.value
    val open get() = target > 0f || fraction > .001f || dockHidden.value > .001f

    fun moveTo(value: Float) {
        target = value
        val start = fraction
        job?.cancel()
        dragged = start
        job = scope.launch {
            animated.snapTo(start)
            dragged = null
            if (value > 0f) dockHidden.animateTo(1f, tween(180))
            animated.animateTo(value, spring(dampingRatio = .94f, stiffness = 340f))
            if (value == 0f) dockHidden.animateTo(0f, tween(220))
        }
    }
    fun show() { if (target == 0f) moveTo(.62f) }
    fun close() = moveTo(0f)
    fun startDrag() { job?.cancel(); dragged = fraction }
    fun dragBy(delta: Float) { dragged = (fraction - delta / height.coerceAtLeast(1f)).coerceIn(0f, 1f) }
    fun endDrag(velocity: Float) {
        val anchors = listOf(0f, .62f, 1f)
        val here = fraction
        val next = when {
            velocity < -height * .8f -> anchors.firstOrNull { it > here + .04f } ?: 1f
            velocity > height * .8f -> anchors.lastOrNull { it < here - .04f } ?: 0f
            else -> anchors.minBy { abs(it - here) }
        }
        moveTo(next)
    }
}

@Composable
internal fun PhotoDetailPanelHeader(tool: PhotoDetailTool, state: PhotoDetailPanelState, subtitle: String?, close: () -> Unit = { state.close() }) {
    Column(Modifier.fillMaxWidth().draggable(rememberDraggableState(state::dragBy), Orientation.Vertical,
        onDragStarted = { state.startDrag() }, onDragStopped = { state.endDrag(it) })) {
        Box(Modifier.fillMaxWidth().height(24.dp).clickable(role = Role.Button, onClickLabel = "展开或收起面板") {
            state.moveTo(if (state.target > .8f) .62f else 1f)
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(34.dp).height(4.dp).background(Color.White.copy(alpha = .35f), CircleShape))
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tool.title, color = BridgeWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(close) { Icon(Icons.Default.KeyboardArrowDown, "收起面板", tint = BridgeWhite) }
        }
    }
}

// The surface slides at a fixed size; controls stay at the bottom of its visible portion.
internal fun Modifier.photoPanelContentHeight(state: PhotoDetailPanelState): Modifier = layout { measurable, constraints ->
    val visibleHeight = (constraints.maxHeight * state.fraction.coerceAtLeast(.62f)).roundToInt().coerceIn(0, constraints.maxHeight)
    val child = measurable.measure(constraints.copy(minHeight = visibleHeight, maxHeight = visibleHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { child.place(0, 0) }
}

/** Retain visited tools but only measure/place the active one: no hidden hit targets or scroll reset. */
@Composable
internal fun RetainedPhotoDetailTools(tool: PhotoDetailTool, photoKey: String, content: @Composable (PhotoDetailTool) -> Unit) {
    val appearance = remember(tool) { Animatable(0f) }
    LaunchedEffect(appearance) { appearance.animateTo(1f, tween(180)) }
    val visited = remember(photoKey) { mutableStateListOf(tool) }
    SideEffect { if (tool !in visited) visited.add(tool) }
    val tools = if (tool in visited) visited.toList() else visited + tool
    Layout(content = { tools.forEach { item -> key(item) { Box(Modifier.fillMaxSize()) { content(item) } } } }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = appearance.value; translationY = 8.dp.toPx() * (1f - appearance.value) }) { measurables, constraints ->
        val current = measurables[tools.indexOf(tool)].measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) { current.place(0, 0) }
    }
}

@Composable
internal fun PhotoDetailChrome(visible: Boolean, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val child = measurables.single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) { if (visible) child.place(0, 0) }
    }
}

@Composable
internal fun PhotoWatermarkPanel(presets: List<WatermarkPreset>, selectedName: String?, select: (WatermarkPreset?) -> Unit, create: () -> Unit) {
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "none") {
                Surface(Modifier.fillMaxWidth().clickable { select(null) }, shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = .06f)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FilterNone, null, tint = BridgeWhite)
                        Text("不使用水印", Modifier.weight(1f).padding(horizontal = 12.dp), color = BridgeWhite)
                        if (selectedName == null) Icon(Icons.Default.Check, "已选中", tint = BridgeWhite)
                    }
                }
            }
            items(presets, key = { it.id }) { preset ->
                Surface(Modifier.fillMaxWidth().clickable { select(preset) }, shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = if (preset.name == selectedName) .16f else .06f)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        WatermarkTemplatePreview(preset.layout, Modifier.size(68.dp, 50.dp))
                        Text(preset.name, Modifier.weight(1f).padding(horizontal = 12.dp), color = BridgeWhite, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (preset.name == selectedName) Icon(Icons.Default.Check, "已选中", tint = BridgeWhite)
                    }
                }
            }
        }
        TextButton(create, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("新建水印预设") }
    }
}

@Composable
internal fun PhotoDetailToolButton(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, click: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.height(52.dp).bridgePress(interaction).clip(RoundedCornerShape(24.dp))
        .background(if (selected) Color.White.copy(alpha = .2f) else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = click)
        .semantics { if (label != "导出") stateDescription = if (selected) "已展开" else "已收起" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(23.dp), tint = BridgeWhite.copy(alpha = if (enabled) 1f else .35f))
        Spacer(Modifier.height(3.dp))
        Text(label, color = BridgeWhite.copy(alpha = if (enabled) .9f else .35f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
internal fun PhotoColorPanel(luts: List<LutEntry>, selected: LutEntry?, intensity: Float, select: (LutEntry?) -> Unit,
    setIntensity: (Float) -> Unit, finishIntensity: () -> Unit, comparing: Boolean, compare: () -> Unit,
    loadCover: (suspend (LutEntry) -> Bitmap?)?, enabled: Boolean) {
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        LazyRow(state = list, modifier = Modifier.height(126.dp).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "original") { PhotoPresetTile(null, selected == null, enabled, loadCover) { select(null) } }
            items(luts, key = { it.id }) { entry -> PhotoPresetTile(entry, selected?.id == entry.id, enabled, loadCover) { select(entry) } }
        }
        if (luts.isEmpty()) Text("还没有色彩预设，可在色彩页面导入。", color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("强度 ${(intensity * 100).roundToInt()}%", Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.labelLarge)
            TextButton(compare, enabled = enabled && selected != null) { Icon(Icons.Default.Compare, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(if (comparing) "返回效果" else "对比原图") }
        }
        BridgeSlider(intensity, setIntensity, enabled = enabled && selected != null, onValueChangeFinished = finishIntensity,
            valueRange = 0f..1f, modifier = Modifier.fillMaxWidth().height(44.dp))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PhotoPresetTile(entry: LutEntry?, selected: Boolean, enabled: Boolean, loadCover: (suspend (LutEntry) -> Bitmap?)?, click: () -> Unit) {
    val context = LocalContext.current
    var cover by remember(entry?.id, entry?.coverKey) { mutableStateOf(entry?.let { LutPreviewImages.cached(context, it, 160, false)?.original }) }
    LaunchedEffect(entry?.id, entry?.coverKey) { if (entry != null && cover == null) cover = loadCover?.invoke(entry) }
    // A fixed 4:5 card fits the row without a weighted image overflowing its clipping bounds.
    Box(Modifier.width(88.dp).aspectRatio(.8f)
        .border(if (selected) 2.dp else 1.dp, Color.White.copy(alpha = if (selected) 1f else .75f), RoundedCornerShape(12.dp))
        .clip(RoundedCornerShape(12.dp)).clickable(enabled, role = Role.Button, onClick = click)
        .semantics { contentDescription = entry?.name ?: "原始色彩" }
        .padding(3.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
        cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(if (entry == null) Icons.Default.FilterNone else Icons.Default.ColorLens, null, tint = BridgeWhite.copy(alpha = .65f))
        if (selected) Icon(Icons.Default.CheckCircle, "已选中", Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp), tint = BridgeWhite)
    }
}

@Composable
internal fun PhotoInfoPanel(name: String, size: Long, metadata: PhotoMetadata?, asset: PhotoAsset?, record: DownloadRecord?,
    originalLoaded: Boolean, loading: Boolean, loadOriginal: (() -> Unit)?) {
    val context = LocalContext.current
    var locationAccess by remember(record?.uri) { mutableStateOf(DownloadedPhotoDetailsReader.hasLocationAccess(context)) }
    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationAccess = DownloadedPhotoDetailsReader.hasLocationAccess(context) }
    var details by remember(record?.uri) { mutableStateOf<DownloadedPhotoDetails?>(null) }
    var reading by remember(record?.uri) { mutableStateOf(record != null) }
    LaunchedEffect(record?.uri, locationAccess) {
        if (record != null) {
            reading = true
            details = withContext(Dispatchers.IO) {
                try { DownloadedPhotoDetailsReader.read(context, record) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            reading = false
        }
    }
    val meta = details?.metadata ?: metadata ?: PhotoMetadata(capturedAt = asset?.capturedAt)
    val width = details?.width ?: meta.pixelWidth ?: asset?.imageWidth?.takeIf { it > 0 }
    val height = details?.height ?: meta.pixelHeight ?: asset?.imageHeight?.takeIf { it > 0 }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val list = rememberLazyListState()
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!originalLoaded && loadOriginal != null) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading) "正在读取原图和拍摄信息…" else "查看原图以读取完整拍摄参数", Modifier.weight(1f), color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
                TextButton(loadOriginal, enabled = !loading) { Text("查看原图") }
            }
        }
        if (reading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = BridgeWhite) }
        item {
            Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .06f), RoundedCornerShape(16.dp)).padding(vertical = 14.dp)) {
                listOf("光圈" to meta.aperture, "快门" to meta.shutterSpeed, "ISO" to meta.iso?.toString(), "焦距" to meta.focalLength).forEach { (label, value) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value ?: "—", color = BridgeWhite, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(label, color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { InfoLine("相机", listOfNotNull(meta.cameraBrand, meta.cameraModel).joinToString(" ").ifBlank { "未记录" }) }
        meta.lensModel?.let { item { InfoLine("镜头", it) } }
        item { InfoLine("拍摄时间", meta.capturedAt?.let { dateFormat.format(it) } ?: "未记录") }
        meta.equivalentFocalLength?.let { item { InfoLine("等效焦距", it) } }
        item { HorizontalDivider(color = Color.White.copy(alpha = .1f)); Spacer(Modifier.height(4.dp)) }
        item { InfoLine("文件", name) }
        item { InfoLine("格式 / 大小", "${name.substringAfterLast('.', "图片").uppercase(Locale.ROOT)} · ${size.prettySize()}") }
        item { InfoLine("分辨率", if (width != null && height != null) "$width × $height" else "未记录") }
        record?.let { item { InfoLine("保存时间", dateFormat.format(Date(it.completedAt))) } }
        item { HorizontalDivider(color = Color.White.copy(alpha = .1f)) }
        if (record != null && !locationAccess) item {
            TextButton({ requestLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }) { Text("允许读取照片位置信息") }
        } else if (meta.latitude != null && meta.longitude != null) {
            item { InfoLine("纬度", "%.6f°".format(Locale.US, meta.latitude)) }
            item { InfoLine("经度", "%.6f°".format(Locale.US, meta.longitude)) }
            meta.altitudeMeters?.let { item { InfoLine("海拔", "%.1f 米".format(Locale.US, it)) } }
        } else item { Text(if (originalLoaded || record != null) "未记录 GPS 位置信息" else "加载原图后查看位置信息", color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall) }
        if (record != null && !reading && details == null) item { Text("文件详情暂时无法读取，请确认原文件仍在手机中。", color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(72.dp), color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.bodySmall)
    }
}
