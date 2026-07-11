package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

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
private enum class Tab(val title: String) { CAMERA("\u76f8\u673a"), PHOTOS("\u7167\u7247"), DOWNLOADS("\u4e0b\u8f7d"), SETTINGS("\u8bbe\u7f6e") }
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
    val session by vm.session.collectAsState()
    var tab by remember { mutableStateOf(Tab.CAMERA) }
    val landing = tab == Tab.CAMERA && session == null
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = BridgeNight, surface = Color(0xFF181015), primary = BridgeEmber, onPrimary = BridgeWhite)) {
        Scaffold(containerColor = BridgeNight, bottomBar = {
            if (!landing) {
                BridgeNavigation(tab) { tab = it }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!landing) notice?.let { ActivityPill(it, busy, Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
                AnimatedContent(tab, label = "tab") { screen ->
                    when (screen) {
                        Tab.CAMERA -> CameraScreen(vm, workflow, busy, notice) { tab = Tab.SETTINGS }
                        Tab.PHOTOS -> GalleryScreen(vm, busy)
                        Tab.DOWNLOADS -> DownloadsScreen(vm)
                        Tab.SETTINGS -> SettingsScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeNavigation(selected: Tab, select: (Tab) -> Unit) {
    Box(Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(30.dp), color = Color(0xFF1D191E), shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .06f))) {
            Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Tab.entries.forEach { item ->
                    val active = item == selected
                    if (active) {
                        Surface(shape = RoundedCornerShape(24.dp), color = BridgeWhite) {
                            Row(Modifier.height(48.dp).animateContentSize().clickable { select(item) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(tabIcon(item), null, tint = BridgeNight, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(item.title, color = BridgeNight, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else IconButton({ select(item) }, Modifier.size(48.dp)) { Icon(tabIcon(item), item.title, tint = BridgeWhite.copy(alpha = .62f), modifier = Modifier.size(21.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ActivityPill(text: String, busy: Boolean, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = if (busy) BridgeEmber.copy(alpha = .16f) else BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .1f))) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BridgeEmber) else Icon(Icons.Default.CheckCircle, null, tint = BridgeEmber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = BridgeWhite, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CameraScreen(vm: MainViewModel, state: Workflow, busy: Boolean, notice: String?, openSettings: () -> Unit) {
    val session by vm.session.collectAsState()
    val photos by vm.photos.collectAsState()
    val config by vm.config.collectAsState()
    if (session == null) ConnectionLanding(state, busy, notice, { vm.connect(true) }, openSettings, config.lastSsid)
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
                if (photos.isNotEmpty()) Text("\u5df2\u627e\u5230 ${photos.size} \u4e2a\u6587\u4ef6", Modifier.padding(top = 14.dp), color = BridgeWhite.copy(alpha = .7f))
                Spacer(Modifier.height(14.dp)); Button(vm::loadPhotos, Modifier.fillMaxWidth(), enabled = !busy, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("\u8bfb\u53d6\u7167\u7247") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(vm::disconnect, Modifier.fillMaxWidth(), enabled = !busy, shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeWhite.copy(alpha = .08f), contentColor = BridgeWhite)) { Text("\u65ad\u5f00\u8fde\u63a5") }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun ConnectionLanding(state: Workflow, busy: Boolean, notice: String?, connect: () -> Unit, openSettings: () -> Unit, lastSsid: String) {
    val pulse by rememberInfiniteTransition(label = "bridge").animateFloat(0.94f, 1.08f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "signal")
    val status = when (state) {
        Workflow.CONNECTING -> "\u6b63\u5728\u5efa\u7acb\u5b89\u5168\u6865\u63a5"
        Workflow.ERROR -> notice ?: "\u8bf7\u68c0\u67e5\u76f8\u673a Wi-Fi"
        else -> if (lastSsid.isNotBlank()) "\u4e0a\u6b21\u8fde\u63a5\uff1a$lastSsid" else "\u7b49\u5f85\u76f8\u673a Wi-Fi"
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
            Text(if (lastSsid.isNotBlank()) "\u70b9\u51fb\u4e0b\u65b9\u6309\u94ae\uff0c\u82e5\u5df2\u8fde\u63a5\u76f8\u673a Wi-Fi \u5c06\u81ea\u52a8\u5efa\u7acb\u6865\u63a5\u3002" else "\u5148\u5728\u7cfb\u7edf Wi-Fi \u4e2d\u8fde\u63a5\u76f8\u673a\u70ed\u70b9\uff0c\nCamera_Bridge \u4f1a\u4e3a\u4f60\u5efa\u7acb\u5b89\u5168\u7684\u7167\u7247\u4f20\u8f93\u6865\u63a5\u3002", color = BridgeWhite.copy(alpha = .68f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = BridgeWhite.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Wifi, null, tint = BridgeEmber, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text(status, color = BridgeWhite.copy(alpha = .88f), style = MaterialTheme.typography.labelMedium) }
            }
            Spacer(Modifier.weight(1f))
            ConnectionLightField(pulse)
            Spacer(Modifier.weight(1f))
            Button(connect, Modifier.fillMaxWidth().height(58.dp), enabled = !busy, shape = RoundedCornerShape(30.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeWhite, disabledContainerColor = BridgeCopper, disabledContentColor = BridgeWhite.copy(alpha = .75f))) {
                Icon(if (busy) Icons.Default.Sync else Icons.Default.Wifi, null); Spacer(Modifier.width(10.dp)); Text(if (busy) "\u6b63\u5728\u5efa\u7acb\u8fde\u63a5" else "\u5f00\u59cb\u5efa\u7acb\u8fde\u63a5", fontWeight = FontWeight.Bold)
            }
            Text("\u8fde\u4e0d\u4e0a\u65f6\u4f1a\u81ea\u52a8\u8df3\u8f6c Wi-Fi \u8bbe\u7f6e", Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), color = BridgeWhite.copy(alpha = .48f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    var preview by remember { mutableStateOf<PhotoAsset?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(PhotoFilter.ALL) }
    val scope = rememberCoroutineScope()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedHandles by remember { mutableStateOf<Set<UInt>>(emptySet()) }
    val visiblePhotos = photos.filter { it.matches(filter) }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("\u76f8\u518c", if (photos.isNotEmpty()) "${visiblePhotos.size} \u4e2a\u6587\u4ef6" else "\u8fde\u63a5\u76f8\u673a\u540e\u5f00\u59cb") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ selectionMode = !selectionMode; if (!selectionMode) selectedHandles = emptySet() }, enabled = !busy) { Text(if (selectionMode) "\u53d6\u6d88" else "\u591a\u9009") }
                if (selectionMode) TextButton({ vm.downloadAll(photos.filter { it.handle in selectedHandles }); selectedHandles = emptySet(); selectionMode = false }, enabled = !busy && selectedHandles.isNotEmpty()) { Text("\u4e0b\u8f7d\uff08${selectedHandles.size}\uff09") }
                IconButton(vm::loadPhotos, enabled = !busy) { Icon(Icons.Default.Refresh, "\u5237\u65b0") }
            }
        }
        FilterBar(filter) { filter = it }
        if (photos.isEmpty()) BridgeEmptyState(Icons.Default.PhotoLibrary, "\u6682\u65e0\u7167\u7247", "\u8fde\u63a5\u76f8\u673a\u5e76\u8bfb\u53d6\u76f8\u518c")
        else if (visiblePhotos.isEmpty()) BridgeEmptyState(Icons.Default.FilterAlt, "\u6b64\u5206\u7c7b\u6682\u65e0\u6587\u4ef6", "\u8bd5\u8bd5\u5176\u4ed6\u5206\u7c7b")
        else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(148.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 94.dp), verticalItemSpacing = 10.dp, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visiblePhotos, key = { it.handle.toString() }) { asset ->
                PhotoCard(asset, vm.thumbnails[asset.handle], { vm.loadThumbnail(asset) }, asset.handle in selectedHandles) {
                    if (selectionMode) selectedHandles = if (asset.handle in selectedHandles) selectedHandles - asset.handle else selectedHandles + asset.handle
                    else { vm.loadThumbnail(asset); previewBitmap = null; previewLoading = false; preview = asset }
                }
            }
            if (hasMore && filter == PhotoFilter.ALL) item { LaunchedEffect(photos.size) { vm.loadMorePhotos() }; CircularProgressIndicator(Modifier.padding(20.dp), color = BridgeEmber) }
        }
    }
    preview?.let { asset ->
        PreviewDialog(asset.name, asset.size, vm.thumbnails[asset.handle], previewBitmap, previewLoading, { preview = null }, {
            previewBitmap = null
            previewLoading = true
            scope.launch {
                val bitmap = vm.loadOriginalPreview(asset)
                if (preview?.handle == asset.handle) { previewBitmap = bitmap; previewLoading = false }
            }
        })
    }
}

@Composable
private fun PreviewDialog(name: String, size: Long, thumbnail: Bitmap?, bitmap: Bitmap?, loading: Boolean, dismiss: () -> Unit, loadOriginal: (() -> Unit)? = null, download: (() -> Unit)? = null) {
    var rotation by remember(name) { mutableIntStateOf(0) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.9f), shape = RoundedCornerShape(28.dp), color = Color(0xFF171015), border = androidx.compose.foundation.BorderStroke(1.dp, BridgeWhite.copy(alpha = .12f))) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(name, color = BridgeWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(size.prettySize(), color = BridgeWhite.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall) }
                    IconButton({ rotation = (rotation + 90) % 360 }) { Icon(Icons.Default.RotateRight, "顺时针旋转 90 度", tint = BridgeWhite) }
                    IconButton(dismiss) { Icon(Icons.Default.Close, "关闭", tint = BridgeWhite) }
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).background(BridgeNight, RoundedCornerShape(20.dp)).clipToBounds(), contentAlignment = Alignment.Center) {
                    (bitmap ?: thumbnail)?.let { ZoomableImage(it, rotation) } ?: Text("正在加载缩略图", color = BridgeWhite)
                    if (loading) CircularProgressIndicator(color = BridgeEmber)
                }
                if (loadOriginal != null || download != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (download != null) TextButton(download) { Text("下载原图", color = BridgeWhite) }
                    if (loadOriginal != null) Button(loadOriginal, enabled = !loading, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight)) { Text(if (bitmap == null) "查看原图" else "重新加载") }
                }
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
private fun DownloadsScreen(vm: MainViewModel) {
    val rows by vm.downloads.collectAsState()
    var preview by remember { mutableStateOf<DownloadRecord?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filter by remember { mutableStateOf(PhotoFilter.ALL) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val visibleRows = rows.filter { it.matches(filter) }
    LaunchedEffect(preview?.uri) {
        val uri = preview?.uri ?: return@LaunchedEffect
        previewBitmap = null; previewLoading = true
        previewBitmap = vm.loadLocalOriginal(uri); previewLoading = false
    }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        BridgePageLabel("\u4e0b\u8f7d", "\u5df2\u4fdd\u5b58 ${rows.size} \u4e2a\u6587\u4ef6") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ selectionMode = !selectionMode; if (!selectionMode) selectedUris = emptySet() }) { Text(if (selectionMode) "\u53d6\u6d88" else "\u591a\u9009", color = BridgeWhite) }
                if (selectionMode) {
                    TextButton({ shareUris(context, visibleRows.filter { it.uri in selectedUris }.map { it.uri }); selectedUris = emptySet(); selectionMode = false }, enabled = selectedUris.isNotEmpty()) { Text("\u5206\u4eab\uff08${selectedUris.size}\uff09", color = if (selectedUris.isNotEmpty()) BridgeEmber else BridgeWhite.copy(alpha = .4f)) }
                    TextButton({ showDeleteConfirm = true }, enabled = selectedUris.isNotEmpty()) { Text("\u5220\u9664\uff08${selectedUris.size}\uff09", color = if (selectedUris.isNotEmpty()) Color(0xFFE57373) else BridgeWhite.copy(alpha = .4f)) }
                }
            }
        }
        FilterBar(filter) { filter = it; selectedUris = emptySet() }
        if (rows.isEmpty()) BridgeEmptyState(Icons.Default.CloudDownload, "\u6682\u65e0\u4e0b\u8f7d\u8bb0\u5f55", "\u5728\u76f8\u518c\u4e2d\u9009\u62e9\u7167\u7247\u5373\u53ef\u4fdd\u5b58\u5230\u624b\u673a")
        else if (visibleRows.isEmpty()) BridgeEmptyState(Icons.Default.FilterAlt, "\u6b64\u5206\u7c7b\u6682\u65e0\u6587\u4ef6", "\u8bd5\u8bd5\u5176\u4ed6\u5206\u7c7b")
        else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(148.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 94.dp), verticalItemSpacing = 10.dp, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visibleRows, key = { it.uri }) { row ->
                DownloadCard(row, row.uri in selectedUris) {
                    if (selectionMode) selectedUris = if (row.uri in selectedUris) selectedUris - row.uri else selectedUris + row.uri
                    else preview = row
                }
            }
        }
    }
    preview?.let { row ->
        PreviewDialog(row.name, row.size, null, previewBitmap, previewLoading, { preview = null }, null)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    var host by remember(config.host) { mutableStateOf(config.host) }
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    Column(Modifier.fillMaxSize().background(BridgeNight)) {
        TopAppBar(title = { Text("\u8bbe\u7f6e", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = BridgeNight, titleContentColor = BridgeWhite))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
private fun tabIcon(tab: Tab) = when (tab) { Tab.CAMERA -> Icons.Default.CameraAlt; Tab.PHOTOS -> Icons.Default.PhotoLibrary; Tab.DOWNLOADS -> Icons.Default.Download; Tab.SETTINGS -> Icons.Default.Settings }
