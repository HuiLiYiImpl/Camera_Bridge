package com.yaoyihan.nikonconnect

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Date

private val Canvas = Color(0xFFF9F9F8)
private val Ink = Color(0xFF1A1A1A)
private val Muted = Color(0xFF6B727E)
private val Amber = Color(0xFFD4770A)
private val AmberSoft = Color(0xFFF7E8D2)
private val Green = Color(0xFF24984B)
private enum class Tab(val title: String) { CAMERA("\u76f8\u673a"), PHOTOS("\u7167\u7247"), DOWNLOADS("\u4e0b\u8f7d"), SETTINGS("\u8bbe\u7f6e") }

@Composable
fun NikonConnectApp(vm: MainViewModel = viewModel()) {
    val workflow by vm.workflow.collectAsState()
    val busy by vm.isBusy.collectAsState()
    val notice by vm.notice.collectAsState()
    var tab by remember { mutableStateOf(Tab.CAMERA) }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = Canvas, surface = Color.White, primary = Ink, onPrimary = Color.White)) {
        Scaffold(containerColor = Canvas, bottomBar = {
            NavigationBar(containerColor = Color.White) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(tab == item, { tab = item }, { Icon(tabIcon(item), null) }, label = { Text(item.title) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                notice?.let { ActivityPill(it, busy, Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
                AnimatedContent(tab, label = "tab") { screen ->
                    when (screen) {
                        Tab.CAMERA -> CameraScreen(vm, workflow, busy)
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
private fun ActivityPill(text: String, busy: Boolean, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = if (busy) AmberSoft else Color.White, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Amber) else Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Ink, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CameraScreen(vm: MainViewModel, state: Workflow, busy: Boolean) {
    val session by vm.session.collectAsState()
    val photos by vm.photos.collectAsState()
    var brand by remember { mutableIntStateOf(0) }
    val brands = listOf("\u5c3c\u5eb7", "\u7d22\u5c3c", "\u4f73\u80fd", "\u5bcc\u58eb")
    LaunchedEffect(state) { if (state == Workflow.WAITING) while (true) { kotlinx.coroutines.delay(2200); brand = (brand + 1) % brands.size } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f)); LensGlow(state); Spacer(Modifier.height(24.dp))
        Text(if (session == null) "\u8fde\u63a5\u4f60\u7684 ${brands[brand]} \u76f8\u673a" else "${session!!.name} \u5df2\u8fde\u63a5", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(10.dp)); Status(state); Spacer(Modifier.weight(1f))
        if (session == null) {
            PrimaryButton("\u8fde\u63a5\u76f8\u673a", Icons.Default.Wifi, !busy, vm::connect)
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CameraAlt, null, tint = Color.White, modifier = Modifier.background(Green, RoundedCornerShape(10.dp)).padding(8.dp))
                        Spacer(Modifier.width(12.dp)); Column { Text(session!!.name, fontWeight = FontWeight.Bold, color = Ink); Text("\u5df2\u51c6\u5907\u597d\u6d4f\u89c8\u548c\u4e0b\u8f7d", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (photos.isNotEmpty()) Text("\u5df2\u627e\u5230 ${photos.size} \u4e2a\u6587\u4ef6", Modifier.padding(top = 14.dp), color = Muted)
                    Spacer(Modifier.height(14.dp)); SecondaryButton("\u8bfb\u53d6\u7167\u7247", Icons.Default.Refresh, !busy, vm::loadPhotos)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(vm::disconnect, Modifier.fillMaxWidth(), enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFCD2830))) { Text("\u65ad\u5f00\u8fde\u63a5") }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun LensGlow(state: Workflow) {
    val pulse by rememberInfiniteTransition(label = "lens").animateFloat(0.92f, 1.12f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val color = if (state == Workflow.CONNECTED) Green else Amber
    Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(116.dp).scale(if (state == Workflow.CONNECTING || state == Workflow.LOADING) pulse else 1f).background(color.copy(alpha = .12f), CircleShape))
        Surface(Modifier.size(82.dp), shape = CircleShape, color = Ink, shadowElevation = 8.dp) { Icon(Icons.Default.CameraAlt, null, Modifier.padding(21.dp), tint = Color.White) }
    }
}

@Composable
private fun Status(state: Workflow) {
    val (label, color) = when (state) {
        Workflow.WAITING -> "\u7b49\u5f85\u76f8\u673a Wi-Fi" to Color(0xFF3F68B2)
        Workflow.CONNECTING -> "\u6b63\u5728\u8fde\u63a5" to Amber
        Workflow.CONNECTED -> "\u5df2\u8fde\u63a5" to Green
        Workflow.LOADING -> "\u6b63\u5728\u8bfb\u53d6\u7167\u7247" to Amber
        Workflow.DOWNLOADING -> "\u6b63\u5728\u4e0b\u8f7d" to Amber
        Workflow.ERROR -> "\u9700\u8981\u5904\u7406" to Color(0xFFCB2630)
    }
    Surface(shape = RoundedCornerShape(40.dp), color = color.copy(alpha = .11f)) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun GalleryScreen(vm: MainViewModel, busy: Boolean) {
    val photos by vm.photos.collectAsState()
    val hasMore by vm.hasMorePhotos.collectAsState()
    var preview by remember { mutableStateOf<PhotoAsset?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedHandles by remember { mutableStateOf<Set<UInt>>(emptySet()) }
    Column(Modifier.fillMaxSize()) {
        Header("\u7167\u7247", if (photos.isNotEmpty()) "${photos.size} \u4e2a\u6587\u4ef6" else "\u8fde\u63a5\u76f8\u673a\u540e\u5f00\u59cb") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ selectionMode = !selectionMode; if (!selectionMode) selectedHandles = emptySet() }, enabled = !busy) { Text(if (selectionMode) "\u53d6\u6d88" else "\u591a\u9009") }
                if (selectionMode) TextButton({ vm.downloadAll(photos.filter { it.handle in selectedHandles }); selectedHandles = emptySet(); selectionMode = false }, enabled = !busy && selectedHandles.isNotEmpty()) { Text("\u4e0b\u8f7d\uff08${selectedHandles.size}\uff09") }
                IconButton(vm::loadPhotos, enabled = !busy) { Icon(Icons.Default.Refresh, "\u5237\u65b0") }
            }
        }
        if (photos.isEmpty()) EmptyState(Icons.Default.PhotoLibrary, "\u6682\u65e0\u7167\u7247", "\u8fde\u63a5\u76f8\u673a\u5e76\u8bfb\u53d6\u76f8\u518c")
        else LazyVerticalGrid(GridCells.Adaptive(118.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(photos, key = { it.handle.toString() }) { asset ->
                PhotoCard(asset, vm.thumbnails[asset.handle], { vm.loadThumbnail(asset) }, asset.handle in selectedHandles) {
                    if (selectionMode) selectedHandles = if (asset.handle in selectedHandles) selectedHandles - asset.handle else selectedHandles + asset.handle
                    else preview = asset
                }
            }
            if (hasMore) item(span = { GridItemSpan(maxLineSpan) }) { LaunchedEffect(photos.size) { vm.loadMorePhotos() }; LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = Amber) }
        }
    }
    preview?.let { asset -> PreviewDialog(asset, vm.thumbnails[asset.handle], { preview = null }) { vm.download(asset) } }
}

@Composable
private fun PreviewDialog(asset: PhotoAsset, bitmap: Bitmap?, dismiss: () -> Unit, download: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(asset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
            else Text("此文件暂时没有可用预览", color = Muted)
        },
        confirmButton = { TextButton(download) { Text("下载原图") } },
        dismissButton = { TextButton(dismiss) { Text("关闭") } },
    )
}

@Composable
private fun PhotoCard(asset: PhotoAsset, bitmap: Bitmap?, load: () -> Unit, selected: Boolean, click: () -> Unit) {
    LaunchedEffect(asset.handle) { load() }
    Card(Modifier.fillMaxWidth().clickable { click() }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (selected) AmberSoft else Color.White)) {
        Box(Modifier.aspectRatio(.78f)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Box(Modifier.fillMaxSize().background(AmberSoft), contentAlignment = Alignment.Center) { Icon(Icons.Default.Image, null, tint = Amber, modifier = Modifier.size(32.dp)) }
            Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), shape = RoundedCornerShape(8.dp), color = Ink.copy(alpha = .76f)) { Text(asset.type, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall) }
            if (selected) Surface(Modifier.align(Alignment.TopStart).padding(7.dp), shape = CircleShape, color = Green) { Icon(Icons.Default.Check, "已选择", tint = Color.White, modifier = Modifier.padding(5.dp).size(18.dp)) }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.White.copy(alpha = .94f)).padding(8.dp)) { Text(asset.name, color = Ink, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(asset.size.prettySize(), color = Muted, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun DownloadsScreen(vm: MainViewModel) {
    val rows by vm.downloads.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header("\u4e0b\u8f7d", "\u5df2\u4fdd\u5b58\u5230\u7cfb\u7edf\u76f8\u518c") {}
        if (rows.isEmpty()) EmptyState(Icons.Default.CloudDownload, "\u6682\u65e0\u4e0b\u8f7d\u8bb0\u5f55", "\u8f7b\u89e6\u7167\u7247\u5373\u53ef\u4fdd\u5b58\u5230\u624b\u673a")
        else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows) { row -> Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Download, null, tint = Amber, modifier = Modifier.size(25.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(row.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${row.size.prettySize()} - ${Date(row.completedAt).prettyDate()}", color = Muted, style = MaterialTheme.typography.bodySmall) } } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    var host by remember(config.host) { mutableStateOf(config.host) }
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("\u8bbe\u7f6e", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("\u5c3c\u5eb7 PTP/IP", color = Muted, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(host, { host = it; vm.updateConfig { c -> c.copy(host = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("\u76f8\u673a IP \u5730\u5740") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(port, { value -> port = value.filter(Char::isDigit); value.toIntOrNull()?.let { number -> vm.updateConfig { c -> c.copy(port = number) } } }, Modifier.fillMaxWidth(), label = { Text("\u7aef\u53e3") }, singleLine = true)
                    Text("\u5c3c\u5eb7\u9ed8\u8ba4\uff1a192.168.1.1:15740", Modifier.padding(top = 10.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                } }
            }
            item {
                PreferenceSwitch("\u4e0b\u8f7d\u540e\u4fdd\u5b58\u5230\u7cfb\u7edf\u76f8\u518c", "\u56fe\u7247 / Nikon \u8fde\u63a5", config.autoExport) { vm.updateConfig { c -> c.copy(autoExport = it) } }
                PreferenceSwitch("\u4f18\u5148\u663e\u793a JPEG", "\u76f8\u673a\u540c\u65f6\u5305\u542b RAW \u4e0e JPEG \u65f6\u4f7f\u7528", config.jpegFirst) { vm.updateConfig { c -> c.copy(jpegFirst = it) } }
            }
            item {
                Text("\u5c0f\u7c73 14 \u4f7f\u7528\u63d0\u793a", color = Muted, style = MaterialTheme.typography.labelLarge)
                Card(colors = CardDefaults.cardColors(containerColor = AmberSoft), shape = RoundedCornerShape(18.dp)) { Text("\u8bf7\u5148\u5728 Wi-Fi \u8bbe\u7f6e\u4e2d\u8fde\u63a5\u76f8\u673a\u70ed\u70b9\u3002\u82e5 HyperOS \u63d0\u793a\u8be5\u7f51\u7edc\u65e0\u4e92\u8054\u7f51\uff0c\u8bf7\u4fdd\u6301\u8fde\u63a5\uff0c\u8fd4\u56de\u5e94\u7528\u540e\u70b9\u51fb\u300c\u8fde\u63a5\u76f8\u673a\u300d\u3002", Modifier.padding(16.dp), color = Ink, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) }; Switch(checked, change) } }
}
@Composable private fun Header(title: String, subtitle: String, action: @Composable () -> Unit) { Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) }; action() } }
@Composable private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) { Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(icon, null, tint = Amber, modifier = Modifier.size(50.dp)); Spacer(Modifier.height(14.dp)); Text(title, fontWeight = FontWeight.Bold, color = Ink); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun PrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, click: () -> Unit) { Button(click, Modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text, fontWeight = FontWeight.Bold) } }
@Composable private fun SecondaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, click: () -> Unit) { Button(click, Modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F1F2), contentColor = Ink)) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text) } }
private fun tabIcon(tab: Tab) = when (tab) { Tab.CAMERA -> Icons.Default.CameraAlt; Tab.PHOTOS -> Icons.Default.PhotoLibrary; Tab.DOWNLOADS -> Icons.Default.Download; Tab.SETTINGS -> Icons.Default.Settings }
