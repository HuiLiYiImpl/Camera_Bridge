package com.yaoyihan.nikonconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

@Composable
internal fun RemoteColorControls(vm: MainViewModel, fullscreen: Boolean) {
    val preview by vm.remoteLiveView.collectAsState()
    val color by vm.remotePreviewColor.collectAsState()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(BridgeSurface.copy(alpha = .85f)).bridgeGlass(RoundedCornerShape(0.dp))) {
        RemoteColorEntry(Icons.Default.Palette, "机内滤镜", preview.parameters.pictureControl ?: "—", Modifier.weight(1f)) {
            vm.openRemoteParameter(RemoteParameter.PICTURE_CONTROL)
        }
        RemoteColorEntry(Icons.Default.ColorLens, "预览预设 · 仅预览",
            if (color.loading) "加载中…" else color.entry?.name ?: "原始画面", Modifier.weight(1f)) { pickerOpen = true }
    }
    if (pickerOpen) RemotePreviewPresetPanel(vm, fullscreen) { pickerOpen = false }
}

@Composable
private fun RemoteColorEntry(icon: ImageVector, title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 44.dp).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(17.dp), tint = BridgeEmber)
        Column(Modifier.weight(1f).padding(start = 7.dp)) {
            Text(title, color = BridgeWhite.copy(alpha = .6f), fontSize = 9.sp, maxLines = 1)
            Text(value, color = BridgeWhite, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(14.dp), tint = BridgeWhite.copy(alpha = .45f))
    }
}

@Composable
private fun RemotePreviewPresetPanel(vm: MainViewModel, fullscreen: Boolean, onDismiss: () -> Unit) {
    val luts by vm.luts.collectAsState()
    val color by vm.remotePreviewColor.collectAsState()
    val shape = RoundedCornerShape(28.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            val compact = fullscreen || maxHeight < 520.dp
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onDismiss))
            Surface(Modifier.align(Alignment.Center).widthIn(max = 420.dp).fillMaxWidth()
                .background(BridgeBackground, shape).background(BridgeNight.copy(alpha = .55f), shape).bridgeGlass(shape)
                .pointerInput(Unit) { detectTapGestures {} }, shape = shape, color = Color.Transparent) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("预览预设", color = BridgeWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text("仅改变监视画面，不影响照片", color = BridgeWhite.copy(alpha = .75f), fontSize = 11.sp)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭预览预设", tint = BridgeWhite) }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (compact) 150.dp else 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item(key = "original") {
                            RemotePreviewPresetRow("原始画面", "保留相机传来的色彩", color.entry == null, false) {
                                vm.selectRemotePreviewLut(null)
                            }
                        }
                        items(luts, key = { "preset:${it.id}" }) { entry ->
                            RemotePreviewPresetRow(entry.name, entry.category.title,
                                color.entry?.id == entry.id, color.loading && color.entry?.id == entry.id) {
                                vm.selectRemotePreviewLut(entry)
                            }
                        }
                    }
                    if (luts.isEmpty()) Text("先到色彩预设页导入预设，即可在这里预览", color = BridgeWhite.copy(alpha = .7f),
                        fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                    color.message?.let { Text(it, color = BridgeWhite, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    if (color.entry != null) {
                        Text("预览强度 ${(color.intensity * 100).roundToInt()}%", color = BridgeWhite.copy(alpha = .8f),
                            fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                        Slider(value = color.intensity, onValueChange = vm::setRemotePreviewIntensity,
                            enabled = !color.loading, modifier = Modifier.fillMaxWidth().height(32.dp),
                            colors = SliderDefaults.colors(thumbColor = BridgeEmber, activeTrackColor = BridgeEmber,
                                inactiveTrackColor = BridgeWhite.copy(alpha = .2f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun RemotePreviewPresetRow(name: String, detail: String, selected: Boolean, loading: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp),
        color = if (selected) BridgeEmber.copy(alpha = .2f) else BridgeWhite.copy(alpha = .08f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(name, color = if (selected) BridgeEmber else BridgeWhite, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = BridgeWhite.copy(alpha = .6f), fontSize = 10.sp)
            }
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = BridgeEmber, strokeWidth = 2.dp)
            else if (selected) Icon(Icons.Default.Check, "已选择", Modifier.size(18.dp), tint = BridgeEmber)
        }
    }
}
