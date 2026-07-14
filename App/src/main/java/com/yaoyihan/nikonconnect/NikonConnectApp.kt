package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.util.Date
import kotlin.math.roundToInt

private val Canvas = Color(0xFFF9F9F8)
private val Ink = Color(0xFF1A1A1A)
private val Muted = Color(0xFF6B727E)
private val Amber = Color(0xFFD4770A)
private val AmberSoft = Color(0xFFF7E8D2)
private val Green = Color(0xFF24984B)
private data class BridgePalette(
    val night: Color,
    val wine: Color,
    val ember: Color,
    val copper: Color,
    val white: Color,
    val surface: Color,
    val elevated: Color,
    val deep: Color,
)

private fun bridgePalette(theme: AppColorTheme): BridgePalette = when (theme) {
    AppColorTheme.DARKROOM_ORANGE -> BridgePalette(Color(0xFF0E090C), Color(0xFF35140F), Color(0xFFFF7133), Color(0xFFB95732), Color(0xFFFFF7F1), Color(0xFF181015), Color(0xFF211419), Color(0xFF08070A))
    AppColorTheme.NIKON_YELLOW -> BridgePalette(Color(0xFF0B0B0B), Color(0xFF252107), Color(0xFFFFD400), Color(0xFFA88B00), Color(0xFFF7F7F2), Color(0xFF181818), Color(0xFF242424), Color(0xFF050505))
    AppColorTheme.PROFESSIONAL_GRAY -> BridgePalette(Color(0xFF101214), Color(0xFF24272B), Color(0xFFD8A64A), Color(0xFF8B7449), Color(0xFFF3F4F5), Color(0xFF1A1D20), Color(0xFF24282C), Color(0xFF090A0C))
    AppColorTheme.DEEP_BLUE -> BridgePalette(Color(0xFF07111C), Color(0xFF0D2941), Color(0xFF49AFFF), Color(0xFF236D9E), Color(0xFFF1F8FF), Color(0xFF0E1B28), Color(0xFF14283A), Color(0xFF030A11))
}

private val LocalBridgePalette = staticCompositionLocalOf { bridgePalette(AppColorTheme.DARKROOM_ORANGE) }
private val BridgeNight: Color @Composable get() = LocalBridgePalette.current.night
private val BridgeWine: Color @Composable get() = LocalBridgePalette.current.wine
private val BridgeEmber: Color @Composable get() = LocalBridgePalette.current.ember
private val BridgeCopper: Color @Composable get() = LocalBridgePalette.current.copper
private val BridgeWhite: Color @Composable get() = LocalBridgePalette.current.white
private val BridgeSurface: Color @Composable get() = LocalBridgePalette.current.surface
private val BridgeElevated: Color @Composable get() = LocalBridgePalette.current.elevated
private val BridgeDeep: Color @Composable get() = LocalBridgePalette.current.deep
private data class InlineTask(val title: String, val detail: String, val running: Boolean, val failed: Boolean = false)
private enum class ConnectionMode { CENTER, WIFI, USB }
private enum class Tab(val title: String) { CAMERA("\u76f8\u673a"), PHOTOS("\u7167\u7247"), DOWNLOADS("\u4e0b\u8f7d"), LUT("LUT"), WATERMARK("\u6c34\u5370"), SETTINGS("\u8bbe\u7f6e") }
private enum class PhotoFilter(val title: String) { ALL("\u5168\u90e8"), JPG("JPG"), RAW("RAW"), VIDEO("\u89c6\u9891") }
private fun PhotoAsset.matches(filter: PhotoFilter) = when (filter) {
    PhotoFilter.ALL -> true
    PhotoFilter.JPG -> type == "JPEG"
    PhotoFilter.RAW -> type == "RAW"
    PhotoFilter.VIDEO -> type in setOf("MOV", "MP4")
}
// ponytail: infer type from extension instead of adding a format field to DownloadRecord
private fun DownloadRecord.matches(filter: PhotoFilter) = when (filter) {
    PhotoFilter.ALL -> true
    PhotoFilter.JPG -> name.substringAfterLast('.', "").equals("jpg", true) || name.substringAfterLast('.', "").equals("jpeg", true)
    PhotoFilter.RAW -> name.substringAfterLast('.', "").lowercase() in setOf("nef", "nrw", "cr2", "arw", "dng", "raf")
    PhotoFilter.VIDEO -> name.substringAfterLast('.', "").lowercase() in setOf("mov", "mp4")
}

@Composable
fun NikonConnectApp(vm: MainViewModel = viewModel()) {
    val workflow by vm.workflow.collectAsState()
    val busy by vm.isBusy.collectAsState()
    val notice by vm.notice.collectAsState()
    val snackbarText by vm.snackbar.collectAsState()
    val session by vm.session.collectAsState()
    val config by vm.config.collectAsState()
    val diagnostic by vm.diagnosticState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.CAMERA) }
    val landing = tab == Tab.CAMERA && session == null
    LaunchedEffect(session) {
        if (session == null) tab = Tab.CAMERA
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.checkConnectionOnResume() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(snackbarText) { snackbarText?.let { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) } }
    val palette = bridgePalette(config.colorTheme)
    CompositionLocalProvider(LocalBridgePalette provides palette) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = palette.night, surface = palette.surface, primary = palette.ember, onPrimary = palette.night)) {
            Scaffold(
                containerColor = BridgeNight,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        if (!landing) AnimatedActivityPill(
                            notice,
                            busy,
                            if (workflow == Workflow.ERROR) vm::retry else null,
                            diagnostic.takeIf { workflow == Workflow.ERROR },
                            { vm.exportDiagnostics { it.onSuccess { uri -> shareDiagnostic(context, uri) } } },
                            { copyDiagnostic(context, vm.diagnosticCopyText()) },
                        )
                        AnimatedContent(tab, label = "tab", modifier = Modifier.weight(1f)) { screen ->
                            when (screen) {
                                Tab.CAMERA -> CameraScreen(vm, workflow, busy, notice, { tab = Tab.SETTINGS }) { tab = Tab.PHOTOS }
                                Tab.PHOTOS -> GalleryScreen(vm, busy)
                                Tab.DOWNLOADS -> DownloadsScreen(vm)
                                Tab.LUT -> LutScreen(vm) { tab = Tab.WATERMARK }
                                Tab.WATERMARK -> WatermarkScreen(vm) { tab = Tab.LUT }
                                Tab.SETTINGS -> SettingsScreen(vm)
                            }
                        }
                    }
                    if (!landing) BridgeNavigation(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable
private fun BridgeNavigation(selected: Tab, select: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                color = BridgeNight.copy(alpha = .78f),
                shadowElevation = 10.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)),
            ) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tab.entries.filterNot { it == Tab.WATERMARK }.forEach { item ->
                        val active = item == selected || (selected == Tab.WATERMARK && item == Tab.LUT)
                        if (active) {
                            Surface(
                                Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(26.dp),
                                color = BridgeWhite.copy(alpha = .94f),
                            ) {
                                Column(
                                    Modifier.fillMaxSize().clickable { select(item) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(tabIcon(item), item.title, tint = BridgeNight, modifier = Modifier.size(21.dp))
                                    Text(item.title, color = BridgeNight, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        } else {
                            Box(
                                Modifier.weight(1f).height(52.dp).clickable { select(item) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(tabIcon(item), item.title, tint = BridgeWhite.copy(alpha = .68f), modifier = Modifier.size(23.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityPill(text: String, busy: Boolean, modifier: Modifier, retry: (() -> Unit)? = null, diagnostic: DiagnosticState? = null, exportDiagnostic: (() -> Unit)? = null, copyDiagnostic: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = if (busy) BridgeEmber.copy(alpha = .16f) else if (retry != null) Color(0xFFE57373).copy(alpha = .12f) else BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, if (retry != null) Color(0xFFE57373).copy(alpha = .3f) else BridgeWhite.copy(alpha = .1f))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BridgeEmber) else Icon(if (retry != null) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, tint = if (retry != null) Color(0xFFE57373) else BridgeEmber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (retry != null) { TextButton(retry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("重试", color = Color(0xFFE57373), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) } }
            }
            diagnostic?.diagnosticId?.let { id ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("诊断编号：$id", Modifier.weight(1f), color = BridgeWhite.copy(alpha = .58f), style = MaterialTheme.typography.labelSmall)
                    copyDiagnostic?.let { TextButton(it, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("复制", color = BridgeWhite.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall) } }
                    exportDiagnostic?.let { TextButton(it, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("导出诊断", color = BridgeEmber, style = MaterialTheme.typography.labelSmall) } }
                }
            }
        }
    }
}

@Composable
private fun CameraScreen(vm: MainViewModel, state: Workflow, busy: Boolean, notice: String?, openSettings: () -> Unit, openPhotos: () -> Unit) {
    val session by vm.session.collectAsState()
    val photos by vm.photos.collectAsState()
    val config by vm.config.collectAsState()
    val diagnostic by vm.diagnosticState.collectAsState()
    val context = LocalContext.current
    var mode by remember { mutableStateOf(ConnectionMode.CENTER) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    if (session == null) when (mode) {
        ConnectionMode.CENTER -> ConnectionCenter(config, state, notice, diagnostic, openSettings, { mode = ConnectionMode.WIFI }, { mode = ConnectionMode.USB }, { vm.exportDiagnostics { it.onSuccess { uri -> shareDiagnostic(context, uri) } } }, { copyDiagnostic(context, vm.diagnosticCopyText()) })
        ConnectionMode.WIFI -> WifiConnectionFlow(config.brand, state, busy, notice, { mode = ConnectionMode.CENTER }, { brand -> vm.updateConfig { it.copy(brand = brand) } }, { vm.openWifiSettings() }, { vm.connect(true) })
        ConnectionMode.USB -> UsbConnectionFlow(vm, busy, { mode = ConnectionMode.CENTER })
    }
    else ConnectedCameraScreen(session!!, photos.size, busy, openPhotos, vm::loadPhotos) {
        if (busy) confirmDisconnect = true else vm.disconnect()
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("中止当前任务并断开？", color = BridgeWhite) },
        text = { Text("相机正在读取或传输文件，断开连接会中止当前任务。", color = BridgeWhite.copy(alpha = .72f)) },
        confirmButton = { TextButton({ confirmDisconnect = false; vm.disconnect() }) { Text("仍然断开", color = Color(0xFFFF7777)) } },
        dismissButton = { TextButton({ confirmDisconnect = false }) { Text("继续连接", color = BridgeEmber) } },
        containerColor = BridgeElevated,
    )
}

@Composable
private fun ConnectedCameraScreen(
    session: CameraSession,
    photoCount: Int,
    busy: Boolean,
    openPhotos: () -> Unit,
    refresh: () -> Unit,
    disconnect: () -> Unit,
) {
    val details = session.details
    val lensTitle = details.lensName ?: if (details.lensSpec != null) "已连接镜头" else "镜头信息暂不可用"
    val recentSettings = listOfNotNull(
        details.recentFocalLength,
        details.recentAperture,
        details.recentShutter,
        details.recentIso?.let { "ISO $it" },
    ).joinToString(" · ")
    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BridgeWine, BridgeNight, BridgeDeep))),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BridgePageLabel("相机", "设备状态与连接信息") }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .13f))) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(54.dp), shape = RoundedCornerShape(17.dp), color = BridgeEmber) { Icon(Icons.Default.CameraAlt, null, Modifier.padding(14.dp), tint = BridgeNight) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(session.name, color = BridgeWhite, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(details.manufacturer, details.deviceVersion?.let { "固件 $it" }).distinct().joinToString(" · ").ifBlank { "Nikon 相机" }, color = BridgeWhite.copy(alpha = .52f), style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(shape = RoundedCornerShape(14.dp), color = BridgeEmber.copy(alpha = .15f)) {
                            Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(BridgeEmber, CircleShape)); Spacer(Modifier.width(6.dp)); Text(if (busy) "工作中" else "已连接", color = BridgeEmber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = BridgeWhite.copy(alpha = .09f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(40.dp), shape = CircleShape, color = BridgeWhite.copy(alpha = .08f)) { Icon(Icons.Default.Camera, null, Modifier.padding(10.dp), tint = BridgeEmber) }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lensTitle, color = BridgeWhite, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(details.lensSpec, if (details.lensFromPhoto) "来自最近照片" else null).joinToString(" · ").ifBlank { "相机未提供实时镜头属性" }, color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (recentSettings.isNotBlank()) Surface(Modifier.fillMaxWidth().padding(top = 13.dp), shape = RoundedCornerShape(14.dp), color = BridgeNight.copy(alpha = .45f)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text("最近拍摄参数", color = BridgeWhite.copy(alpha = .45f), style = MaterialTheme.typography.labelSmall)
                            Text(recentSettings, color = BridgeWhite.copy(alpha = .86f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CameraMetricCard("照片", "$photoCount 张", Icons.Default.PhotoLibrary, Modifier.weight(1f))
                CameraMetricCard("电量", details.batteryPercent?.let { "$it%" } ?: "--", Icons.Default.BatteryFull, Modifier.weight(1f))
                CameraMetricCard("连接", session.transport.title, if (session.transport == ConnectionTransport.USB) Icons.Default.Usb else Icons.Default.Wifi, Modifier.weight(1f))
            }
        }
        if (details.deviceVersion != null || details.serialNumber != null || details.recentCapturedAt != null) item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = BridgeWhite.copy(alpha = .06f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("设备信息", color = BridgeWhite, fontWeight = FontWeight.Bold)
                    details.deviceVersion?.let { CameraInfoRow("固件版本", it) }
                    details.serialNumber?.let { CameraInfoRow("序列号", "•••• ${it.takeLast(4)}") }
                    details.recentCapturedAt?.let { timestamp ->
                        val time = remember(timestamp) { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(Date(timestamp)) }
                        CameraInfoRow("最近拍摄", time)
                    }
                }
            }
        }
        item {
            Button(openPhotos, Modifier.fillMaxWidth().height(56.dp), enabled = !busy && photoCount > 0, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) {
                Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("进入相册", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(refresh, Modifier.weight(1f).height(50.dp), enabled = !busy, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .18f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("重新读取")
                }
                OutlinedButton(disconnect, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF7777).copy(alpha = .45f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9090))) {
                    Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("断开连接")
                }
            }
        }
    }
}

@Composable
private fun CameraMetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = BridgeWhite.copy(alpha = .07f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .09f))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 13.dp)) {
            Icon(icon, null, tint = BridgeEmber, modifier = Modifier.size(19.dp))
            Spacer(Modifier.height(8.dp)); Text(value, color = BridgeWhite, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = BridgeWhite.copy(alpha = .45f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CameraInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = BridgeWhite.copy(alpha = .48f), style = MaterialTheme.typography.bodySmall)
        Text(value, color = BridgeWhite.copy(alpha = .86f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConnectionCenter(
    config: CameraConfig,
    state: Workflow,
    notice: String?,
    diagnostic: DiagnosticState,
    openSettings: () -> Unit,
    openWifi: () -> Unit,
    openUsb: () -> Unit,
    exportDiagnostic: () -> Unit,
    copyDiagnostic: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BridgeWine, BridgeNight, BridgeDeep))).padding(horizontal = 24.dp, vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CAMERA_BRIDGE", color = BridgeWhite, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("连接中心", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(openSettings, Modifier.background(BridgeWhite.copy(alpha = .09f), CircleShape)) { Icon(Icons.Default.Settings, "设置", tint = BridgeWhite) }
        }
        Spacer(Modifier.height(34.dp))
        Text("连接你的相机", color = BridgeWhite, fontSize = 32.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(8.dp))
        Text("通过无线或 USB 导入照片", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodyMedium)
        if (state == Workflow.ERROR && (notice != null || diagnostic.diagnosticId != null)) {
            DiagnosticErrorCard(notice ?: diagnostic.lastError ?: "连接失败", diagnostic, exportDiagnostic, copyDiagnostic)
        }
        Spacer(Modifier.height(24.dp))
        ConnectionMethodCard("Wi‑Fi 连接", "连接相机热点，浏览相机相册", Icons.Default.Wifi, true, openWifi)
        Spacer(Modifier.height(12.dp))
        ConnectionMethodCard("USB 连接", "连接数据线，快速导入照片", Icons.Default.Usb, false, openUsb)
        Spacer(Modifier.height(28.dp))
        Text("最近连接", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = BridgeWhite.copy(alpha = .06f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (config.lastTransport == ConnectionTransport.USB) Icons.Default.Usb else Icons.Default.Wifi, null, tint = BridgeEmber, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                if (config.lastCameraName.isBlank()) Text("暂无最近连接", color = BridgeWhite.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall)
                else Column { Text("${config.lastCameraName} · ${config.lastTransport.title}", color = BridgeWhite, fontWeight = FontWeight.SemiBold); Text("上次连接记录", color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("品牌只用于辅助选择，连接后会自动识别相机。", Modifier.fillMaxWidth(), color = BridgeWhite.copy(alpha = .42f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun DiagnosticErrorCard(message: String, diagnostic: DiagnosticState, export: () -> Unit, copy: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color(0xFFE57373).copy(alpha = .12f), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE57373).copy(alpha = .35f))) {
        Column(Modifier.padding(15.dp)) {
            Text(if (diagnostic.flapping) "USB 连接不稳定" else "连接失败", color = Color(0xFFFFA0A0), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(message, color = BridgeWhite.copy(alpha = .82f), style = MaterialTheme.typography.bodySmall)
            diagnostic.diagnosticId?.let { Text("诊断编号：$it", Modifier.padding(top = 6.dp), color = BridgeWhite.copy(alpha = .62f), style = MaterialTheme.typography.labelSmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(copy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("复制错误信息", color = BridgeWhite.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall) }
                TextButton(export, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("导出诊断", color = BridgeEmber, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun ConnectionMethodCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, click: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable { click() }, shape = RoundedCornerShape(22.dp), color = if (primary) BridgeEmber else BridgeWhite.copy(alpha = .08f), border = if (primary) null else androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(42.dp), shape = CircleShape, color = if (primary) BridgeNight.copy(alpha = .18f) else BridgeEmber.copy(alpha = .15f)) { Icon(icon, null, tint = if (primary) BridgeNight else BridgeEmber, modifier = Modifier.padding(10.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, color = if (primary) BridgeNight else BridgeWhite, fontWeight = FontWeight.Bold); Text(subtitle, color = if (primary) BridgeNight.copy(alpha = .68f) else BridgeWhite.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.Default.ArrowForward, null, tint = if (primary) BridgeNight else BridgeWhite.copy(alpha = .7f))
        }
    }
}

@Composable
private fun WifiConnectionFlow(brand: CameraBrand, state: Workflow, busy: Boolean, notice: String?, back: () -> Unit, selectBrand: (CameraBrand) -> Unit, openWifi: () -> Unit, connect: () -> Unit) {
    var brandChosen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("Wi‑Fi 连接", if (brandChosen) "按步骤连接相机热点" else "选择品牌作为辅助信息") { TextButton(back) { Text("返回", color = BridgeWhite) } }
        if (!brandChosen) {
            Text("相机品牌", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.labelLarge)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CameraBrand.entries, key = { it.name }) { item ->
                    Surface(Modifier.fillMaxWidth().clickable(enabled = item.available) { selectBrand(item); brandChosen = true }, shape = RoundedCornerShape(16.dp), color = BridgeWhite.copy(alpha = if (item.available) .09f else .045f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CameraAlt, null, tint = if (item.available) BridgeEmber else BridgeWhite.copy(alpha = .3f), modifier = Modifier.size(21.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.title, color = if (item.available) BridgeWhite else BridgeWhite.copy(alpha = .42f), fontWeight = FontWeight.SemiBold); Text(item.subtitle, color = BridgeWhite.copy(alpha = .45f), style = MaterialTheme.typography.bodySmall) }; if (item.available) Icon(Icons.Default.ChevronRight, null, tint = BridgeWhite.copy(alpha = .55f))
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = BridgeEmber.copy(alpha = .14f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeEmber.copy(alpha = .3f))) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CameraAlt, null, tint = BridgeEmber); Spacer(Modifier.width(10.dp)); Text("已选择 ${brand.title}，连接后仍会自动读取实际型号", color = BridgeWhite, style = MaterialTheme.typography.bodySmall) } } }
                item { ConnectionStep(1, "在相机中开启 Wi‑Fi", "打开相机的无线连接或智能设备连接") }
                item { ConnectionStep(2, "在手机系统 Wi‑Fi 中连接相机热点", "连接完成后返回 Camera Bridge") }
                item { ConnectionStep(3, "返回 Camera Bridge", "点击下方按钮开始检测并建立连接") }
                item { if (notice != null) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = BridgeWhite.copy(alpha = .07f)) { Text(notice, Modifier.padding(13.dp), color = BridgeWhite.copy(alpha = .82f), style = MaterialTheme.typography.bodySmall) } }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(openWifi, Modifier.weight(1f), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .2f))) { Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(6.dp)); Text("打开系统 Wi‑Fi") }; Button(connect, Modifier.weight(1f), enabled = !busy, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BridgeNight) else Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "正在连接" else "开始连接") } } }
            }
        }
    }
}

@Composable
private fun UsbConnectionFlow(vm: MainViewModel, busy: Boolean, back: () -> Unit) {
    val usb by vm.usbState.collectAsState()
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("USB 连接", "通过数据线导入照片") { TextButton(back) { Text("返回", color = BridgeWhite) } }
        val usbAction: () -> Unit = when {
            !usb.detected -> { { vm.detectUsbCamera(); Unit } }
            !usb.permissionGranted -> { { vm.requestUsbPermission(); Unit } }
            else -> { { vm.connectUsb(); Unit } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("连接 USB 相机", color = BridgeWhite, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            if (!usb.detected) {
                item { ConnectionStep(1, "使用支持数据传输的 USB‑C 数据线连接相机与手机", "不要使用仅充电线") }
                item { ConnectionStep(2, "在相机中选择文件传输 / MTP / PTP", "保持相机开机") }
                item { ConnectionStep(3, "点击检测 USB 相机", "系统会自动检查 USB 设备") }
            } else {
                item { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeEmber.copy(alpha = .35f))) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Usb, null, tint = BridgeEmber); Spacer(Modifier.width(10.dp)); Text("已检测到 USB 相机", color = BridgeWhite, fontWeight = FontWeight.Bold) }; Spacer(Modifier.height(8.dp)); Text(usb.model ?: usb.deviceName ?: "USB 相机", color = BridgeWhite, style = MaterialTheme.typography.titleMedium); Text(if (usb.permissionGranted) "MTP · 存储卡已就绪" else "等待 USB 访问权限", color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall) } } }
                item { Text("未检测到可读取的 USB 相机".takeIf { !usb.ready && usb.message.contains("未检测") } ?: usb.message, color = if (usb.ready) BridgeEmber else BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall) }
                item { Text("请确认：\n• 使用的是数据线，不是仅充电线\n• 手机已开启 OTG\n• 相机已开机\n• 相机 USB 模式为 MTP/PTP", color = BridgeWhite.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall) }
            }
            item { Button(usbAction, Modifier.fillMaxWidth().height(54.dp), enabled = !busy, shape = RoundedCornerShape(27.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) { Icon(if (!usb.detected) Icons.Default.Search else Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text(if (!usb.detected) "检测 USB 相机" else if (!usb.permissionGranted) "授权并读取相册" else "读取相册", fontWeight = FontWeight.Bold) } }
        }
    }
}

@Composable
private fun ConnectionLanding(state: Workflow, busy: Boolean, notice: String?, connect: () -> Unit, openSettings: () -> Unit, lastSsid: String, brand: CameraBrand, selectBrand: (CameraBrand) -> Unit) {
    val pulse by rememberInfiniteTransition(label = "bridge").animateFloat(0.94f, 1.08f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "signal")
    var showCameraPicker by remember { mutableStateOf(false) }
    val status = when (state) {
        Workflow.CONNECTING -> "\u6b63\u5728\u5efa\u7acb\u5b89\u5168\u6865\u63a5"
        Workflow.ERROR -> notice ?: "\u8bf7\u68c0\u67e5\u76f8\u673a Wi-Fi"
        else -> notice ?: if (lastSsid.isNotBlank()) "\u4e0a\u6b21\u8fde\u63a5\uff1a$lastSsid" else ""
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BridgeWine, BridgeNight, BridgeDeep)))) {
        Box(Modifier.size(420.dp).align(Alignment.TopCenter).offset(y = (-150).dp).background(Brush.radialGradient(listOf(BridgeEmber.copy(alpha = .42f), Color.Transparent)), CircleShape))
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("CAMERA_BRIDGE", color = BridgeWhite, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text("\u76f8\u673a\u539f\u56fe\u4f20\u8f93", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelSmall) }
                IconButton(openSettings, Modifier.background(BridgeWhite.copy(alpha = .09f), CircleShape)) { Icon(Icons.Default.Settings, "\u8bbe\u7f6e", tint = BridgeWhite) }
            }
            Spacer(Modifier.height(42.dp))
            Text("\u63a5\u5165\u76f8\u673a\nWi-Fi", color = BridgeWhite, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Normal)
            Spacer(Modifier.height(14.dp))
            Text(if (lastSsid.isNotBlank()) "\u70b9\u51fb\u4e0b\u65b9\u6309\u94ae\uff0c\u82e5\u5df2\u8fde\u63a5\u76f8\u673a Wi-Fi \u5c06\u81ea\u52a8\u5efa\u7acb\u6865\u63a5\u3002" else "\u6309\u4ee5\u4e0b\u6b65\u9aa4\u8fde\u63a5\u76f8\u673a\uff0c\u5b8c\u6210\u540e\u70b9\u51fb\u4e0b\u65b9\u6309\u94ae\u5efa\u7acb\u6865\u63a5\u3002", color = BridgeWhite.copy(alpha = .68f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            Box {
                Surface(Modifier.fillMaxWidth().clickable { showCameraPicker = true }, shape = RoundedCornerShape(18.dp), color = BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CameraAlt, null, tint = BridgeEmber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text("选择你的相机", color = BridgeWhite, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold); Text("${brand.title} · ${brand.subtitle}", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) }
                        Icon(Icons.Default.KeyboardArrowDown, "选择相机品牌", tint = BridgeWhite.copy(alpha = .7f))
                    }
                }
                DropdownMenu(showCameraPicker, { showCameraPicker = false }, modifier = Modifier.background(BridgeElevated)) {
                    CameraBrand.entries.forEach { item ->
                        DropdownMenuItem(
                            text = { Text("${item.title} · ${item.subtitle}", color = if (item.available) BridgeWhite else BridgeWhite.copy(alpha = .42f)) },
                            onClick = { if (item.available) { selectBrand(item); showCameraPicker = false } },
                            enabled = item.available,
                            leadingIcon = if (item == brand) ({ Icon(Icons.Default.Check, null, tint = BridgeEmber) }) else null,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (status.isNotBlank()) Surface(shape = RoundedCornerShape(20.dp), color = BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Wifi, null, tint = BridgeEmber, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text(status, color = BridgeWhite.copy(alpha = .88f), style = MaterialTheme.typography.labelMedium) }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionStep(1, "\u8fde\u63a5\u5230\u667a\u80fd\u8bbe\u5907", "\u76f8\u673a\u83dc\u5355 \u2192 \u8bbe\u7f6e \u2192 \u8fde\u63a5\u5230\u667a\u80fd\u8bbe\u5907")
                ConnectionStep(2, "Wi-Fi \u8fde\u63a5", "\u9009\u62e9 AP mode\uff08\u63a5\u5165\u70b9\u6a21\u5f0f\uff09")
                ConnectionStep(3, "\u5efa\u7acb Wi-Fi \u8fde\u63a5", "\u624b\u673a\u8fde\u63a5\u76f8\u673a\u70ed\u70b9\u540e\u70b9\u51fb\u4e0b\u65b9\u6309\u94ae")
            }
            Spacer(Modifier.weight(1f))
            ConnectionLightField(pulse)
            Spacer(Modifier.weight(1f))
            Button(connect, Modifier.fillMaxWidth().height(58.dp), enabled = !busy, shape = RoundedCornerShape(30.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeWhite, disabledContainerColor = BridgeCopper, disabledContentColor = BridgeWhite.copy(alpha = .75f))) {
                Icon(if (busy) Icons.Default.Sync else Icons.Default.Wifi, null); Spacer(Modifier.width(10.dp)); Text(if (busy) "\u6b63\u5728\u68c0\u6d4b\u8fde\u63a5" else "\u5f00\u59cb\u5efa\u7acb\u8fde\u63a5", fontWeight = FontWeight.Bold)
            }
            Text("\u8fde\u4e0d\u4e0a\u65f6\u4f1a\u81ea\u52a8\u8df3\u8f6c Wi-Fi \u8bbe\u7f6e", Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), color = BridgeWhite.copy(alpha = .48f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun ConnectionStep(index: Int, title: String, subtitle: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = BridgeWhite.copy(alpha = .05f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .08f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(24.dp), shape = CircleShape, color = BridgeEmber.copy(alpha = .18f)) { Box(contentAlignment = Alignment.Center) { Text("$index", color = BridgeEmber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) } }
            Spacer(Modifier.width(12.dp))
            Column { Text(title, color = BridgeWhite, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold); Text(subtitle, color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ConnectionLightField(pulse: Float) {
    Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(280.dp).offset(x = 54.dp, y = 46.dp).border(1.dp, BridgeCopper.copy(alpha = .23f), CircleShape))
        Box(Modifier.size(220.dp).offset(x = (-50).dp, y = 68.dp).border(1.dp, BridgeWhite.copy(alpha = .12f), CircleShape))
        Box(Modifier.width(202.dp).height(132.dp).graphicsLayer { rotationZ = -12f }.clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(BridgeEmber.copy(alpha = .28f), BridgeWine.copy(alpha = .08f)))).border(1.dp, BridgeWhite.copy(alpha = .18f), RoundedCornerShape(28.dp)))
        Box(Modifier.size(82.dp).scale(pulse).background(Brush.radialGradient(listOf(BridgeEmber.copy(alpha = .55f), BridgeEmber.copy(alpha = .08f))), CircleShape), contentAlignment = Alignment.Center) { Surface(Modifier.size(54.dp), shape = CircleShape, color = BridgeWhite) { Icon(Icons.Default.CameraAlt, null, Modifier.padding(14.dp), tint = BridgeWine) } }
    }
}

@Composable
private fun LensGlow(state: Workflow) {
    val pulse by rememberInfiniteTransition(label = "lens").animateFloat(0.92f, 1.12f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val color = BridgeEmber
    Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(116.dp).scale(if (state == Workflow.CONNECTING || state == Workflow.LOADING) pulse else 1f).background(color.copy(alpha = .12f), CircleShape))
        Surface(Modifier.size(82.dp), shape = CircleShape, color = BridgeWhite, shadowElevation = 8.dp) { Icon(Icons.Default.CameraAlt, null, Modifier.padding(21.dp), tint = BridgeNight) }
    }
}

@Composable
private fun Status(state: Workflow) {
    val (label, color) = when (state) {
        Workflow.WAITING -> "\u7b49\u5f85\u76f8\u673a Wi-Fi" to BridgeCopper
        Workflow.CONNECTING -> "\u6b63\u5728\u8fde\u63a5" to BridgeEmber
        Workflow.CONNECTED -> "\u5df2\u8fde\u63a5" to BridgeEmber
        Workflow.LOADING -> "\u6b63\u5728\u8bfb\u53d6\u7167\u7247" to BridgeEmber
        Workflow.DOWNLOADING -> "\u6b63\u5728\u4e0b\u8f7d" to BridgeEmber
        Workflow.ERROR -> "\u9700\u8981\u5904\u7406" to BridgeCopper
    }
    Surface(shape = RoundedCornerShape(40.dp), color = color.copy(alpha = .11f)) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun GalleryScreen(vm: MainViewModel, busy: Boolean) {
    val photos by vm.photos.collectAsState()
    val session by vm.session.collectAsState()
    val hasMore by vm.hasMorePhotos.collectAsState()
    val pageTask by vm.pageTask.collectAsState()
    val luts by vm.luts.collectAsState()
    val recentLutIds by vm.recentLutIds.collectAsState()
    val watermarks by vm.watermarks.collectAsState()
    var preview by remember { mutableStateOf<PhotoAsset?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewRotation by remember { mutableIntStateOf(0) }
    var previewLoading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(PhotoFilter.ALL) }
    val scope = rememberCoroutineScope()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedHandles by remember { mutableStateOf<Set<UInt>>(emptySet()) }
    var selectionMenu by remember { mutableStateOf(false) }
    var showBatchLutPicker by remember { mutableStateOf(false) }
    var batchLutEntry by remember { mutableStateOf<LutEntry?>(null) }
    var batchLut by remember { mutableStateOf<CubeLut?>(null) }
    var batchLutLoading by remember { mutableStateOf(false) }
    var showBatchConfirm by remember { mutableStateOf(false) }
    var showBatchWatermarkPicker by remember { mutableStateOf(false) }
    var batchWatermark by remember { mutableStateOf<WatermarkPreset?>(null) }
    var showBatchWatermarkConfirm by remember { mutableStateOf(false) }
    var batchLutWatermarkMode by remember { mutableStateOf(false) }
    var gradedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var grading by remember { mutableStateOf(false) }
    var selectedLutEntry by remember { mutableStateOf<LutEntry?>(null) }
    var selectedLut by remember { mutableStateOf<CubeLut?>(null) }
    var selectedWatermark by remember { mutableStateOf<WatermarkPreset?>(null) }
    var watermarkedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var photoMetadata by remember { mutableStateOf<PhotoMetadata?>(null) }
    var watermarking by remember { mutableStateOf(false) }
    var watermarkRetry by remember { mutableIntStateOf(0) }
    var previewLoadJob by remember { mutableStateOf<Job?>(null) }
    var showWatermarkEditor by remember { mutableStateOf(false) }
    var editingWatermark by remember { mutableStateOf<WatermarkPreset?>(null) }
    val context = LocalContext.current
    var inlineTask by remember { mutableStateOf<InlineTask?>(null) }
    var inlineRetry by remember { mutableStateOf<() -> Unit>({}) }
    var lutRetry by remember { mutableIntStateOf(0) }
    val visiblePhotos = photos.filter { it.matches(filter) }
    LaunchedEffect(previewBitmap, selectedLut, lutRetry) {
        val original = previewBitmap; val lut = selectedLut
        if (original == null || lut == null) {
            gradedBitmap = null; grading = false
            if (lut == null && inlineTask?.title?.startsWith("正在应用") == true) inlineTask = null
        } else {
            inlineTask = InlineTask("正在应用 LUT…", "${selectedLutEntry?.name ?: lut.name} · 预览", running = true)
            grading = true
            val result = runCatching { withContext(Dispatchers.Default) { CubeLuts.apply(original, lut) } }
            result.onSuccess { gradedBitmap = it; inlineTask = InlineTask("✓ 已应用 LUT", "${selectedLutEntry?.name ?: lut.name} · 预览", running = false) }
                .onFailure { gradedBitmap = null; inlineTask = InlineTask("应用 LUT 失败：${it.message ?: "无法处理图片"}", "", running = false, failed = true); inlineRetry = { lutRetry++ } }
            grading = false
        }
    }
    LaunchedEffect(gradedBitmap, previewBitmap, selectedWatermark, photoMetadata, watermarkRetry, previewRotation) {
        val base = gradedBitmap ?: previewBitmap
        val preset = selectedWatermark
        if (base == null || preset == null) {
            watermarkedBitmap = null
            watermarking = false
        } else {
            inlineTask = InlineTask("正在生成水印…", "${preset.name} · 预览", running = true)
            watermarking = true
            watermarkedBitmap = null
            val result = runCatching { withContext(Dispatchers.Default) {
                val selected = OrientedBitmaps.rotate(base, previewRotation)
                try {
                    WatermarkRenderer.render(selected, photoMetadata ?: PhotoMetadata(capturedAt = preview?.capturedAt), preset, context)
                } finally {
                    if (selected !== base && !selected.isRecycled) selected.recycle()
                }
            } }
            result.onSuccess { watermarkedBitmap = it; inlineTask = InlineTask("✓ 已生成水印", "${preset.name} · 预览", running = false) }
                .onFailure { watermarkedBitmap = null; inlineTask = InlineTask("生成水印失败：${it.message ?: "无法处理图片"}", "", running = false, failed = true); inlineRetry = { watermarkRetry++ } }
            watermarking = false
        }
    }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        val sourceSubtitle = if (photos.isNotEmpty()) {
            "${session?.name ?: "相机"} · ${session?.transport?.title ?: "Wi‑Fi"} · ${visiblePhotos.size} 个文件"
        } else "连接相机后开始"
        BridgePageLabel("\u76f8\u518c", sourceSubtitle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    TextButton({ selectionMode = false; selectedHandles = emptySet() }, enabled = !busy) { Text("\u53d6\u6d88", color = BridgeWhite) }
                    Surface(shape = RoundedCornerShape(12.dp), color = BridgeWhite.copy(alpha = .08f)) {
                        Text("${selectedHandles.size} \u5f20", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = BridgeWhite.copy(alpha = .78f), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box {
                        Button(
                            { selectionMenu = true },
                            enabled = !busy && selectedHandles.isNotEmpty(),
                            modifier = Modifier.height(38.dp),
                            shape = RoundedCornerShape(13.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight, disabledContainerColor = BridgeWhite.copy(alpha = .08f), disabledContentColor = BridgeWhite.copy(alpha = .3f)),
                        ) {
                            Icon(Icons.Default.Tune, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("操作", fontWeight = FontWeight.Bold)
                        }
                        BatchActionMenu(selectionMenu, { selectionMenu = false }, "已选择 ${selectedHandles.size} 张") {
                            BatchActionMenuItem(Icons.Default.Download, "下载原图", "保存相机中的原始文件") { vm.downloadAll(photos.filter { it.handle in selectedHandles }); selectedHandles = emptySet(); selectionMode = false; selectionMenu = false }
                            BatchActionMenuItem(Icons.Default.ColorLens, "套用 LUT", "批量生成调色副本") { batchLutWatermarkMode = false; showBatchLutPicker = true; selectionMenu = false }
                            BatchActionMenuItem(Icons.Default.TextFields, "添加水印", "应用已保存的水印预设") { showBatchWatermarkPicker = true; selectionMenu = false }
                            BatchActionMenuItem(Icons.Default.AutoAwesome, "LUT + 水印", "一次完成调色和水印") { batchLutWatermarkMode = true; showBatchLutPicker = true; selectionMenu = false }
                        }
                    }
                } else {
                    TextButton({ selectionMode = true }, enabled = !busy) { Text("\u591a\u9009", color = BridgeWhite) }
                    IconButton(vm::loadPhotos, enabled = !busy) { Icon(Icons.Default.Refresh, "\u5237\u65b0", tint = BridgeWhite) }
                }
            }
        }
        PageTaskBar(pageTask, vm::retryPageTask)
        FilterBar(filter) { filter = it }
        if (photos.isEmpty()) BridgeEmptyState(Icons.Default.PhotoLibrary, "\u6682\u65e0\u7167\u7247", "\u8fde\u63a5\u76f8\u673a\u5e76\u8bfb\u53d6\u76f8\u518c")
        else if (visiblePhotos.isEmpty()) BridgeEmptyState(Icons.Default.FilterAlt, "\u6b64\u5206\u7c7b\u6682\u65e0\u6587\u4ef6", "\u8bd5\u8bd5\u5176\u4ed6\u5206\u7c7b")
        else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(148.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 126.dp), verticalItemSpacing = 6.dp, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(visiblePhotos, key = { it.handle.toString() }) { asset ->
                PhotoCard(asset, vm.thumbnails[asset.handle], { vm.loadThumbnail(asset) }, asset.handle in selectedHandles) {
                    if (selectionMode) selectedHandles = if (asset.handle in selectedHandles) selectedHandles - asset.handle else selectedHandles + asset.handle
                    else { previewLoadJob?.cancel(); vm.loadThumbnail(asset); previewBitmap = null; previewRotation = 0; previewLoading = false; selectedLut = null; selectedLutEntry = null; selectedWatermark = null; watermarkedBitmap = null; photoMetadata = null; inlineTask = null; inlineRetry = {}; watermarkRetry = 0; preview = asset }
                }
            }
            if (hasMore && filter == PhotoFilter.ALL) item { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { LaunchedEffect(photos.size) { vm.loadMorePhotos() }; CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BridgeEmber); Spacer(Modifier.width(10.dp)); Text("正在加载更多", color = BridgeWhite.copy(alpha = .6f), style = MaterialTheme.typography.labelMedium) } }
            if (!hasMore && filter == PhotoFilter.ALL && photos.isNotEmpty()) item { Text("已全部加载", Modifier.fillMaxWidth().padding(18.dp), color = BridgeWhite.copy(alpha = .45f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
    preview?.let { asset ->
        val startLoadOriginal: () -> Unit = {
            val run = {
                inlineTask = InlineTask("正在加载原图…", asset.name, running = true)
                previewBitmap = null; previewLoading = true
                previewLoadJob?.cancel()
                previewLoadJob = scope.launch {
                    val loaded = vm.loadOriginalPhoto(asset)
                    if (preview?.handle == asset.handle) {
                        previewBitmap = loaded?.bitmap; photoMetadata = loaded?.metadata; previewLoading = false
                        inlineTask = if (loaded != null) InlineTask("✓ 已加载原图", asset.name, running = false) else InlineTask("加载原图失败：无法解码图片", asset.name, running = false, failed = true)
                    }
                }
                Unit
            }
            inlineRetry = run; run()
        }
        val startDownload: () -> Unit = {
            val run = {
                inlineTask = InlineTask("正在下载原图…", asset.name, running = true)
                vm.download(asset, inline = true) { result -> result.fold({ inlineTask = InlineTask("✓ 已下载原图", it.name, running = false) }, { inlineTask = InlineTask("下载失败：${it.message ?: "未知错误"}", "", running = false, failed = true) }) }
                Unit
            }
            inlineRetry = run; run()
        }
        val startExport: () -> Unit = {
            val run = {
                val suffix = buildList { selectedLut?.let { add(it.name) }; if (selectedWatermark != null) add("Watermark") }.joinToString("_").ifBlank { "Export" }
                inlineTask = InlineTask("正在导出…", "${selectedWatermark?.name ?: selectedLutEntry?.name ?: "当前预览"}", running = true)
                vm.exportEditedPhoto(asset, selectedLut, selectedWatermark, suffix, selectedWatermark?.quality ?: 95, rotation = previewRotation, inline = true) { result ->
                    result.fold({ inlineTask = InlineTask("✓ 已导出", it.name, running = false) }, { inlineTask = InlineTask("导出失败：${it.message ?: "未知错误"}", "", running = false, failed = true) })
                }
                Unit
            }
            inlineRetry = run; run()
        }
        PreviewDialog(asset.name, asset.size, vm.thumbnails[asset.handle], watermarkedBitmap ?: gradedBitmap ?: previewBitmap, previewLoading || grading || watermarking, { previewLoadJob?.cancel(); preview = null; previewBitmap = null; previewRotation = 0; gradedBitmap = null; watermarkedBitmap = null; photoMetadata = null; selectedLut = null; selectedLutEntry = null; selectedWatermark = null; inlineTask = null; inlineRetry = {} }, {
            startLoadOriginal()
        }, previewBitmap != null, luts, recentLutIds, { entry ->
            if (entry == null) { selectedLutEntry = null; selectedLut = null; inlineTask = null; inlineRetry = {} }
            else {
                vm.markLutUsed(entry)
                scope.launch { selectedLut = runCatching { vm.readLut(entry) }.getOrNull(); selectedLutEntry = if (selectedLut != null) entry else null }
            }
        }, selectedLutEntry?.name, download = startDownload, watermarks = watermarks, selectWatermark = { preset -> selectedWatermark = preset }, watermarkName = selectedWatermark?.name, exportEdited = startExport, createWatermark = { showWatermarkEditor = true; editingWatermark = null }, inlineTask = inlineTask, dismissInline = { inlineTask = null; inlineRetry = {} }, retryInline = inlineRetry, rotation = if (watermarkedBitmap != null) 0 else previewRotation, rotateLeft = { previewRotation = (previewRotation - 90 + 360) % 360 })
    }
    if (showWatermarkEditor) WatermarkEditorDialog(editingWatermark, { showWatermarkEditor = false }, { preset -> if (editingWatermark == null) vm.addWatermark(preset) else vm.updateWatermark(preset); showWatermarkEditor = false })
    LutPickerSheet(showBatchLutPicker, luts, recentLutIds, { showBatchLutPicker = false }, { entry ->
        if (entry != null) {
            showBatchLutPicker = false
            batchLutEntry = entry
            batchLut = null
            batchLutLoading = true
            showBatchConfirm = !batchLutWatermarkMode
            scope.launch {
                vm.markLutUsed(entry)
                val loaded = runCatching { vm.readLut(entry) }.getOrNull()
                if (batchLutEntry?.id == entry.id) {
                    batchLut = loaded
                    batchLutLoading = false
                    if (loaded == null) showBatchConfirm = false
                    else if (batchLutWatermarkMode) showBatchWatermarkPicker = true
                }
            }
        }
    })
    WatermarkPickerSheet(showBatchWatermarkPicker, watermarks, { showBatchWatermarkPicker = false }, { preset ->
        if (preset != null) { batchWatermark = preset; showBatchWatermarkPicker = false; showBatchWatermarkConfirm = true }
    }, { showBatchWatermarkPicker = false; showWatermarkEditor = true; editingWatermark = null })
    if (showBatchConfirm && batchLutEntry != null) {
        val selected = photos.filter { it.handle in selectedHandles }
        val rawCount = selected.count { it.type == "RAW" }
        val videoCount = selected.count { it.type in setOf("MOV", "MP4") }
        val suffix = buildString { if (rawCount > 0) append(" RAW 将导出为 JPG。"); if (videoCount > 0) append(" 视频将自动跳过。") }
        AlertDialog(onDismissRequest = { showBatchConfirm = false }, title = { Text("批量套用 LUT", color = BridgeWhite) }, text = { Column { Text("将为 ${selected.size} 张图片应用「${batchLutEntry!!.name}」并导出新图片。$suffix", color = BridgeWhite.copy(alpha = .8f)); if (batchLutLoading) { Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BridgeEmber); Spacer(Modifier.width(8.dp)); Text("正在读取 LUT…", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall) } } } }, confirmButton = { TextButton({ batchLut?.let { lut -> showBatchConfirm = false; batchLutLoading = false; vm.downloadAllWithLut(selected, lut) { selectionMode = false; selectedHandles = emptySet() } } }, enabled = !batchLutLoading && batchLut != null) { Text("开始导出", color = if (batchLutLoading) BridgeWhite.copy(alpha = .35f) else BridgeEmber) } }, dismissButton = { TextButton({ showBatchConfirm = false }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    if (showBatchWatermarkConfirm && batchWatermark != null) {
        val selected = photos.filter { it.handle in selectedHandles }
        val videoCount = selected.count { it.type in setOf("MOV", "MP4") }
        val suffix = if (videoCount > 0) "视频将自动跳过。" else ""
        val combined = batchLutWatermarkMode && batchLut != null
        AlertDialog(onDismissRequest = { showBatchWatermarkConfirm = false }, title = { Text(if (combined) "批量导出编辑图" else "批量添加水印", color = BridgeWhite) }, text = { Text(if (combined) "将为 ${selected.size} 张图片先应用「${batchLutEntry?.name}」，再添加「${batchWatermark!!.name}」水印并导出新文件。原图不会被覆盖。$suffix" else "将为 ${selected.size} 张图片添加「${batchWatermark!!.name}」水印并导出新文件。原图不会被覆盖。$suffix", color = BridgeWhite.copy(alpha = .8f)) }, confirmButton = { TextButton({ showBatchWatermarkConfirm = false; if (combined) vm.applyLutAndWatermarkToPhotos(selected, batchLut!!, batchWatermark!!) { selectionMode = false; selectedHandles = emptySet(); batchLutWatermarkMode = false } else vm.addWatermarkToPhotos(selected, batchWatermark!!) { selectionMode = false; selectedHandles = emptySet() } }) { Text("开始导出", color = BridgeEmber) } }, dismissButton = { TextButton({ showBatchWatermarkConfirm = false }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
}

@Composable
private fun AnimatedActivityPill(text: String?, busy: Boolean, retry: (() -> Unit)?, diagnostic: DiagnosticState?, exportDiagnostic: () -> Unit, copyDiagnostic: () -> Unit) {
    var shownText by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text != null) shownText = text
        else { delay(180); shownText = null }
    }
    AnimatedVisibility(visible = text != null, enter = fadeIn(tween(220)) + expandVertically(tween(220), expandFrom = Alignment.Top) + slideInVertically(tween(220)) { -it / 3 }, exit = fadeOut(tween(180)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + slideOutVertically(tween(180)) { -it / 3 }) {
        shownText?.let { ActivityPill(it, busy, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), retry, diagnostic, exportDiagnostic, copyDiagnostic) }
    }
}

@Composable
private fun PageTaskBar(task: PageTask?, retry: () -> Unit) {
    var shownTask by remember { mutableStateOf(task) }
    LaunchedEffect(task) { if (task != null) shownTask = task else { delay(180); shownTask = null } }
    AnimatedVisibility(visible = task != null, enter = fadeIn(tween(220)) + expandVertically(tween(220), expandFrom = Alignment.Top) + slideInVertically(tween(220)) { -it / 3 }, exit = fadeOut(tween(180)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + slideOutVertically(tween(180)) { -it / 3 }) {
        shownTask?.let { current ->
            val accent = if (current.failed) Color(0xFFE57373) else BridgeEmber
            Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = .13f), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .28f))) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (current.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent) else Icon(if (current.failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, tint = accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp)); Text(current.message, Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.bodySmall)
                    if (current.failed) TextButton(retry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("重试", color = accent) }
                }
            }
        }
    }
}

@Composable
private fun InlineStatusCard(task: InlineTask?, dismiss: () -> Unit, retry: () -> Unit) {
    var shownTask by remember { mutableStateOf(task) }
    LaunchedEffect(task) {
        if (task != null) {
            shownTask = task
            if (!task.running && !task.failed) { delay(3_000); dismiss() }
        } else { delay(180); shownTask = null }
    }
    AnimatedVisibility(visible = task != null, enter = fadeIn(tween(220)) + expandVertically(tween(220), expandFrom = Alignment.Bottom) + slideInVertically(tween(220)) { it / 2 }, exit = fadeOut(tween(180)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Bottom) + slideOutVertically(tween(180)) { it / 2 }) {
        shownTask?.let { current ->
            val accent = if (current.failed) Color(0xFFE57373) else BridgeEmber
            Surface(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = .13f), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .3f))) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (current.running) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = accent) else Icon(if (current.failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(current.title, color = BridgeWhite, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); if (current.detail.isNotBlank()) Text(current.detail, color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (current.failed) TextButton(retry, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("重试", color = accent) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LutPickerSheet(visible: Boolean, luts: List<LutEntry>, recentIds: List<String>, dismiss: () -> Unit, select: (LutEntry?) -> Unit) {
    if (!visible) return
    var category by remember { mutableStateOf<LutCategory?>(null) }
    var recentOnly by remember { mutableStateOf(false) }
    val visibleLuts = if (recentOnly) recentIds.mapNotNull { id -> luts.firstOrNull { it.id == id } } else luts.filter { category == null || it.category == category }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = BridgeSurface, contentColor = BridgeWhite) {
        Text("选择 LUT", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { LutCategoryChip("最近使用", recentOnly) { recentOnly = true; category = null } }
            item { LutCategoryChip("全部", !recentOnly && category == null) { recentOnly = false; category = null } }
            items(LutCategory.entries) { item -> LutCategoryChip(item.title, !recentOnly && category == item) { recentOnly = false; category = item } }
        }
        if (visibleLuts.isEmpty()) {
            Text(if (recentOnly) "暂无最近使用" else "请先在 LUT 页面导入预设", Modifier.fillMaxWidth().padding(28.dp), color = BridgeWhite.copy(alpha = .62f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleLuts, key = { it.id }) { entry ->
                Surface(Modifier.fillMaxWidth().clickable { select(entry) }, shape = RoundedCornerShape(16.dp), color = BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ColorLens, null, tint = BridgeEmber, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Column { Text(entry.name, color = BridgeWhite, fontWeight = FontWeight.SemiBold); Text(entry.category.title, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
        TextButton({ select(null) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) { Text("不使用 LUT", color = BridgeWhite.copy(alpha = .8f)) }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatermarkPickerSheet(visible: Boolean, presets: List<WatermarkPreset>, dismiss: () -> Unit, select: (WatermarkPreset?) -> Unit, create: () -> Unit = {}) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = BridgeSurface, contentColor = BridgeWhite) {
        Text("选择水印", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (presets.isEmpty()) Text("请先创建水印预设", Modifier.fillMaxWidth().padding(28.dp), color = BridgeWhite.copy(alpha = .62f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets, key = { it.id }) { preset ->
                Surface(Modifier.fillMaxWidth().clickable { select(preset) }, shape = RoundedCornerShape(16.dp), color = BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        WatermarkTemplatePreview(preset.layout, Modifier.size(width = 76.dp, height = 56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column { Text(preset.name, color = BridgeWhite, fontWeight = FontWeight.SemiBold); Text(preset.layout.uiTitle(), color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        TextButton({ create() }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) { Text("新建预设", color = BridgeEmber) }
        TextButton({ select(null) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) { Text("不使用水印", color = BridgeWhite.copy(alpha = .8f)) }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun WatermarkTemplatePreview(layout: WatermarkLayout, modifier: Modifier = Modifier.size(width = 76.dp, height = 56.dp)) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = Color(0xFF252125), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .14f))) {
        when (layout) {
            WatermarkLayout.MINIMAL -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF354431), Color(0xFF111517))))) {
                Box(Modifier.align(Alignment.Center).fillMaxWidth(.72f).fillMaxHeight(.68f).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Color(0xFF77866D), Color(0xFF263A34)))))
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(28.dp).height(2.dp).background(Color.White.copy(alpha = .92f), CircleShape))
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.width(36.dp).height(1.dp).background(Color.White.copy(alpha = .65f), CircleShape))
                }
            }
            WatermarkLayout.RIGHT_PARAMS, WatermarkLayout.LEFT_PARAMS -> Column(Modifier.fillMaxSize().background(Color.White)) {
                Box(Modifier.fillMaxWidth().weight(1f).background(Brush.linearGradient(listOf(Color(0xFF8796A0), Color(0xFF344A43)))))
                Row(Modifier.fillMaxWidth().height(16.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (layout == WatermarkLayout.LEFT_PARAMS) Box(Modifier.size(8.dp).background(Color(0xFF232323), RoundedCornerShape(2.dp)))
                    Box(Modifier.weight(1f).height(3.dp).background(Color(0xFF303030), CircleShape))
                    if (layout == WatermarkLayout.RIGHT_PARAMS) Box(Modifier.size(8.dp).background(Color(0xFF232323), RoundedCornerShape(2.dp)))
                    Box(Modifier.width(15.dp).height(3.dp).background(Color(0xFF5A5A5A), CircleShape))
                }
            }
            WatermarkLayout.WHITE_BORDER -> Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(.88f).aspectRatio(1.5f).background(Brush.linearGradient(listOf(Color(0xFF8796A0), Color(0xFF344A43)))))
            }
            WatermarkLayout.CUSTOM -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF5B4A43), Color(0xFF191515))))) {
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp).width(40.dp).height(5.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun PreviewDialog(name: String, size: Long, thumbnail: Bitmap?, bitmap: Bitmap?, loading: Boolean, dismiss: () -> Unit, loadOriginal: (() -> Unit)? = null, originalLoaded: Boolean = bitmap != null, luts: List<LutEntry> = emptyList(), recentLutIds: List<String> = emptyList(), selectLut: ((LutEntry?) -> Unit)? = null, lutName: String? = null, exportLut: (() -> Unit)? = null, download: (() -> Unit)? = null, watermarks: List<WatermarkPreset> = emptyList(), selectWatermark: ((WatermarkPreset?) -> Unit)? = null, watermarkName: String? = null, exportEdited: (() -> Unit)? = null, createWatermark: () -> Unit = {}, inlineTask: InlineTask? = null, dismissInline: () -> Unit = {}, retryInline: () -> Unit = {}, rotation: Int = 0, rotateLeft: () -> Unit = {}) {
    var lutMenu by remember(name) { mutableStateOf(false) }
    var watermarkMenu by remember(name) { mutableStateOf(false) }
    var editSheet by remember(name) { mutableStateOf(false) }
    var moreMenu by remember(name) { mutableStateOf(false) }
    val taskRunning = inlineTask?.running == true
    val exportAction = exportEdited ?: exportLut
    val format = name.substringAfterLast('.', "").uppercase().ifBlank { "\u56fe\u7247" }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxSize(),
            color = BridgeNight,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(BridgeWine.copy(alpha = .8f), BridgeNight, BridgeDeep))))
                    (bitmap ?: thumbnail)?.let { ZoomableImage(it, rotation) } ?: Text("\u6b63\u5728\u52a0\u8f7d\u7f29\u7565\u56fe", color = BridgeWhite.copy(alpha = .7f))
                    if (loading) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = BridgeEmber)
                        Spacer(Modifier.height(8.dp))
                        Text(if (!originalLoaded) "\u6b63\u5728\u52a0\u8f7d\u539f\u56fe\u2026" else "\u6b63\u5728\u5904\u7406\u9884\u89c8\u2026", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = BridgeNight.copy(alpha = .74f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, color = BridgeWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${size.prettySize()} · $format", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(rotateLeft, Modifier.size(40.dp)) { Icon(Icons.Default.RotateLeft, "\u9006\u65f6\u9488\u65cb\u8f6c", tint = BridgeWhite) }
                            if (download != null) Box {
                                IconButton({ moreMenu = true }, Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "\u66f4\u591a", tint = BridgeWhite) }
                                DropdownMenu(moreMenu, { moreMenu = false }) {
                                    DropdownMenuItem({ Text("\u4e0b\u8f7d\u539f\u59cb\u6587\u4ef6") }, { moreMenu = false; download() })
                                }
                            }
                            IconButton(dismiss, Modifier.size(40.dp)) { Icon(Icons.Default.Close, "\u5173\u95ed", tint = BridgeWhite) }
                        }
                    }
                    if (!editSheet) InlineStatusCard(inlineTask, dismissInline, retryInline)
                }

                if (lutName != null || watermarkName != null) Row(
                    Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 156.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lutName?.let { Surface(shape = RoundedCornerShape(10.dp), color = BridgeNight.copy(alpha = .68f)) { Text(it, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = BridgeWhite.copy(alpha = .9f), style = MaterialTheme.typography.labelSmall) } }
                    watermarkName?.let { Surface(shape = RoundedCornerShape(10.dp), color = BridgeNight.copy(alpha = .68f)) { Text(it, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = BridgeWhite.copy(alpha = .9f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }

                if (!editSheet) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        Surface(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 64.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = BridgeNight.copy(alpha = .76f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)),
                            shadowElevation = 8.dp,
                        ) {
                            if (!originalLoaded) {
                                Button(
                                    { loadOriginal?.invoke() },
                                    Modifier.fillMaxWidth().height(56.dp).padding(4.dp),
                                    enabled = loadOriginal != null && !taskRunning && !loading,
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber.copy(alpha = .9f), contentColor = BridgeNight),
                                ) { Text(if (loading) "\u6b63\u5728\u52a0\u8f7d\u2026" else "\u67e5\u770b\u539f\u56fe", fontWeight = FontWeight.Bold) }
                            } else {
                                Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        { editSheet = true },
                                        Modifier.weight(1f).height(56.dp),
                                        enabled = !taskRunning && !loading,
                                        shape = RoundedCornerShape(18.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .28f)),
                                    ) {
                                        Icon(Icons.Default.ColorLens, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("\u7f16\u8f91", fontWeight = FontWeight.SemiBold)
                                    }
                                    Button(
                                        { exportAction?.invoke() },
                                        Modifier.weight(1f).height(56.dp),
                                        enabled = exportAction != null && !taskRunning && !loading,
                                        shape = RoundedCornerShape(18.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber.copy(alpha = .9f), contentColor = BridgeNight),
                                    ) { Text("\u5bfc\u51fa", fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }

                if (editSheet) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)))
                    EditDrawer(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 64.dp),
                        lutName,
                        watermarkName,
                        { editSheet = false; lutMenu = true },
                        { editSheet = false; watermarkMenu = true },
                        { selectLut?.invoke(null); selectWatermark?.invoke(null); editSheet = false },
                        { editSheet = false },
                    )
                }
            }
        }
    }
    if (lutMenu && selectLut != null) LutPickerSheet(true, luts, recentLutIds, { lutMenu = false }, { entry -> lutMenu = false; selectLut(entry) })
    if (watermarkMenu && selectWatermark != null) WatermarkPickerSheet(true, watermarks, { watermarkMenu = false }, { preset -> watermarkMenu = false; selectWatermark(preset) }, { watermarkMenu = false; createWatermark() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyPreviewDialog(name: String, size: Long, thumbnail: Bitmap?, bitmap: Bitmap?, loading: Boolean, dismiss: () -> Unit, loadOriginal: (() -> Unit)? = null, originalLoaded: Boolean = bitmap != null, luts: List<LutEntry> = emptyList(), recentLutIds: List<String> = emptyList(), selectLut: ((LutEntry?) -> Unit)? = null, lutName: String? = null, exportLut: (() -> Unit)? = null, download: (() -> Unit)? = null, watermarks: List<WatermarkPreset> = emptyList(), selectWatermark: ((WatermarkPreset?) -> Unit)? = null, watermarkName: String? = null, exportEdited: (() -> Unit)? = null, createWatermark: () -> Unit = {}, inlineTask: InlineTask? = null, dismissInline: () -> Unit = {}, retryInline: () -> Unit = {}) {
    var rotation by remember(name) { mutableIntStateOf(0) }
    var lutMenu by remember(name) { mutableStateOf(false) }
    var watermarkMenu by remember(name) { mutableStateOf(false) }
    var editSheet by remember(name) { mutableStateOf(false) }
    var moreMenu by remember(name) { mutableStateOf(false) }
    val taskRunning = inlineTask?.running == true
    val exportAction = exportEdited ?: exportLut
    val format = name.substringAfterLast('.', "").uppercase().ifBlank { "图片" }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(8.dp), shape = RoundedCornerShape(28.dp), color = BridgeSurface, border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = BridgeWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${size.prettySize()} · $format", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ rotation = (rotation - 90 + 360) % 360 }) { Icon(Icons.Default.RotateLeft, "逆时针旋转 90 度", tint = BridgeWhite) }
                    if (download != null) Box {
                        IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, "更多", tint = BridgeWhite) }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            DropdownMenuItem({ Text("下载原始文件") }, { moreMenu = false; download() })
                        }
                    }
                    IconButton(dismiss) { Icon(Icons.Default.Close, "关闭", tint = BridgeWhite) }
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp, vertical = 4.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(BridgeWine.copy(alpha = .8f), BridgeNight, BridgeDeep))))
                    (bitmap ?: thumbnail)?.let { ZoomableImage(it, rotation) } ?: Text("正在加载缩略图", color = BridgeWhite.copy(alpha = .7f))
                    if (loading) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = BridgeEmber)
                        Spacer(Modifier.height(8.dp))
                        Text(if (!originalLoaded) "正在加载原图…" else "正在处理预览…", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                    }
                    if (lutName != null || watermarkName != null) Row(Modifier.align(Alignment.BottomStart).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        lutName?.let { Surface(shape = RoundedCornerShape(10.dp), color = BridgeNight.copy(alpha = .72f)) { Text(it, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = BridgeWhite, style = MaterialTheme.typography.labelSmall) } }
                        watermarkName?.let { Surface(shape = RoundedCornerShape(10.dp), color = BridgeNight.copy(alpha = .72f)) { Text(it, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = BridgeWhite, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    }
                }
                InlineStatusCard(inlineTask, dismissInline, retryInline)
                if (!originalLoaded) {
                    Button({ loadOriginal?.invoke() }, Modifier.fillMaxWidth().height(56.dp).padding(top = 10.dp), enabled = loadOriginal != null && !taskRunning && !loading, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) {
                        Text(if (loading) "正在加载…" else "查看原图", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({ editSheet = true }, Modifier.weight(1f).height(56.dp), enabled = !taskRunning && !loading, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .28f))) {
                            Icon(Icons.Default.ColorLens, null); Spacer(Modifier.width(7.dp)); Text("编辑", fontWeight = FontWeight.SemiBold)
                        }
                        Button({ exportAction?.invoke() }, Modifier.weight(1f).height(56.dp), enabled = exportAction != null && !taskRunning && !loading, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) {
                            Text("导出", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                }
                if (editSheet) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)))
                    EditDrawer(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp),
                        lutName,
                        watermarkName,
                        { editSheet = false; lutMenu = true },
                        { editSheet = false; watermarkMenu = true },
                        { selectLut?.invoke(null); selectWatermark?.invoke(null); editSheet = false },
                        { editSheet = false },
                    )
                }
            }
        }
    }
    if (lutMenu && selectLut != null) LutPickerSheet(true, luts, recentLutIds, { lutMenu = false }, { entry -> lutMenu = false; selectLut(entry) })
    if (watermarkMenu && selectWatermark != null) WatermarkPickerSheet(true, watermarks, { watermarkMenu = false }, { preset -> watermarkMenu = false; selectWatermark(preset) }, { watermarkMenu = false; createWatermark() })
}

@Composable
private fun EditDrawer(
    modifier: Modifier,
    lutName: String?,
    watermarkName: String?,
    openLut: () -> Unit,
    openWatermark: () -> Unit,
    restore: () -> Unit,
    dismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it },
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .imePadding(),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = BridgeSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "编辑照片",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = BridgeWhite,
                    )
                    TextButton(dismiss) { Text("完成", color = BridgeEmber) }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        openLut,
                        Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .22f)),
                    ) {
                        Icon(Icons.Default.ColorLens, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("LUT", maxLines = 1)
                    }
                    OutlinedButton(
                        openWatermark,
                        Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .22f)),
                    ) {
                        Icon(Icons.Default.TextFields, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("水印", maxLines = 1)
                    }
                    OutlinedButton(
                        restore,
                        Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .22f)),
                    ) {
                        Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("还原", maxLines = 1)
                    }
                }
                Text("当前效果", color = BridgeWhite.copy(alpha = .58f), style = MaterialTheme.typography.labelMedium)
                Text(
                    listOfNotNull(lutName, watermarkName).joinToString(" · ").ifBlank { "未选择编辑效果" },
                    Modifier.padding(top = 4.dp, bottom = 4.dp),
                    color = BridgeWhite,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap, rotation: Int) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val sourceWidth = if (rotation % 180 == 0) bitmap.width else bitmap.height
    val sourceHeight = if (rotation % 180 == 0) bitmap.height else bitmap.width
    val fitScale = if (viewport == IntSize.Zero) 1f else minOf(viewport.width.toFloat() / sourceWidth, viewport.height.toFloat() / sourceHeight)
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 12f)
        val maxX = ((sourceWidth * fitScale * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((sourceHeight * fitScale * scale - viewport.height) / 2f).coerceAtLeast(0f)
        offset = if (scale == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
    }
    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().onSizeChanged { viewport = it }.transformable(transform).graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation.toFloat(); translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
}

@Composable
private fun PhotoCard(asset: PhotoAsset, bitmap: Bitmap?, load: () -> Unit, selected: Boolean, click: () -> Unit) {
    LaunchedEffect(asset.handle) { load() }
    val ratio = when (asset.handle.toInt().and(3)) { 0 -> .72f; 1 -> 1.16f; 2 -> .86f; else -> .98f }
    Card(Modifier.fillMaxWidth().clickable { click() }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, BridgeEmber) else null) {
        Box(Modifier.aspectRatio(ratio)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(BridgeWine, BridgeNight)), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Image, null, tint = BridgeEmber, modifier = Modifier.size(32.dp)) }
            if (selected) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = CircleShape, color = BridgeEmber) { Icon(Icons.Default.Check, "已选择", tint = BridgeNight, modifier = Modifier.padding(6.dp).size(17.dp)) }
        }
    }
}

@Composable
private fun BatchActionMenu(
    expanded: Boolean,
    dismiss: () -> Unit,
    selectionText: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = dismiss,
        modifier = Modifier.widthIn(min = 252.dp, max = 280.dp),
        shape = RoundedCornerShape(22.dp),
        containerColor = BridgeElevated,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("批量操作", color = BridgeWhite, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(selectionText, color = BridgeEmber, style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider(color = BridgeWhite.copy(alpha = .08f))
        content()
    }
}

@Composable
private fun BatchActionMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    click: () -> Unit,
) {
    val accent = if (danger) Color(0xFFFF7777) else BridgeEmber
    DropdownMenuItem(
        text = {
            Column {
                Text(title, color = if (danger) accent else BridgeWhite, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = BridgeWhite.copy(alpha = .48f), style = MaterialTheme.typography.labelSmall)
            }
        },
        onClick = click,
        leadingIcon = {
            Surface(Modifier.size(34.dp), shape = CircleShape, color = accent.copy(alpha = .14f)) {
                Icon(icon, null, Modifier.padding(8.dp), tint = accent)
            }
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp),
    )
}

@Composable
private fun DownloadsScreen(vm: MainViewModel) {
    val rows by vm.downloads.collectAsState()
    val luts by vm.luts.collectAsState()
    val recentLutIds by vm.recentLutIds.collectAsState()
    val watermarks by vm.watermarks.collectAsState()
    val pageTask by vm.pageTask.collectAsState()
    var preview by remember { mutableStateOf<DownloadRecord?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewRotation by remember { mutableIntStateOf(0) }
    var previewLoading by remember { mutableStateOf(false) }
    var gradedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var grading by remember { mutableStateOf(false) }
    var previewLut by remember { mutableStateOf<LutEntry?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMenu by remember { mutableStateOf(false) }
    var showBatchLutPicker by remember { mutableStateOf(false) }
    var batchLutEntry by remember { mutableStateOf<LutEntry?>(null) }
    var batchLut by remember { mutableStateOf<CubeLut?>(null) }
    var batchLutLoading by remember { mutableStateOf(false) }
    var showBatchConfirm by remember { mutableStateOf(false) }
    var showBatchWatermarkPicker by remember { mutableStateOf(false) }
    var batchWatermark by remember { mutableStateOf<WatermarkPreset?>(null) }
    var showBatchWatermarkConfirm by remember { mutableStateOf(false) }
    var batchLutWatermarkMode by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(PhotoFilter.ALL) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inlineTask by remember { mutableStateOf<InlineTask?>(null) }
    var inlineRetry by remember { mutableStateOf<() -> Unit>({}) }
    var lutRetry by remember { mutableIntStateOf(0) }
    var originalReload by remember { mutableIntStateOf(0) }
    var selectedWatermark by remember { mutableStateOf<WatermarkPreset?>(null) }
    var watermarkedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var photoMetadata by remember { mutableStateOf<PhotoMetadata?>(null) }
    var watermarking by remember { mutableStateOf(false) }
    var watermarkRetry by remember { mutableIntStateOf(0) }
    var showWatermarkEditor by remember { mutableStateOf(false) }
    var editingWatermark by remember { mutableStateOf<WatermarkPreset?>(null) }
    val visibleRows = rows.filter { it.matches(filter) }
    LaunchedEffect(preview?.uri, originalReload) {
        val uri = preview?.uri ?: return@LaunchedEffect
        val name = preview?.name ?: "图片"
        inlineTask = InlineTask("正在加载原图…", name, running = true)
        previewBitmap = null; previewLoading = true
        val loaded = preview?.let { vm.loadLocalPhoto(it) }
        previewBitmap = loaded?.bitmap; photoMetadata = loaded?.metadata; previewLoading = false
        if (loaded != null) inlineTask = InlineTask("✓ 已加载原图", name, running = false)
        else { inlineTask = InlineTask("加载原图失败：无法读取图片", name, running = false, failed = true); inlineRetry = { originalReload++ } }
    }
    LaunchedEffect(previewBitmap, previewLut, lutRetry) {
        val original = previewBitmap; val entry = previewLut
        if (original == null || entry == null) { gradedBitmap = null; grading = false; if (entry == null && inlineTask?.title?.startsWith("正在应用") == true) inlineTask = null }
        else {
            inlineTask = InlineTask("正在应用 LUT…", "${entry.name} · 预览", running = true)
            grading = true
            val result = runCatching { withContext(Dispatchers.Default) { val source = context.filesDir.resolve("luts").resolve(entry.id).readText(); CubeLuts.apply(original, CubeLuts.parse(entry.name, source)) } }
            result.onSuccess { gradedBitmap = it; inlineTask = InlineTask("✓ 已应用 LUT", "${entry.name} · 预览", running = false) }
                .onFailure { gradedBitmap = null; inlineTask = InlineTask("应用 LUT 失败：${it.message ?: "无法处理图片"}", "", running = false, failed = true); inlineRetry = { lutRetry++ } }
            grading = false
        }
    }
    LaunchedEffect(gradedBitmap, previewBitmap, selectedWatermark, photoMetadata, watermarkRetry, previewRotation) {
        val base = gradedBitmap ?: previewBitmap
        val preset = selectedWatermark
        if (base == null || preset == null) {
            watermarkedBitmap = null
            watermarking = false
        } else {
            inlineTask = InlineTask("正在生成水印…", "${preset.name} · 预览", running = true)
            watermarking = true
            watermarkedBitmap = null
            val result = runCatching { withContext(Dispatchers.Default) {
                val selected = OrientedBitmaps.rotate(base, previewRotation)
                try {
                    WatermarkRenderer.render(selected, photoMetadata ?: PhotoMetadata(), preset, context)
                } finally {
                    if (selected !== base && !selected.isRecycled) selected.recycle()
                }
            } }
            result.onSuccess { watermarkedBitmap = it; inlineTask = InlineTask("✓ 已生成水印", "${preset.name} · 预览", running = false) }
                .onFailure { watermarkedBitmap = null; inlineTask = InlineTask("生成水印失败：${it.message ?: "无法处理图片"}", "", running = false, failed = true); inlineRetry = { watermarkRetry++ } }
            watermarking = false
        }
    }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("\u4e0b\u8f7d", "\u5df2\u4fdd\u5b58 ${rows.size} \u4e2a\u6587\u4ef6") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    TextButton({ selectionMode = false; selectedUris = emptySet() }) { Text("取消", color = BridgeWhite) }
                    Surface(shape = RoundedCornerShape(12.dp), color = BridgeWhite.copy(alpha = .08f)) {
                        Text("${selectedUris.size} 个", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = BridgeWhite.copy(alpha = .78f), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box {
                        Button(
                            { selectionMenu = true },
                            enabled = selectedUris.isNotEmpty(),
                            modifier = Modifier.height(38.dp),
                            shape = RoundedCornerShape(13.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight, disabledContainerColor = BridgeWhite.copy(alpha = .08f), disabledContentColor = BridgeWhite.copy(alpha = .3f)),
                        ) {
                            Icon(Icons.Default.Tune, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("操作", fontWeight = FontWeight.Bold)
                        }
                        BatchActionMenu(selectionMenu, { selectionMenu = false }, "已选择 ${selectedUris.size} 个文件") {
                            BatchActionMenuItem(Icons.Default.ColorLens, "套用 LUT", "批量生成调色副本") { batchLutWatermarkMode = false; showBatchLutPicker = true; selectionMenu = false }
                            BatchActionMenuItem(Icons.Default.TextFields, "添加水印", "应用已保存的水印预设") { showBatchWatermarkPicker = true; selectionMenu = false }
                            BatchActionMenuItem(Icons.Default.AutoAwesome, "LUT + 水印", "一次完成调色和水印") { batchLutWatermarkMode = true; showBatchLutPicker = true; selectionMenu = false }
                            BatchActionMenuItem(Icons.Default.Share, "分享", "发送选中的文件") { shareUris(context, rows.filter { it.uri in selectedUris }.map { it.uri }); selectedUris = emptySet(); selectionMode = false; selectionMenu = false }
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = BridgeWhite.copy(alpha = .08f))
                            BatchActionMenuItem(Icons.Default.Delete, "删除", "从下载记录和相册中移除", danger = true) { showDeleteConfirm = true; selectionMenu = false }
                        }
                    }
                } else TextButton({ selectionMode = true }) { Text("多选", color = BridgeWhite) }
            }
        }
        PageTaskBar(pageTask, vm::retryPageTask)
        FilterBar(filter) { filter = it; selectedUris = emptySet() }
        if (rows.isEmpty()) BridgeEmptyState(Icons.Default.CloudDownload, "\u6682\u65e0\u4e0b\u8f7d\u8bb0\u5f55", "\u5728\u76f8\u518c\u4e2d\u9009\u62e9\u7167\u7247\u5373\u53ef\u4fdd\u5b58\u5230\u624b\u673a")
        else if (visibleRows.isEmpty()) BridgeEmptyState(Icons.Default.FilterAlt, "\u6b64\u5206\u7c7b\u6682\u65e0\u6587\u4ef6", "\u8bd5\u8bd5\u5176\u4ed6\u5206\u7c7b")
        else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(148.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 126.dp), verticalItemSpacing = 10.dp, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visibleRows, key = { it.uri }) { row ->
                DownloadCard(row, row.uri in selectedUris) {
                    if (selectionMode) selectedUris = if (row.uri in selectedUris) selectedUris - row.uri else selectedUris + row.uri
                    else { previewRotation = 0; previewLut = null; gradedBitmap = null; selectedWatermark = null; watermarkedBitmap = null; photoMetadata = null; inlineTask = null; inlineRetry = {}; watermarkRetry = 0; preview = row }
                }
            }
        }
    }
    preview?.let { row ->
        val startExport: () -> Unit = {
            val run = {
                val suffix = buildList { previewLut?.let { add(it.name) }; if (selectedWatermark != null) add("Watermark") }.joinToString("_").ifBlank { "Export" }
                inlineTask = InlineTask("正在导出…", "${selectedWatermark?.name ?: previewLut?.name ?: "当前预览"}", running = true)
                vm.exportEditedDownload(row, previewLut, selectedWatermark, suffix, selectedWatermark?.quality ?: 95, rotation = previewRotation, inline = true) { result ->
                    result.fold({ inlineTask = InlineTask("✓ 已导出", it.name, running = false) }, { inlineTask = InlineTask("导出失败：${it.message ?: "未知错误"}", "", running = false, failed = true) })
                }
                Unit
            }
            inlineRetry = run
            run()
        }
        PreviewDialog(row.name, row.size, null, watermarkedBitmap ?: gradedBitmap ?: previewBitmap, previewLoading || grading || watermarking, { preview = null; previewBitmap = null; previewRotation = 0; previewLut = null; gradedBitmap = null; selectedWatermark = null; watermarkedBitmap = null; photoMetadata = null; inlineTask = null; inlineRetry = {} }, { originalReload++ }, previewBitmap != null, luts, recentLutIds, { entry -> previewLut = entry; entry?.let(vm::markLutUsed) }, previewLut?.name, download = null, watermarks = watermarks, selectWatermark = { preset -> selectedWatermark = preset }, watermarkName = selectedWatermark?.name, exportEdited = startExport, createWatermark = { showWatermarkEditor = true; editingWatermark = null }, inlineTask = inlineTask, dismissInline = { inlineTask = null; inlineRetry = {} }, retryInline = inlineRetry, rotation = if (watermarkedBitmap != null) 0 else previewRotation, rotateLeft = { previewRotation = (previewRotation - 90 + 360) % 360 })
    }
    if (showWatermarkEditor) WatermarkEditorDialog(editingWatermark, { showWatermarkEditor = false }, { preset -> if (editingWatermark == null) vm.addWatermark(preset) else vm.updateWatermark(preset); showWatermarkEditor = false })
    LutPickerSheet(showBatchLutPicker, luts, recentLutIds, { showBatchLutPicker = false }, { entry ->
        if (entry != null) {
            showBatchLutPicker = false
            batchLutEntry = entry
            batchLut = null
            batchLutLoading = true
            showBatchConfirm = !batchLutWatermarkMode
            scope.launch {
                vm.markLutUsed(entry)
                val loaded = runCatching { vm.readLut(entry) }.getOrNull()
                if (batchLutEntry?.id == entry.id) {
                    batchLut = loaded
                    batchLutLoading = false
                    if (loaded == null) showBatchConfirm = false
                    else if (batchLutWatermarkMode) showBatchWatermarkPicker = true
                }
            }
        }
    })
    WatermarkPickerSheet(showBatchWatermarkPicker, watermarks, { showBatchWatermarkPicker = false }, { preset ->
        if (preset != null) { batchWatermark = preset; showBatchWatermarkPicker = false; showBatchWatermarkConfirm = true }
    }, { showBatchWatermarkPicker = false; showWatermarkEditor = true; editingWatermark = null })
    if (showBatchConfirm && batchLutEntry != null) {
        val selected = rows.filter { it.uri in selectedUris }
        val rawCount = selected.count { it.name.substringAfterLast('.', "").lowercase() in setOf("nef", "nrw", "cr2", "arw", "dng", "raf") }
        val videoCount = selected.count { !it.name.supportsLutInput() }
        val suffix = buildString { if (rawCount > 0) append(" RAW 将导出为 JPG。"); if (videoCount > 0) append(" 视频将自动跳过。") }
        AlertDialog(onDismissRequest = { showBatchConfirm = false }, title = { Text("批量套用 LUT", color = BridgeWhite) }, text = { Column { Text("将为 ${selected.size} 个文件应用「${batchLutEntry!!.name}」并导出为新图片。$suffix", color = BridgeWhite.copy(alpha = .8f)); if (batchLutLoading) { Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BridgeEmber); Spacer(Modifier.width(8.dp)); Text("正在读取 LUT…", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall) } } } }, confirmButton = { TextButton({ batchLut?.let { lut -> showBatchConfirm = false; batchLutLoading = false; vm.applyLutToDownloads(selected, lut) { selectionMode = false; selectedUris = emptySet() } } }, enabled = !batchLutLoading && batchLut != null) { Text("开始导出", color = if (batchLutLoading) BridgeWhite.copy(alpha = .35f) else BridgeEmber) } }, dismissButton = { TextButton({ showBatchConfirm = false }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    if (showBatchWatermarkConfirm && batchWatermark != null) {
        val selected = rows.filter { it.uri in selectedUris }
        val videoCount = selected.count { !it.name.supportsLutInput() }
        val suffix = if (videoCount > 0) "视频将自动跳过。" else ""
        val combined = batchLutWatermarkMode && batchLut != null
        AlertDialog(onDismissRequest = { showBatchWatermarkConfirm = false }, title = { Text(if (combined) "批量导出编辑图" else "批量添加水印", color = BridgeWhite) }, text = { Text(if (combined) "将为 ${selected.size} 个文件先应用「${batchLutEntry?.name}」，再添加「${batchWatermark!!.name}」水印并导出新文件。原图不会被覆盖。$suffix" else "将为 ${selected.size} 个文件添加「${batchWatermark!!.name}」水印并导出新文件。原图不会被覆盖。$suffix", color = BridgeWhite.copy(alpha = .8f)) }, confirmButton = { TextButton({ showBatchWatermarkConfirm = false; if (combined) vm.applyLutAndWatermarkToDownloads(selected, batchLut!!, batchWatermark!!) { selectionMode = false; selectedUris = emptySet(); batchLutWatermarkMode = false } else vm.addWatermarkToDownloads(selected, batchWatermark!!) { selectionMode = false; selectedUris = emptySet() } }) { Text("开始导出", color = BridgeEmber) } }, dismissButton = { TextButton({ showBatchWatermarkConfirm = false }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("\u5220\u9664\u6587\u4ef6", color = BridgeWhite) },
            text = { Text("\u786e\u8ba4\u5220\u9664\u9009\u4e2d\u7684 ${selectedUris.size} \u4e2a\u6587\u4ef6\uff1f\u5220\u9664\u540e\u65e0\u6cd5\u6062\u590d\u3002", color = BridgeWhite.copy(alpha = .8f)) },
            confirmButton = { TextButton({ vm.deleteDownloads(selectedUris); selectedUris = emptySet(); selectionMode = false; showDeleteConfirm = false }) { Text("\u5220\u9664", color = Color(0xFFE57373)) } },
            dismissButton = { TextButton({ showDeleteConfirm = false }) { Text("\u53d6\u6d88", color = BridgeWhite.copy(alpha = .6f)) } },
            containerColor = BridgeSurface
        )
    }
}

private fun shareUris(context: android.content.Context, uris: List<String>) {
    if (uris.isEmpty()) return
    val parsed = uris.map { Uri.parse(it) }
    val intent = if (parsed.size == 1) android.content.Intent(android.content.Intent.ACTION_SEND).apply { putExtra(android.content.Intent.EXTRA_STREAM, parsed.first()); type = "image/*" }
    else android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(parsed)); type = "image/*" }
    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(intent, "\u5206\u4eab\u5230"))
}

private fun shareDiagnostic(context: android.content.Context, uri: Uri) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "分享诊断包"))
}

private fun copyDiagnostic(context: android.content.Context, text: String) {
    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Camera Bridge 诊断", text))
    android.widget.Toast.makeText(context, "已复制诊断信息", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun LutScreen(vm: MainViewModel, openWatermark: () -> Unit) {
    val luts by vm.luts.collectAsState()
    var category by remember { mutableStateOf<LutCategory?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LutEntry?>(null) }
    var editName by remember { mutableStateOf("") }
    var categoryEditor by remember { mutableStateOf<LutEntry?>(null) }
    var deleteConfirm by remember { mutableStateOf<LutEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLut) }
    val visible = luts.filter { category == null || it.category == category }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("LUT", "已导入 ${luts.size} 个预设") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(openWatermark) { Text("水印预设", color = BridgeWhite) }
                TextButton({ picker.launch(arrayOf("*/*")) }) { Text("导入", color = BridgeEmber) }
            }
        }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { LutCategoryChip("\u5168\u90e8", category == null) { category = null } }
            items(LutCategory.entries) { item -> LutCategoryChip(item.title, category == item) { category = item } }
        }
        if (visible.isEmpty()) BridgeEmptyState(Icons.Default.ColorLens, "\u6682\u65e0 LUT", "\u5bfc\u5165 .cube \u6587\u4ef6\u540e\u53ef\u5728\u8be6\u60c5\u9875\u9009\u62e9")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visible, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(42.dp), shape = CircleShape, color = BridgeEmber.copy(alpha = .15f)) { Icon(Icons.Default.ColorLens, null, Modifier.padding(11.dp), tint = BridgeEmber) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(entry.name, color = BridgeWhite, fontWeight = FontWeight.SemiBold); Text(entry.category.title, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) }
                        Box {
                            IconButton({ menuFor = entry.id }) { Icon(Icons.Default.MoreVert, "\u7ba1\u7406", tint = BridgeWhite) }
                            DropdownMenu(menuFor == entry.id, { menuFor = null }) {
                                DropdownMenuItem({ Text("\u91cd\u547d\u540d") }, { editName = entry.name; editing = entry; menuFor = null })
                                DropdownMenuItem({ Text("修改分类") }, { categoryEditor = entry; menuFor = null })
                                DropdownMenuItem({ Text("删除", color = Color(0xFFE57373)) }, { deleteConfirm = entry; menuFor = null })
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { entry ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("重命名 LUT", color = BridgeWhite) }, text = { OutlinedTextField(editName, { editName = it }, singleLine = true, colors = bridgeFieldColors()) }, confirmButton = { TextButton({ vm.updateLut(entry, name = editName); editing = null }) { Text("保存", color = BridgeEmber) } }, dismissButton = { TextButton({ editing = null }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    categoryEditor?.let { entry ->
        AlertDialog(onDismissRequest = { categoryEditor = null }, title = { Text("修改分类", color = BridgeWhite) }, text = {
            Column { LutCategory.entries.forEach { type -> Row(Modifier.fillMaxWidth().clickable { vm.updateLut(entry, category = type); categoryEditor = null }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(type == entry.category, { vm.updateLut(entry, category = type); categoryEditor = null }, colors = RadioButtonDefaults.colors(selectedColor = BridgeEmber)); Text(type.title, color = BridgeWhite) } } }
        }, confirmButton = {}, containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    deleteConfirm?.let { entry ->
        AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("删除 LUT", color = BridgeWhite) }, text = { Text("删除后不会影响已经导出的图片。", color = BridgeWhite.copy(alpha = .8f)) }, confirmButton = { TextButton({ vm.removeLut(entry); deleteConfirm = null }) { Text("删除", color = Color(0xFFE57373)) } }, dismissButton = { TextButton({ deleteConfirm = null }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = BridgeSurface, titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
}

@Composable private fun LutCategoryChip(title: String, selected: Boolean, click: () -> Unit) { Surface(Modifier.clickable { click() }, shape = RoundedCornerShape(18.dp), color = if (selected) BridgeEmber else BridgeWhite.copy(alpha = .08f)) { Text(title, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (selected) BridgeNight else BridgeWhite, style = MaterialTheme.typography.labelMedium) } }

@Composable
private fun WatermarkScreen(vm: MainViewModel, back: () -> Unit) {
    val presets by vm.watermarks.collectAsState()
    var editorVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WatermarkPreset?>(null) }
    var deleteConfirm by remember { mutableStateOf<WatermarkPreset?>(null) }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("水印", "${presets.size} 个预设") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(back) { Text("返回", color = BridgeWhite) }
                TextButton({ editing = null; editorVisible = true }) { Text("新建", color = BridgeEmber) }
            }
        }
        if (presets.isEmpty()) {
            BridgeEmptyState(Icons.Default.TextFields, "暂无水印预设", "创建固定布局预设后可在图片详情或批量操作中使用")
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(presets, key = { it.id }) { preset ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        WatermarkTemplatePreview(preset.layout, Modifier.size(width = 72.dp, height = 54.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, color = BridgeWhite, fontWeight = FontWeight.SemiBold)
                        }
                        Row {
                            IconButton({ editing = preset; editorVisible = true }) { Icon(Icons.Default.Edit, "编辑", tint = BridgeWhite) }
                            IconButton({ deleteConfirm = preset }) { Icon(Icons.Default.DeleteOutline, "删除", tint = Color(0xFFE57373)) }
                        }
                    }
                }
            }
        }
    }
    if (editorVisible) {
        WatermarkEditorDialog(editing, { editorVisible = false }, { saved -> if (editing == null) vm.addWatermark(saved) else vm.updateWatermark(saved); editorVisible = false })
    }
    deleteConfirm?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("删除水印预设", color = BridgeWhite) },
            text = { Text("删除后不会影响已经导出的图片。", color = BridgeWhite.copy(alpha = .8f)) },
            confirmButton = { TextButton({ vm.removeWatermark(preset); deleteConfirm = null }) { Text("删除", color = Color(0xFFE57373)) } },
            dismissButton = { TextButton({ deleteConfirm = null }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } },
            containerColor = BridgeSurface,
            titleContentColor = BridgeWhite,
            textContentColor = BridgeWhite,
        )
    }
}

@Composable
private fun WatermarkEditorDialog(existing: WatermarkPreset?, dismiss: () -> Unit, save: (WatermarkPreset) -> Unit) {
    val context = LocalContext.current
    var draft by remember(existing?.id) { mutableStateOf(existing ?: WatermarkPreset(java.util.UUID.randomUUID().toString(), "我的水印", logoEnabled = false, useBrandLogo = false)) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            draft = draft.copy(logoUri = it.toString())
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f), shape = RoundedCornerShape(28.dp), color = BridgeSurface, border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (existing == null) "新建水印预设" else "编辑水印预设", Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(dismiss) { Icon(Icons.Default.Close, "关闭", tint = BridgeWhite) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("预设名称") }, singleLine = true, colors = bridgeFieldColors()) }
                    item { WatermarkTemplatePreview(draft.layout, Modifier.fillMaxWidth().height(150.dp)) }
                    item { Text("布局", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.labelLarge) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(WatermarkLayout.MINIMAL, WatermarkLayout.RIGHT_PARAMS, WatermarkLayout.LEFT_PARAMS, WatermarkLayout.WHITE_BORDER)) { layout ->
                                LutCategoryChip(layout.uiTitle(), draft.layout == layout) {
                                    draft = draft.copy(
                                        layout = layout,
                                        textColor = if (layout == WatermarkLayout.MINIMAL) android.graphics.Color.WHITE else android.graphics.Color.BLACK,
                                        logoPosition = if (layout == WatermarkLayout.LEFT_PARAMS) WatermarkLogoPosition.TOP_LEFT else WatermarkLogoPosition.TOP_RIGHT,
                                    )
                                }
                            }
                        }
                    }
                    item { Text("显示字段", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.labelLarge) }
                    items(WatermarkField.entries) { field ->
                        Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(fields = if (field in draft.fields) draft.fields - field else draft.fields + field) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(field in draft.fields, { checked -> draft = draft.copy(fields = if (checked) draft.fields + field else draft.fields - field) }, colors = CheckboxDefaults.colors(checkedColor = BridgeEmber, checkmarkColor = BridgeNight))
                            Text(field.title, color = BridgeWhite)
                        }
                    }
                    item { OutlinedTextField(draft.customText, { draft = draft.copy(customText = it) }, Modifier.fillMaxWidth(), label = { Text("自定义文字") }, singleLine = true, colors = bridgeFieldColors()) }
                    item { OutlinedTextField(draft.copyrightText, { draft = draft.copy(copyrightText = it) }, Modifier.fillMaxWidth(), label = { Text("版权文字") }, singleLine = true, colors = bridgeFieldColors()) }
                    item {
                        Text("字号 ${draft.fontSize}", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        Slider(draft.fontSize.toFloat(), { draft = draft.copy(fontSize = it.roundToInt()) }, valueRange = 18f..64f, colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber))
                    }
                    item {
                        Text("文字颜色", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LutCategoryChip("白色", draft.textColor == android.graphics.Color.WHITE) { draft = draft.copy(textColor = android.graphics.Color.WHITE) }
                            LutCategoryChip("黑色", draft.textColor == android.graphics.Color.BLACK) { draft = draft.copy(textColor = android.graphics.Color.BLACK) }
                        }
                    }
                    item {
                        Text("边距 ${draft.margin}", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        Slider(draft.margin.toFloat(), { draft = draft.copy(margin = it.roundToInt()) }, valueRange = 12f..64f, colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber))
                    }
                    item {
                        Text("背景透明度 ${draft.backgroundAlpha}", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        Slider(draft.backgroundAlpha.toFloat(), { draft = draft.copy(backgroundAlpha = it.roundToInt()) }, valueRange = 0f..255f, colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber))
                    }
                    item {
                        Text("导出质量 ${draft.quality}", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        Slider(draft.quality.toFloat(), { draft = draft.copy(quality = it.roundToInt()) }, valueRange = 80f..100f, colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber))
                    }
                    if (draft.layout == WatermarkLayout.LEFT_PARAMS || draft.layout == WatermarkLayout.RIGHT_PARAMS) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.frameEnabled, { draft = draft.copy(frameEnabled = it) }, colors = CheckboxDefaults.colors(checkedColor = BridgeEmber, checkmarkColor = BridgeNight))
                            Text("图片外围增加白色边框", color = BridgeWhite)
                        }
                    }
                    if ((draft.layout == WatermarkLayout.LEFT_PARAMS || draft.layout == WatermarkLayout.RIGHT_PARAMS) && draft.frameEnabled) item {
                        Text("白边宽度 ${draft.frameThickness}", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        Slider(draft.frameThickness.toFloat(), { draft = draft.copy(frameThickness = it.roundToInt()) }, valueRange = 8f..64f, colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber))
                    }
                    if (draft.layout == WatermarkLayout.LEFT_PARAMS || draft.layout == WatermarkLayout.RIGHT_PARAMS) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.logoEnabled, { draft = draft.copy(logoEnabled = it) }, colors = CheckboxDefaults.colors(checkedColor = BridgeEmber, checkmarkColor = BridgeNight))
                            Text("显示 Logo / 文字品牌名", color = BridgeWhite)
                        }
                    }
                    if ((draft.layout == WatermarkLayout.LEFT_PARAMS || draft.layout == WatermarkLayout.RIGHT_PARAMS) && draft.logoEnabled) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(if (draft.logoUri == null) "未选择自定义 Logo" else "已选择自定义 Logo", color = BridgeWhite); Text("优先使用你的 PNG，其次匹配相机品牌 Logo", color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall) }
                            TextButton({ logoPicker.launch(arrayOf("image/png")) }) { Text("选择 PNG", color = BridgeEmber) }
                            if (draft.logoUri != null) IconButton({ draft = draft.copy(logoUri = null) }) { Icon(Icons.Default.Clear, "移除 Logo", tint = BridgeWhite.copy(alpha = .65f)) }
                        }
                    }
                    if ((draft.layout == WatermarkLayout.LEFT_PARAMS || draft.layout == WatermarkLayout.RIGHT_PARAMS) && draft.logoEnabled) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.useBrandLogo, { draft = draft.copy(useBrandLogo = it) }, colors = CheckboxDefaults.colors(checkedColor = BridgeEmber, checkmarkColor = BridgeNight))
                            Text("自动使用相机品牌 Logo", color = BridgeWhite)
                        }
                    }
                    if ((draft.layout == WatermarkLayout.LEFT_PARAMS || draft.layout == WatermarkLayout.RIGHT_PARAMS) && draft.logoUri != null) {
                        item {
                            Text("Logo 大小 ${draft.logoScale}%", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                            Slider(draft.logoScale.toFloat(), { draft = draft.copy(logoScale = it.roundToInt()) }, valueRange = 50f..180f, colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber))
                        }
                        item {
                            Text("Logo 透明度 ${draft.logoAlpha}%", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                            Slider(draft.logoAlpha.toFloat(), { draft = draft.copy(logoAlpha = it.roundToInt()) }, valueRange = 10f..100f, colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(dismiss) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) }
                    TextButton({
                        save(draft.copy(
                            name = draft.name.trim().ifBlank { "未命名水印" },
                            logoPosition = if (draft.layout == WatermarkLayout.LEFT_PARAMS) WatermarkLogoPosition.TOP_LEFT else WatermarkLogoPosition.TOP_RIGHT,
                        ))
                    }) { Text("保存", color = BridgeEmber) }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    val diagnostic by vm.diagnosticState.collectAsState()
    val context = LocalContext.current
    var exportingDiagnostics by remember { mutableStateOf(false) }
    var host by remember(config.host) { mutableStateOf(config.host) }
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    var namingRule by remember(config.fileNamingRule) { mutableStateOf(config.fileNamingRule) }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("设置", "外观、连接与导出偏好") {}
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("外观", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                ThemeSelector(config.colorTheme) { theme -> vm.updateConfig { it.copy(colorTheme = theme) } }
            }
            item {
                Text("连接偏好", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(vertical = 6.dp)) {
                    PreferenceSwitch("Wi‑Fi 自动恢复", "回到前台时检查相机连接", config.wifiAutoRestore) { vm.updateConfig { it.copy(wifiAutoRestore = !it.wifiAutoRestore) } }
                    PreferenceSwitch("USB 自动读取", "检测到授权的 USB 相机后读取相册", config.usbAutoRead) { vm.updateConfig { it.copy(usbAutoRead = !it.usbAutoRead) } }
                    PreferenceSwitch("后台保持 Wi‑Fi 连接", "仅 Wi‑Fi 连接时启用后台保活", config.keepWifiAlive) { vm.updateConfig { it.copy(keepWifiAlive = !it.keepWifiAlive) } }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = BridgeWhite.copy(alpha = .08f))
                    Column(Modifier.padding(16.dp)) {
                        Text("Wi‑Fi PTP/IP 参数", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(host, { host = it; vm.updateConfig { c -> c.copy(host = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("相机 IP 地址") }, singleLine = true, colors = bridgeFieldColors())
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(port, { value -> port = value.filter(Char::isDigit); value.toIntOrNull()?.let { number -> vm.updateConfig { c -> c.copy(port = number) } } }, Modifier.fillMaxWidth(), label = { Text("端口") }, singleLine = true, colors = bridgeFieldColors())
                        Text("尼康默认：192.168.1.1:15740", Modifier.padding(top = 10.dp), color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
            item {
                Text("导出偏好", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(vertical = 6.dp)) {
                    PreferenceSwitch("导出到系统相册", "照片编辑结果保存到 Pictures/Camera Bridge", config.autoExport) { vm.updateConfig { it.copy(autoExport = !it.autoExport) } }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { Text("JPEG 质量 ${config.jpegQuality}", color = BridgeWhite.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall); Slider(config.jpegQuality.toFloat(), { vm.updateConfig { c -> c.copy(jpegQuality = it.roundToInt()) } }, valueRange = 80f..100f, colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber)) }
                    OutlinedTextField(namingRule, { namingRule = it; vm.updateConfig { c -> c.copy(fileNamingRule = it) } }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), label = { Text("文件命名规则") }, singleLine = true, colors = bridgeFieldColors())
                } }
            }
            item {
                Text("存储", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(vertical = 6.dp)) {
                    PreferenceSwitch("缩略图缓存", "减少重复读取相机缩略图", config.thumbnailCacheEnabled) { vm.updateConfig { it.copy(thumbnailCacheEnabled = !it.thumbnailCacheEnabled) } }
                    TextButton(vm::clearThumbnailCache, Modifier.padding(horizontal = 10.dp)) { Text("清理缓存", color = Color(0xFFE57373)) }
                } }
            }
            item {
                Text("诊断与日志", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("最近连接状态", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.labelMedium)
                        Text("最近连接：${if (config.lastCameraName.isBlank()) "暂无" else "${config.lastCameraName} · ${config.lastTransport.title}"}", Modifier.padding(top = 5.dp), color = BridgeWhite, style = MaterialTheme.typography.bodySmall)
                        Text(if (diagnostic.lastEvent == null) "暂无诊断记录" else "最近事件：${diagnostic.lastEvent}", Modifier.padding(top = 5.dp), color = BridgeWhite, style = MaterialTheme.typography.bodySmall)
                        Text("最近错误：${diagnostic.lastError ?: "无"}", Modifier.padding(top = 5.dp), color = if (diagnostic.lastError == null) BridgeWhite.copy(alpha = .6f) else Color(0xFFFFA0A0), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        diagnostic.diagnosticId?.let { Text("诊断编号：$it", Modifier.padding(top = 5.dp), color = BridgeWhite.copy(alpha = .58f), style = MaterialTheme.typography.labelSmall) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton({ copyDiagnostic(context, vm.diagnosticCopyText()) }, enabled = diagnostic.lastEvent != null, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("复制最近错误", color = BridgeWhite.copy(alpha = .8f)) }
                            TextButton({ exportingDiagnostics = true; vm.exportDiagnostics { result -> exportingDiagnostics = false; result.onSuccess { shareDiagnostic(context, it) }.onFailure { error -> android.widget.Toast.makeText(context, "导出诊断失败：${error.message ?: "未知错误"}", android.widget.Toast.LENGTH_SHORT).show() } } }, enabled = !exportingDiagnostics, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (exportingDiagnostics) "正在生成…" else "导出诊断包", color = BridgeEmber) }
                        }
                        TextButton(vm::clearDiagnosticData, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("清除诊断数据", color = Color(0xFFE57373)) }
                    }
                }
            }
            item {
                Text("关于", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = BridgeWhite.copy(alpha = .06f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) { Column(Modifier.padding(16.dp)) { Text("Camera Bridge", color = BridgeWhite, fontWeight = FontWeight.Bold); Text("版本 1.0.2 · Wi‑Fi / USB 相机导入", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}

@Composable
private fun ThemeSelector(selected: AppColorTheme, select: (AppColorTheme) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppColorTheme.entries.chunked(2).forEach { themes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                themes.forEach { theme ->
                    ThemePreviewCard(theme, theme == selected, Modifier.weight(1f)) { select(theme) }
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewCard(theme: AppColorTheme, selected: Boolean, modifier: Modifier = Modifier, select: () -> Unit) {
    val palette = bridgePalette(theme)
    Surface(
        modifier = modifier.clickable(onClick = select),
        shape = RoundedCornerShape(20.dp),
        color = palette.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) palette.ember else palette.white.copy(alpha = .12f)),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(58.dp)
                    .background(Brush.linearGradient(listOf(palette.wine, palette.night))),
            ) {
                Row(Modifier.align(Alignment.CenterStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(palette.ember, palette.copper, palette.white).forEach { color ->
                        Box(Modifier.size(14.dp).background(color, CircleShape))
                    }
                }
                if (selected) {
                    Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = CircleShape, color = palette.ember) {
                        Icon(Icons.Default.Check, "已选择", tint = palette.night, modifier = Modifier.padding(4.dp).size(14.dp))
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(theme.title, color = palette.white, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(theme.subtitle, color = palette.white.copy(alpha = .5f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { toggle() }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = BridgeWhite); Text(subtitle, color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall) }
        Switch(checked, { toggle() }, colors = SwitchDefaults.colors(checkedThumbColor = BridgeNight, checkedTrackColor = BridgeEmber, uncheckedThumbColor = BridgeWhite.copy(alpha = .6f), uncheckedTrackColor = BridgeWhite.copy(alpha = .12f)))
    }
}

@Composable private fun BridgePageLabel(title: String, subtitle: String, action: @Composable () -> Unit = {}) { Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = BridgeWhite, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) }; action() } }
@Composable private fun FilterBar(selected: PhotoFilter, select: (PhotoFilter) -> Unit) { LazyRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(PhotoFilter.entries) { filter -> Surface(Modifier.clickable { select(filter) }, shape = RoundedCornerShape(18.dp), color = if (filter == selected) BridgeEmber else BridgeWhite.copy(alpha = .08f), border = if (filter == selected) null else androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) { Text(filter.title, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (filter == selected) BridgeNight else BridgeWhite, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) } } } }
@Composable private fun BridgeEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) { Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Surface(Modifier.size(72.dp), shape = CircleShape, color = BridgeEmber.copy(alpha = .13f)) { Icon(icon, null, tint = BridgeEmber, modifier = Modifier.padding(21.dp)) }; Spacer(Modifier.height(16.dp)); Text(title, color = BridgeWhite, fontWeight = FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text(subtitle, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) } }
@Composable private fun DownloadCard(row: DownloadRecord, selected: Boolean, click: () -> Unit) { val context = LocalContext.current; val bitmap by produceState<Bitmap?>(null, row.uri) { value = withContext(Dispatchers.IO) { loadDownloadThumbnail(context, row.uri) } }; val ratio = when (row.name.hashCode().and(3)) { 0 -> .72f; 1 -> 1.16f; 2 -> .86f; else -> .98f }; Card(Modifier.fillMaxWidth().clickable { click() }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, BridgeEmber) else androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) { Box(Modifier.aspectRatio(ratio)) { if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(BridgeWine, BridgeNight)), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, tint = BridgeEmber) }; if (selected) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = CircleShape, color = BridgeEmber) { Icon(Icons.Default.Check, "\u5df2\u9009\u62e9", tint = BridgeNight, modifier = Modifier.padding(6.dp).size(17.dp)) }; Surface(Modifier.align(Alignment.BottomEnd).padding(7.dp), shape = RoundedCornerShape(10.dp), color = BridgeNight.copy(alpha = .72f)) { Text(row.size.prettySize(), Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = BridgeWhite, style = MaterialTheme.typography.labelSmall) } } } }
private fun loadDownloadThumbnail(context: android.content.Context, uri: String): Bitmap? = runCatching { val parsed = Uri.parse(uri); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.contentResolver.loadThumbnail(parsed, Size(480, 640), null) else context.contentResolver.openInputStream(parsed)?.use { OrientedBitmaps.decode(it.readBytes()) } }.getOrNull()
@Composable private fun bridgeFieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = BridgeWhite, unfocusedTextColor = BridgeWhite, focusedLabelColor = BridgeEmber, unfocusedLabelColor = BridgeWhite.copy(alpha = .55f), focusedBorderColor = BridgeEmber, unfocusedBorderColor = BridgeWhite.copy(alpha = .2f), cursorColor = BridgeEmber)
@Composable private fun Header(title: String, subtitle: String, action: @Composable () -> Unit) { Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) }; action() } }
@Composable private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) { Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(icon, null, tint = Amber, modifier = Modifier.size(50.dp)); Spacer(Modifier.height(14.dp)); Text(title, fontWeight = FontWeight.Bold, color = Ink); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun PrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, click: () -> Unit) { Button(click, Modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text, fontWeight = FontWeight.Bold) } }
@Composable private fun SecondaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, click: () -> Unit) { Button(click, Modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F1F2), contentColor = Ink)) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text) } }
private fun tabIcon(tab: Tab) = when (tab) { Tab.CAMERA -> Icons.Default.CameraAlt; Tab.PHOTOS -> Icons.Default.PhotoLibrary; Tab.DOWNLOADS -> Icons.Default.Download; Tab.LUT -> Icons.Default.ColorLens; Tab.WATERMARK -> Icons.Default.TextFields; Tab.SETTINGS -> Icons.Default.Settings }
