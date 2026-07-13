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
import java.util.Date
import kotlin.math.roundToInt

private val Canvas = Color(0xFFF9F9F8)
private val Ink = Color(0xFF1A1A1A)
private val Muted = Color(0xFF6B727E)
private val Amber = Color(0xFFD4770A)
private val AmberSoft = Color(0xFFF7E8D2)
private val Green = Color(0xFF24984B)
private val BridgeNight = Color(0xFF0E090C)
private val BridgeWine = Color(0xFF35140F)
private val BridgeEmber = Color(0xFFFF7133)
private val BridgeCopper = Color(0xFFB95732)
private val BridgeWhite = Color(0xFFFFF7F1)
private data class InlineTask(val title: String, val detail: String, val running: Boolean, val failed: Boolean = false)
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
    val hasLoadedPhotos by vm.hasLoadedPhotos.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.CAMERA) }
    val landing = tab == Tab.CAMERA && session == null
    LaunchedEffect(session, hasLoadedPhotos) {
        if (session == null) tab = Tab.CAMERA
        else if (hasLoadedPhotos) tab = Tab.PHOTOS
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.checkConnectionOnResume() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(snackbarText) { snackbarText?.let { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) } }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = BridgeNight, surface = Color(0xFF181015), primary = BridgeEmber, onPrimary = BridgeWhite)) {
        Scaffold(containerColor = BridgeNight, snackbarHost = { SnackbarHost(snackbarHostState) }, bottomBar = {
            if (!landing) {
                BridgeNavigation(tab) { tab = it }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!landing) AnimatedActivityPill(notice, busy, if (workflow == Workflow.ERROR) vm::retry else null)
                AnimatedContent(tab, label = "tab") { screen ->
                    when (screen) {
                        Tab.CAMERA -> CameraScreen(vm, workflow, busy, notice) { tab = Tab.SETTINGS }
                        Tab.PHOTOS -> GalleryScreen(vm, busy)
                        Tab.DOWNLOADS -> DownloadsScreen(vm)
                        Tab.LUT -> LutScreen(vm) { tab = Tab.WATERMARK }
                        Tab.WATERMARK -> WatermarkScreen(vm) { tab = Tab.LUT }
                        Tab.SETTINGS -> SettingsScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeNavigation(selected: Tab, select: (Tab) -> Unit) {
    Box(Modifier.fillMaxWidth().height(99.dp).padding(horizontal = 18.dp).padding(bottom = 36.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(39.dp), color = Color(0xFF1D191E), shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .06f))) {
            Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Tab.entries.filterNot { it == Tab.WATERMARK }.forEach { item ->
                    val active = item == selected || (selected == Tab.WATERMARK && item == Tab.LUT)
                    if (active) {
                        Surface(Modifier.size(62.dp), shape = RoundedCornerShape(31.dp), color = BridgeWhite) { IconButton({ select(item) }) { Icon(tabIcon(item), item.title, tint = BridgeNight, modifier = Modifier.size(25.dp)) } }
                    } else IconButton({ select(item) }, Modifier.size(62.dp)) { Icon(tabIcon(item), item.title, tint = BridgeWhite.copy(alpha = .62f), modifier = Modifier.size(27.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ActivityPill(text: String, busy: Boolean, modifier: Modifier, retry: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = if (busy) BridgeEmber.copy(alpha = .16f) else if (retry != null) Color(0xFFE57373).copy(alpha = .12f) else BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, if (retry != null) Color(0xFFE57373).copy(alpha = .3f) else BridgeWhite.copy(alpha = .1f))) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BridgeEmber) else Icon(if (retry != null) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, tint = if (retry != null) Color(0xFFE57373) else BridgeEmber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (retry != null) { TextButton(retry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("重试", color = Color(0xFFE57373), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) } }
        }
    }
}

@Composable
private fun CameraScreen(vm: MainViewModel, state: Workflow, busy: Boolean, notice: String?, openSettings: () -> Unit) {
    val session by vm.session.collectAsState()
    val photos by vm.photos.collectAsState()
    val config by vm.config.collectAsState()
    if (session == null) ConnectionLanding(state, busy, notice, { vm.connect(true) }, openSettings, config.lastSsid, config.brand) { brand -> vm.updateConfig { it.copy(brand = brand) } }
    else Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BridgeWine, BridgeNight))).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BridgePageLabel("\u76f8\u673a", "\u5b89\u5168\u6865\u63a5\u5df2\u5efa\u7acb")
        Spacer(Modifier.weight(1f)); LensGlow(state); Spacer(Modifier.height(24.dp))
        Text("${session!!.name} \u5df2\u8fde\u63a5", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = BridgeWhite)
        Spacer(Modifier.height(10.dp)); Status(state); Spacer(Modifier.weight(1f))
        Card(colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CameraAlt, null, tint = BridgeNight, modifier = Modifier.background(BridgeEmber, RoundedCornerShape(10.dp)).padding(8.dp))
                    Spacer(Modifier.width(12.dp)); Column { Text(session!!.name, fontWeight = FontWeight.Bold, color = BridgeWhite); Text("\u5df2\u51c6\u5907\u597d\u6d4f\u89c8\u548c\u4e0b\u8f7d", color = BridgeWhite.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall) }
                }
                if (photos.isNotEmpty()) Text("已连接 ${session!!.name} · 已读取 ${photos.size} 张", Modifier.padding(top = 14.dp), color = BridgeWhite.copy(alpha = .7f))
                Spacer(Modifier.height(14.dp)); Button(vm::loadPhotos, Modifier.fillMaxWidth(), enabled = !busy, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("\u8bfb\u53d6\u7167\u7247") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(vm::disconnect, Modifier.fillMaxWidth(), enabled = !busy, shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeWhite.copy(alpha = .08f), contentColor = BridgeWhite)) { Text("\u65ad\u5f00\u8fde\u63a5") }
        Spacer(Modifier.height(22.dp))
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
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BridgeWine, BridgeNight, Color(0xFF08070A))))) {
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
                DropdownMenu(showCameraPicker, { showCameraPicker = false }, modifier = Modifier.background(Color(0xFF251215))) {
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
    val hasMore by vm.hasMorePhotos.collectAsState()
    val pageTask by vm.pageTask.collectAsState()
    val luts by vm.luts.collectAsState()
    val recentLutIds by vm.recentLutIds.collectAsState()
    val watermarks by vm.watermarks.collectAsState()
    var preview by remember { mutableStateOf<PhotoAsset?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
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
    LaunchedEffect(gradedBitmap, previewBitmap, selectedWatermark, photoMetadata) {
        val base = gradedBitmap ?: previewBitmap
        val preset = selectedWatermark
        if (base == null || preset == null) {
            watermarkedBitmap = null
            watermarking = false
        } else {
            inlineTask = InlineTask("正在生成水印…", "${preset.name} · 预览", running = true)
            watermarking = true
            val result = runCatching { withContext(Dispatchers.Default) { WatermarkRenderer.render(base, photoMetadata ?: PhotoMetadata(capturedAt = preview?.capturedAt), preset, context) } }
            result.onSuccess { watermarkedBitmap = it; inlineTask = InlineTask("✓ 已生成水印", "${preset.name} · 预览", running = false) }
                .onFailure { watermarkedBitmap = null; inlineTask = InlineTask("生成水印失败：${it.message ?: "无法处理图片"}", "", running = false, failed = true) }
            watermarking = false
        }
    }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("\u76f8\u518c", if (photos.isNotEmpty()) "${visiblePhotos.size} \u4e2a\u6587\u4ef6" else "\u8fde\u63a5\u76f8\u673a\u540e\u5f00\u59cb") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    TextButton({ selectionMode = false; selectedHandles = emptySet() }, enabled = !busy) { Text("\u53d6\u6d88", color = BridgeWhite) }
                    Text("${selectedHandles.size} \u5f20", Modifier.padding(horizontal = 8.dp), color = BridgeWhite.copy(alpha = .72f), style = MaterialTheme.typography.labelLarge)
                    Box {
                        TextButton({ selectionMenu = true }, enabled = !busy && selectedHandles.isNotEmpty()) { Text("\u64cd\u4f5c \u25be", color = BridgeWhite) }
                        DropdownMenu(selectionMenu, { selectionMenu = false }) {
                            DropdownMenuItem({ Text("\u4e0b\u8f7d\u539f\u56fe") }, { vm.downloadAll(photos.filter { it.handle in selectedHandles }); selectedHandles = emptySet(); selectionMode = false; selectionMenu = false })
                            DropdownMenuItem({ Text("\u5957 LUT") }, { batchLutWatermarkMode = false; showBatchLutPicker = true; selectionMenu = false })
                            DropdownMenuItem({ Text("添加水印") }, { showBatchWatermarkPicker = true; selectionMenu = false })
                            DropdownMenuItem({ Text("LUT + 水印") }, { batchLutWatermarkMode = true; showBatchLutPicker = true; selectionMenu = false })
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
        else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(148.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 94.dp), verticalItemSpacing = 10.dp, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visiblePhotos, key = { it.handle.toString() }) { asset ->
                PhotoCard(asset, vm.thumbnails[asset.handle], { vm.loadThumbnail(asset) }, asset.handle in selectedHandles) {
                    if (selectionMode) selectedHandles = if (asset.handle in selectedHandles) selectedHandles - asset.handle else selectedHandles + asset.handle
                    else { vm.loadThumbnail(asset); previewBitmap = null; previewLoading = false; selectedLut = null; selectedLutEntry = null; selectedWatermark = null; watermarkedBitmap = null; photoMetadata = null; inlineTask = null; inlineRetry = {}; preview = asset }
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
                scope.launch {
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
        val startExport: (() -> Unit)? = if ((gradedBitmap != null || previewBitmap != null) && (selectedLut != null || selectedWatermark != null)) {
            {
                val run = {
                    val outputBitmap = watermarkedBitmap ?: gradedBitmap ?: previewBitmap
                    if (outputBitmap != null) {
                        val suffix = buildList { selectedLut?.let { add(it.name) }; if (selectedWatermark != null) add("Watermark") }.joinToString("_")
                        val title = when { selectedLut != null && selectedWatermark != null -> "导出编辑图"; selectedWatermark != null -> "导出水印图"; else -> "导出套色图" }
                        inlineTask = InlineTask("正在$title…", "${selectedWatermark?.name ?: selectedLutEntry?.name ?: "高质量 JPG"}", running = true)
                        vm.exportEditedBitmap(asset, outputBitmap, photoMetadata ?: PhotoMetadata(capturedAt = asset.capturedAt), suffix, selectedWatermark?.quality ?: 95, inline = true) { result -> result.fold({ inlineTask = InlineTask("✓ 已${title.removePrefix("导出")}", it.name, running = false) }, { inlineTask = InlineTask("导出失败：${it.message ?: "未知错误"}", "", running = false, failed = true) }) }
                    }
                    Unit
                }
                inlineRetry = run; run()
            }
        } else null
        PreviewDialog(asset.name, asset.size, vm.thumbnails[asset.handle], watermarkedBitmap ?: gradedBitmap ?: previewBitmap, previewLoading || grading || watermarking, { preview = null }, {
            startLoadOriginal()
        }, previewBitmap != null, luts, recentLutIds, { entry ->
            if (entry == null) { selectedLutEntry = null; selectedLut = null; inlineTask = null; inlineRetry = {} }
            else {
                vm.markLutUsed(entry)
                scope.launch { selectedLut = runCatching { vm.readLut(entry) }.getOrNull(); selectedLutEntry = if (selectedLut != null) entry else null }
            }
        }, selectedLutEntry?.name, download = startDownload, watermarks = watermarks, selectWatermark = { preset -> selectedWatermark = preset }, watermarkName = selectedWatermark?.name, exportEdited = startExport, createWatermark = { showWatermarkEditor = true; editingWatermark = null }, inlineTask = inlineTask, dismissInline = { inlineTask = null; inlineRetry = {} }, retryInline = inlineRetry)
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
        AlertDialog(onDismissRequest = { showBatchConfirm = false }, title = { Text("批量套用 LUT", color = BridgeWhite) }, text = { Column { Text("将为 ${selected.size} 张图片应用「${batchLutEntry!!.name}」并导出新图片。$suffix", color = BridgeWhite.copy(alpha = .8f)); if (batchLutLoading) { Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BridgeEmber); Spacer(Modifier.width(8.dp)); Text("正在读取 LUT…", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall) } } } }, confirmButton = { TextButton({ batchLut?.let { lut -> showBatchConfirm = false; batchLutLoading = false; vm.downloadAllWithLut(selected, lut) { selectionMode = false; selectedHandles = emptySet() } } }, enabled = !batchLutLoading && batchLut != null) { Text("开始导出", color = if (batchLutLoading) BridgeWhite.copy(alpha = .35f) else BridgeEmber) } }, dismissButton = { TextButton({ showBatchConfirm = false }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = Color(0xFF171015), titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    if (showBatchWatermarkConfirm && batchWatermark != null) {
        val selected = photos.filter { it.handle in selectedHandles }
        val videoCount = selected.count { it.type in setOf("MOV", "MP4") }
        val suffix = if (videoCount > 0) "视频将自动跳过。" else ""
        val combined = batchLutWatermarkMode && batchLut != null
        AlertDialog(onDismissRequest = { showBatchWatermarkConfirm = false }, title = { Text(if (combined) "批量导出编辑图" else "批量添加水印", color = BridgeWhite) }, text = { Text(if (combined) "将为 ${selected.size} 张图片先应用「${batchLutEntry?.name}」，再添加「${batchWatermark!!.name}」水印并导出新文件。原图不会被覆盖。$suffix" else "将为 ${selected.size} 张图片添加「${batchWatermark!!.name}」水印并导出新文件。原图不会被覆盖。$suffix", color = BridgeWhite.copy(alpha = .8f)) }, confirmButton = { TextButton({ showBatchWatermarkConfirm = false; if (combined) vm.applyLutAndWatermarkToPhotos(selected, batchLut!!, batchWatermark!!) { selectionMode = false; selectedHandles = emptySet(); batchLutWatermarkMode = false } else vm.addWatermarkToPhotos(selected, batchWatermark!!) { selectionMode = false; selectedHandles = emptySet() } }) { Text("开始导出", color = BridgeEmber) } }, dismissButton = { TextButton({ showBatchWatermarkConfirm = false }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = Color(0xFF171015), titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
}

@Composable
private fun AnimatedActivityPill(text: String?, busy: Boolean, retry: (() -> Unit)?) {
    var shownText by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text != null) shownText = text
        else { delay(180); shownText = null }
    }
    AnimatedVisibility(visible = text != null, enter = fadeIn(tween(220)) + expandVertically(tween(220), expandFrom = Alignment.Top) + slideInVertically(tween(220)) { -it / 3 }, exit = fadeOut(tween(180)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + slideOutVertically(tween(180)) { -it / 3 }) {
        shownText?.let { ActivityPill(it, busy, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), retry) }
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
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Color(0xFF171015), contentColor = BridgeWhite) {
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
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Color(0xFF171015), contentColor = BridgeWhite) {
        Text("选择水印", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (presets.isEmpty()) Text("请先创建水印预设", Modifier.fillMaxWidth().padding(28.dp), color = BridgeWhite.copy(alpha = .62f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets, key = { it.id }) { preset ->
                Surface(Modifier.fillMaxWidth().clickable { select(preset) }, shape = RoundedCornerShape(16.dp), color = BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TextFields, null, tint = BridgeEmber, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                        Column { Text(preset.name, color = BridgeWhite, fontWeight = FontWeight.SemiBold); Text(preset.layout.title, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) }
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
private fun PreviewDialog(name: String, size: Long, thumbnail: Bitmap?, bitmap: Bitmap?, loading: Boolean, dismiss: () -> Unit, loadOriginal: (() -> Unit)? = null, originalLoaded: Boolean = bitmap != null, luts: List<LutEntry> = emptyList(), recentLutIds: List<String> = emptyList(), selectLut: ((LutEntry?) -> Unit)? = null, lutName: String? = null, exportLut: (() -> Unit)? = null, download: (() -> Unit)? = null, watermarks: List<WatermarkPreset> = emptyList(), selectWatermark: ((WatermarkPreset?) -> Unit)? = null, watermarkName: String? = null, exportEdited: (() -> Unit)? = null, createWatermark: () -> Unit = {}, inlineTask: InlineTask? = null, dismissInline: () -> Unit = {}, retryInline: () -> Unit = {}) {
    var rotation by remember(name) { mutableIntStateOf(0) }
    var lutMenu by remember(name) { mutableStateOf(false) }
    var watermarkMenu by remember(name) { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.9f), shape = RoundedCornerShape(28.dp), color = Color(0xFF171015), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(name, color = BridgeWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(size.prettySize(), color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) }
                    IconButton({ rotation = (rotation - 90 + 360) % 360 }) { Icon(Icons.Default.RotateLeft, "逆时针旋转 90 度", tint = BridgeWhite) }
                    IconButton(dismiss) { Icon(Icons.Default.Close, "关闭", tint = BridgeWhite) }
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).background(BridgeNight, RoundedCornerShape(20.dp)).clipToBounds(), contentAlignment = Alignment.Center) {
                    (bitmap ?: thumbnail)?.let { ZoomableImage(it, rotation) } ?: Text("正在加载缩略图", color = BridgeWhite)
                    if (loading) Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = BridgeEmber); Spacer(Modifier.height(8.dp)); Text(if (bitmap != null) "正在套用 LUT" else "正在加载", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall) }
                }
                val taskRunning = inlineTask?.running == true
                val exportRunning = taskRunning && inlineTask?.title?.startsWith("正在导出") == true
                val downloadRunning = taskRunning && inlineTask?.title?.startsWith("正在下载") == true
                val exportAction = exportEdited ?: exportLut
                val exportLabel = when { lutName != null && watermarkName != null -> "导出编辑图"; watermarkName != null -> "导出水印图"; lutName != null -> "导出套色图"; else -> "导出" }
                if (loadOriginal != null || download != null || selectLut != null || selectWatermark != null || exportAction != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (download != null) TextButton({ download() }, enabled = !taskRunning) { if (downloadRunning) { CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = BridgeWhite); Spacer(Modifier.width(6.dp)); Text("正在下载…", color = BridgeWhite) } else Text("下载原图", color = BridgeWhite) }
                    if (selectLut != null) TextButton({ lutMenu = true }, enabled = originalLoaded) { Text(if (lutName == null) "LUT" else lutName, color = if (originalLoaded) BridgeWhite else BridgeWhite.copy(alpha = .35f)) }
                    if (selectWatermark != null) TextButton({ watermarkMenu = true }, enabled = originalLoaded) { Text(if (watermarkName == null) "水印" else watermarkName, color = if (originalLoaded) BridgeWhite else BridgeWhite.copy(alpha = .35f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (exportAction != null || selectLut != null || selectWatermark != null) TextButton({ exportAction?.invoke() }, enabled = exportAction != null && !taskRunning) { if (exportRunning) { CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = BridgeEmber); Spacer(Modifier.width(6.dp)); Text("正在导出…", color = BridgeEmber) } else Text(exportLabel, color = if (exportAction != null) BridgeEmber else BridgeEmber.copy(alpha = .35f)) }
                    if (loadOriginal != null) Button({ loadOriginal() }, enabled = !loading && !taskRunning, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) { Text(if (taskRunning) "正在处理…" else if (bitmap == null) "查看原图" else "重新加载") }
                }
                InlineStatusCard(inlineTask, dismissInline, retryInline)
            }
        }
    }
    if (lutMenu && selectLut != null) LutPickerSheet(true, luts, recentLutIds, { lutMenu = false }, { entry -> lutMenu = false; selectLut(entry) })
    if (watermarkMenu && selectWatermark != null) WatermarkPickerSheet(true, watermarks, { watermarkMenu = false }, { preset -> watermarkMenu = false; selectWatermark(preset) }, { watermarkMenu = false; createWatermark() })
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
private fun DownloadsScreen(vm: MainViewModel) {
    val rows by vm.downloads.collectAsState()
    val luts by vm.luts.collectAsState()
    val recentLutIds by vm.recentLutIds.collectAsState()
    val watermarks by vm.watermarks.collectAsState()
    val pageTask by vm.pageTask.collectAsState()
    var preview by remember { mutableStateOf<DownloadRecord?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
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
    LaunchedEffect(gradedBitmap, previewBitmap, selectedWatermark, photoMetadata) {
        val base = gradedBitmap ?: previewBitmap
        val preset = selectedWatermark
        if (base == null || preset == null) {
            watermarkedBitmap = null
            watermarking = false
        } else {
            inlineTask = InlineTask("正在生成水印…", "${preset.name} · 预览", running = true)
            watermarking = true
            val result = runCatching { withContext(Dispatchers.Default) { WatermarkRenderer.render(base, photoMetadata ?: PhotoMetadata(), preset, context) } }
            result.onSuccess { watermarkedBitmap = it; inlineTask = InlineTask("✓ 已生成水印", "${preset.name} · 预览", running = false) }
                .onFailure { watermarkedBitmap = null; inlineTask = InlineTask("生成水印失败：${it.message ?: "无法处理图片"}", "", running = false, failed = true) }
            watermarking = false
        }
    }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("\u4e0b\u8f7d", "\u5df2\u4fdd\u5b58 ${rows.size} \u4e2a\u6587\u4ef6") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    TextButton({ selectionMode = false; selectedUris = emptySet() }) { Text("取消", color = BridgeWhite) }
                    Text("${selectedUris.size} 个", Modifier.padding(horizontal = 8.dp), color = BridgeWhite.copy(alpha = .72f), style = MaterialTheme.typography.labelLarge)
                    Box {
                        TextButton({ selectionMenu = true }, enabled = selectedUris.isNotEmpty()) { Text("操作 ▾", color = BridgeWhite) }
                        DropdownMenu(selectionMenu, { selectionMenu = false }) {
                            DropdownMenuItem({ Text("套 LUT") }, { batchLutWatermarkMode = false; showBatchLutPicker = true; selectionMenu = false })
                            DropdownMenuItem({ Text("添加水印") }, { showBatchWatermarkPicker = true; selectionMenu = false })
                            DropdownMenuItem({ Text("LUT + 水印") }, { batchLutWatermarkMode = true; showBatchLutPicker = true; selectionMenu = false })
                            DropdownMenuItem({ Text("分享") }, { shareUris(context, rows.filter { it.uri in selectedUris }.map { it.uri }); selectedUris = emptySet(); selectionMode = false; selectionMenu = false })
                            DropdownMenuItem({ Text("删除", color = Color(0xFFE57373)) }, { showDeleteConfirm = true; selectionMenu = false })
                        }
                    }
                } else TextButton({ selectionMode = true }) { Text("多选", color = BridgeWhite) }
            }
        }
        PageTaskBar(pageTask, vm::retryPageTask)
        FilterBar(filter) { filter = it; selectedUris = emptySet() }
        if (rows.isEmpty()) BridgeEmptyState(Icons.Default.CloudDownload, "\u6682\u65e0\u4e0b\u8f7d\u8bb0\u5f55", "\u5728\u76f8\u518c\u4e2d\u9009\u62e9\u7167\u7247\u5373\u53ef\u4fdd\u5b58\u5230\u624b\u673a")
        else if (visibleRows.isEmpty()) BridgeEmptyState(Icons.Default.FilterAlt, "\u6b64\u5206\u7c7b\u6682\u65e0\u6587\u4ef6", "\u8bd5\u8bd5\u5176\u4ed6\u5206\u7c7b")
        else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(148.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 94.dp), verticalItemSpacing = 10.dp, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visibleRows, key = { it.uri }) { row ->
                DownloadCard(row, row.uri in selectedUris) {
                    if (selectionMode) selectedUris = if (row.uri in selectedUris) selectedUris - row.uri else selectedUris + row.uri
                    else { previewLut = null; gradedBitmap = null; selectedWatermark = null; watermarkedBitmap = null; photoMetadata = null; inlineTask = null; inlineRetry = {}; preview = row }
                }
            }
        }
    }
    preview?.let { row ->
        val startExport: (() -> Unit)? = if ((gradedBitmap != null || previewBitmap != null) && (previewLut != null || selectedWatermark != null)) {
            {
                val run = {
                    val outputBitmap = watermarkedBitmap ?: gradedBitmap ?: previewBitmap
                    if (outputBitmap != null) {
                        val suffix = buildList { previewLut?.let { add(it.name) }; if (selectedWatermark != null) add("Watermark") }.joinToString("_")
                        val title = when { previewLut != null && selectedWatermark != null -> "导出编辑图"; selectedWatermark != null -> "导出水印图"; else -> "导出套色图" }
                        inlineTask = InlineTask("正在$title…", "${selectedWatermark?.name ?: previewLut?.name ?: "高质量 JPG"}", running = true)
                        vm.exportEditedBitmap(PhotoAsset(0u, row.name, row.size, 0x3801), outputBitmap, photoMetadata ?: PhotoMetadata(capturedAt = Date(row.completedAt)), suffix, selectedWatermark?.quality ?: 95, inline = true) { result -> result.fold({ inlineTask = InlineTask("✓ 已${title.removePrefix("导出")}", it.name, running = false) }, { inlineTask = InlineTask("导出失败：${it.message ?: "未知错误"}", "", running = false, failed = true) }) }
                    }
                    Unit
                }
                inlineRetry = run; run()
            }
        } else null
        PreviewDialog(row.name, row.size, null, watermarkedBitmap ?: gradedBitmap ?: previewBitmap, previewLoading || grading || watermarking, { preview = null; previewLut = null; gradedBitmap = null; selectedWatermark = null; watermarkedBitmap = null; inlineTask = null; inlineRetry = {} }, null, previewBitmap != null, luts, recentLutIds, { entry -> previewLut = entry; entry?.let(vm::markLutUsed) }, previewLut?.name, download = null, watermarks = watermarks, selectWatermark = { preset -> selectedWatermark = preset }, watermarkName = selectedWatermark?.name, exportEdited = startExport, createWatermark = { showWatermarkEditor = true; editingWatermark = null }, inlineTask = inlineTask, dismissInline = { inlineTask = null; inlineRetry = {} }, retryInline = inlineRetry)
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
        AlertDialog(onDismissRequest = { showBatchConfirm = false }, title = { Text("批量套用 LUT", color = BridgeWhite) }, text = { Column { Text("将为 ${selected.size} 个文件应用「${batchLutEntry!!.name}」并导出为新图片。$suffix", color = BridgeWhite.copy(alpha = .8f)); if (batchLutLoading) { Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BridgeEmber); Spacer(Modifier.width(8.dp)); Text("正在读取 LUT…", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall) } } } }, confirmButton = { TextButton({ batchLut?.let { lut -> showBatchConfirm = false; batchLutLoading = false; vm.applyLutToDownloads(selected, lut) { selectionMode = false; selectedUris = emptySet() } } }, enabled = !batchLutLoading && batchLut != null) { Text("开始导出", color = if (batchLutLoading) BridgeWhite.copy(alpha = .35f) else BridgeEmber) } }, dismissButton = { TextButton({ showBatchConfirm = false }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = Color(0xFF171015), titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    if (showBatchWatermarkConfirm && batchWatermark != null) {
        val selected = rows.filter { it.uri in selectedUris }
        val videoCount = selected.count { !it.name.supportsLutInput() }
        val suffix = if (videoCount > 0) "视频将自动跳过。" else ""
        val combined = batchLutWatermarkMode && batchLut != null
        AlertDialog(onDismissRequest = { showBatchWatermarkConfirm = false }, title = { Text(if (combined) "批量导出编辑图" else "批量添加水印", color = BridgeWhite) }, text = { Text(if (combined) "将为 ${selected.size} 个文件先应用「${batchLutEntry?.name}」，再添加「${batchWatermark!!.name}」水印并导出新文件。原图不会被覆盖。$suffix" else "将为 ${selected.size} 个文件添加「${batchWatermark!!.name}」水印并导出新文件。原图不会被覆盖。$suffix", color = BridgeWhite.copy(alpha = .8f)) }, confirmButton = { TextButton({ showBatchWatermarkConfirm = false; if (combined) vm.applyLutAndWatermarkToDownloads(selected, batchLut!!, batchWatermark!!) { selectionMode = false; selectedUris = emptySet(); batchLutWatermarkMode = false } else vm.addWatermarkToDownloads(selected, batchWatermark!!) { selectionMode = false; selectedUris = emptySet() } }) { Text("开始导出", color = BridgeEmber) } }, dismissButton = { TextButton({ showBatchWatermarkConfirm = false }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = Color(0xFF171015), titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("\u5220\u9664\u6587\u4ef6", color = BridgeWhite) },
            text = { Text("\u786e\u8ba4\u5220\u9664\u9009\u4e2d\u7684 ${selectedUris.size} \u4e2a\u6587\u4ef6\uff1f\u5220\u9664\u540e\u65e0\u6cd5\u6062\u590d\u3002", color = BridgeWhite.copy(alpha = .8f)) },
            confirmButton = { TextButton({ vm.deleteDownloads(selectedUris); selectedUris = emptySet(); selectionMode = false; showDeleteConfirm = false }) { Text("\u5220\u9664", color = Color(0xFFE57373)) } },
            dismissButton = { TextButton({ showDeleteConfirm = false }) { Text("\u53d6\u6d88", color = BridgeWhite.copy(alpha = .6f)) } },
            containerColor = Color(0xFF171015)
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
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 94.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("重命名 LUT", color = BridgeWhite) }, text = { OutlinedTextField(editName, { editName = it }, singleLine = true, colors = bridgeFieldColors()) }, confirmButton = { TextButton({ vm.updateLut(entry, name = editName); editing = null }) { Text("保存", color = BridgeEmber) } }, dismissButton = { TextButton({ editing = null }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = Color(0xFF171015), titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    categoryEditor?.let { entry ->
        AlertDialog(onDismissRequest = { categoryEditor = null }, title = { Text("修改分类", color = BridgeWhite) }, text = {
            Column { LutCategory.entries.forEach { type -> Row(Modifier.fillMaxWidth().clickable { vm.updateLut(entry, category = type); categoryEditor = null }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(type == entry.category, { vm.updateLut(entry, category = type); categoryEditor = null }, colors = RadioButtonDefaults.colors(selectedColor = BridgeEmber)); Text(type.title, color = BridgeWhite) } } }
        }, confirmButton = {}, containerColor = Color(0xFF171015), titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
    }
    deleteConfirm?.let { entry ->
        AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("删除 LUT", color = BridgeWhite) }, text = { Text("删除后不会影响已经导出的图片。", color = BridgeWhite.copy(alpha = .8f)) }, confirmButton = { TextButton({ vm.removeLut(entry); deleteConfirm = null }) { Text("删除", color = Color(0xFFE57373)) } }, dismissButton = { TextButton({ deleteConfirm = null }) { Text("取消", color = BridgeWhite.copy(alpha = .7f)) } }, containerColor = Color(0xFF171015), titleContentColor = BridgeWhite, textContentColor = BridgeWhite)
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
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 94.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(presets, key = { it.id }) { preset ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(42.dp), shape = CircleShape, color = BridgeEmber.copy(alpha = .15f)) { Icon(Icons.Default.TextFields, null, Modifier.padding(11.dp), tint = BridgeEmber) }
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
            containerColor = Color(0xFF171015),
            titleContentColor = BridgeWhite,
            textContentColor = BridgeWhite,
        )
    }
}

@Composable
private fun WatermarkEditorDialog(existing: WatermarkPreset?, dismiss: () -> Unit, save: (WatermarkPreset) -> Unit) {
    val context = LocalContext.current
    var draft by remember(existing?.id) { mutableStateOf(existing ?: WatermarkPreset(java.util.UUID.randomUUID().toString(), "我的水印")) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            draft = draft.copy(logoUri = it.toString())
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f), shape = RoundedCornerShape(28.dp), color = Color(0xFF171015), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (existing == null) "新建水印预设" else "编辑水印预设", Modifier.weight(1f), color = BridgeWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(dismiss) { Icon(Icons.Default.Close, "关闭", tint = BridgeWhite) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("预设名称") }, singleLine = true, colors = bridgeFieldColors()) }
                    item { Text("布局", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.labelLarge) }
                    item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(WatermarkLayout.entries) { layout -> LutCategoryChip(layout.title, draft.layout == layout) { draft = draft.copy(layout = layout) } } } }
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
                    item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.showBorder, { draft = draft.copy(showBorder = it) }, colors = CheckboxDefaults.colors(checkedColor = BridgeEmber, checkmarkColor = BridgeNight)); Text("显示边框", color = BridgeWhite) } }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.logoEnabled, { draft = draft.copy(logoEnabled = it) }, colors = CheckboxDefaults.colors(checkedColor = BridgeEmber, checkmarkColor = BridgeNight))
                            Text("显示 Logo", color = BridgeWhite)
                        }
                    }
                    if (draft.logoEnabled) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(if (draft.logoUri == null) "未选择自定义 Logo" else "已选择自定义 Logo", color = BridgeWhite); Text("可选择自己的 PNG；未选择时按 EXIF 自动匹配品牌 Logo", color = BridgeWhite.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall) }
                            TextButton({ logoPicker.launch(arrayOf("image/png")) }) { Text("选择 PNG", color = BridgeEmber) }
                            if (draft.logoUri != null) IconButton({ draft = draft.copy(logoUri = null) }) { Icon(Icons.Default.Clear, "移除 Logo", tint = BridgeWhite.copy(alpha = .65f)) }
                        }
                    }
                    if (draft.logoEnabled) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.useBrandLogo, { draft = draft.copy(useBrandLogo = it) }, colors = CheckboxDefaults.colors(checkedColor = BridgeEmber, checkmarkColor = BridgeNight))
                            Text("自动使用 EXIF 相机品牌 Logo", color = BridgeWhite)
                        }
                    }
                    if (draft.logoUri != null) {
                        item {
                            Text("Logo 位置", color = BridgeWhite.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(WatermarkLogoPosition.entries) { position -> LutCategoryChip(position.title, draft.logoPosition == position) { draft = draft.copy(logoPosition = position) } }
                            }
                        }
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
                    TextButton({ save(draft.copy(name = draft.name.trim().ifBlank { "未命名水印" })) }) { Text("保存", color = BridgeEmber) }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    var host by remember(config.host) { mutableStateOf(config.host) }
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("\u8bbe\u7f6e", "\u76f8\u673a IP \u4e0e\u7aef\u53e3") {}
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 94.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("\u5c3c\u5eb7 PTP/IP", color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f)), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(host, { host = it; vm.updateConfig { c -> c.copy(host = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("\u76f8\u673a IP \u5730\u5740") }, singleLine = true, colors = bridgeFieldColors())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(port, { value -> port = value.filter(Char::isDigit); value.toIntOrNull()?.let { number -> vm.updateConfig { c -> c.copy(port = number) } } }, Modifier.fillMaxWidth(), label = { Text("\u7aef\u53e3") }, singleLine = true, colors = bridgeFieldColors())
                    Text("\u5c3c\u5eb7\u9ed8\u8ba4\uff1a192.168.1.1:15740", Modifier.padding(top = 10.dp), color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
}

@Composable private fun BridgePageLabel(title: String, subtitle: String, action: @Composable () -> Unit = {}) { Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = BridgeWhite, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) }; action() } }
@Composable private fun FilterBar(selected: PhotoFilter, select: (PhotoFilter) -> Unit) { LazyRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(PhotoFilter.entries) { filter -> Surface(Modifier.clickable { select(filter) }, shape = RoundedCornerShape(18.dp), color = if (filter == selected) BridgeEmber else BridgeWhite.copy(alpha = .08f), border = if (filter == selected) null else androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) { Text(filter.title, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (filter == selected) BridgeNight else BridgeWhite, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) } } } }
@Composable private fun BridgeEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) { Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Surface(Modifier.size(72.dp), shape = CircleShape, color = BridgeEmber.copy(alpha = .13f)) { Icon(icon, null, tint = BridgeEmber, modifier = Modifier.padding(21.dp)) }; Spacer(Modifier.height(16.dp)); Text(title, color = BridgeWhite, fontWeight = FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text(subtitle, color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) } }
@Composable private fun DownloadCard(row: DownloadRecord, selected: Boolean, click: () -> Unit) { val context = LocalContext.current; val bitmap by produceState<Bitmap?>(null, row.uri) { value = withContext(Dispatchers.IO) { loadDownloadThumbnail(context, row.uri) } }; val ratio = when (row.name.hashCode().and(3)) { 0 -> .72f; 1 -> 1.16f; 2 -> .86f; else -> .98f }; Card(Modifier.fillMaxWidth().clickable { click() }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = BridgeWhite.copy(alpha = .08f)), border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, BridgeEmber) else androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) { Box(Modifier.aspectRatio(ratio)) { if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(BridgeWine, BridgeNight)), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, tint = BridgeEmber) }; if (selected) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = CircleShape, color = BridgeEmber) { Icon(Icons.Default.Check, "\u5df2\u9009\u62e9", tint = BridgeNight, modifier = Modifier.padding(6.dp).size(17.dp)) }; Surface(Modifier.align(Alignment.BottomEnd).padding(7.dp), shape = RoundedCornerShape(10.dp), color = BridgeNight.copy(alpha = .72f)) { Text(row.size.prettySize(), Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = BridgeWhite, style = MaterialTheme.typography.labelSmall) } } } }
private fun loadDownloadThumbnail(context: android.content.Context, uri: String): Bitmap? = runCatching { val parsed = Uri.parse(uri); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.contentResolver.loadThumbnail(parsed, Size(480, 640), null) else context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
@Composable private fun bridgeFieldColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = BridgeWhite, unfocusedTextColor = BridgeWhite, focusedLabelColor = BridgeEmber, unfocusedLabelColor = BridgeWhite.copy(alpha = .55f), focusedBorderColor = BridgeEmber, unfocusedBorderColor = BridgeWhite.copy(alpha = .2f), cursorColor = BridgeEmber)
@Composable private fun Header(title: String, subtitle: String, action: @Composable () -> Unit) { Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) }; action() } }
@Composable private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) { Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(icon, null, tint = Amber, modifier = Modifier.size(50.dp)); Spacer(Modifier.height(14.dp)); Text(title, fontWeight = FontWeight.Bold, color = Ink); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun PrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, click: () -> Unit) { Button(click, Modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text, fontWeight = FontWeight.Bold) } }
@Composable private fun SecondaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, click: () -> Unit) { Button(click, Modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F1F2), contentColor = Ink)) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text) } }
private fun tabIcon(tab: Tab) = when (tab) { Tab.CAMERA -> Icons.Default.CameraAlt; Tab.PHOTOS -> Icons.Default.PhotoLibrary; Tab.DOWNLOADS -> Icons.Default.Download; Tab.LUT -> Icons.Default.ColorLens; Tab.WATERMARK -> Icons.Default.TextFields; Tab.SETTINGS -> Icons.Default.Settings }
