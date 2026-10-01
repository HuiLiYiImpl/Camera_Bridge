package com.yaoyihan.nikonconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun RemoteParameterPanel(editor: RemoteParameterEditor, fullscreen: Boolean,
    onDismiss: () -> Unit, onApply: (Long) -> Unit) {
    val parameter = editor.parameter ?: return
    val descriptor = editor.descriptor
    var selected by remember(parameter, descriptor) { mutableStateOf(descriptor?.current) }
    val options = remember(parameter, descriptor) { descriptor?.let(parameter::wheelOptions).orEmpty() }
    val editable = descriptor?.writable == true && options.isNotEmpty()
    val shape = RoundedCornerShape(28.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = !editor.saving, dismissOnClickOutside = !editor.saving)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            val visibleRows = if (fullscreen || maxHeight < 520.dp) 3 else 5
            Box(Modifier.matchParentSize().clickable(enabled = !editor.saving,
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
            Surface(Modifier.align(Alignment.Center)
                .widthIn(max = if (fullscreen) 340.dp else 420.dp).fillMaxWidth()
                .background(BridgeBackground, shape).background(BridgeNight.copy(alpha = .55f), shape).bridgeGlass(shape)
                .pointerInput(Unit) { detectTapGestures {} }, shape = shape, color = Color.Transparent) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = BridgeWhite.copy(alpha = .12f)) {
                            Icon(when (parameter) {
                                RemoteParameter.SHUTTER -> Icons.Default.ShutterSpeed
                                RemoteParameter.APERTURE -> Icons.Default.Camera
                                RemoteParameter.ISO -> Icons.Default.Iso
                                RemoteParameter.COMPENSATION -> Icons.Default.Exposure
                                RemoteParameter.WHITE_BALANCE -> Icons.Default.WbSunny
                                RemoteParameter.PICTURE_CONTROL -> Icons.Default.Palette
                            }, null, Modifier.padding(10.dp).size(22.dp), tint = BridgeEmber)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(parameter.title, color = BridgeWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (editor.loading) "正在读取相机" else if (editable) "上下滑动调节" else "当前参数",
                                color = BridgeWhite.copy(alpha = .65f), fontSize = 11.sp)
                        }
                        IconButton(onClick = onDismiss, enabled = !editor.saving, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, "关闭参数面板", tint = BridgeWhite.copy(alpha = .75f), modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (parameter == RemoteParameter.PICTURE_CONTROL) {
                        Text("修改相机 Picture Control，影响机内出片", color = BridgeWhite.copy(alpha = .75f),
                            fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    if (editor.loading) {
                        Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = BridgeEmber, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    } else if (editable) {
                        key(parameter, descriptor) {
                            RemoteParameterWheel(options, descriptor.current, visibleRows,
                                enabled = !editor.busy, label = { parameter.display(descriptor.code, it) },
                                onSelection = { selected = it })
                        }
                    } else if (descriptor != null) {
                        Text(parameter.display(descriptor.code, descriptor.current),
                            Modifier.align(Alignment.CenterHorizontally).padding(vertical = 20.dp),
                            color = BridgeWhite, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (!editor.loading) {
                        editor.message?.let {
                            Text(it, color = BridgeWhite.copy(alpha = .85f), fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (descriptor?.writable == true && options.isEmpty()) {
                            Text("相机未提供可滚动的取值范围", color = BridgeWhite.copy(alpha = .8f), fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("相机当前", color = BridgeWhite.copy(alpha = .6f), fontSize = 10.sp)
                                Text(descriptor?.let { parameter.display(it.code, it.current) } ?: "—",
                                    color = BridgeWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (editable) {
                                Button(onClick = { selected?.let(onApply) },
                                    enabled = !editor.busy && selected?.let { it != descriptor.current && descriptor.allows(it) } == true,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BridgeEmber, contentColor = BridgeNight,
                                        disabledContainerColor = BridgeWhite.copy(alpha = .12f), disabledContentColor = BridgeWhite.copy(alpha = .4f)),
                                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
                                    if (editor.saving) CircularProgressIndicator(Modifier.size(16.dp), color = BridgeWhite, strokeWidth = 2.dp)
                                    else Text("应用", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Finite camera choices, with one selected row at the viewport centre. */
@Composable
private fun RemoteParameterWheel(options: List<Long>, current: Long, rows: Int, enabled: Boolean,
    label: (Long) -> String, onSelection: (Long?) -> Unit) {
    val initialIndex = remember(options, current) { options.indexOf(current).coerceAtLeast(0) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val latestSelection by rememberUpdatedState(onSelection)
    val rowHeight = 44.dp
    val nearest by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - centre) }?.index
        }
    }
    LaunchedEffect(state, options) {
        var lastIndex = initialIndex
        snapshotFlow { state.isScrollInProgress to nearest }.distinctUntilChanged().collect { (moving, index) ->
            // Applying during a drag/fling could send a value different from the row that finally snaps.
            latestSelection(if (moving) null else index?.let(options::get))
            if (!moving && index != null && index != lastIndex) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastIndex = index
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(rowHeight * rows)) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(rowHeight)
            .background(BridgeNight.copy(alpha = .3f), RoundedCornerShape(14.dp))
            .border(1.dp, BridgeEmber.copy(alpha = .4f), RoundedCornerShape(14.dp)))
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), userScrollEnabled = enabled,
            flingBehavior = rememberSnapFlingBehavior(state), contentPadding = PaddingValues(vertical = rowHeight * ((rows - 1) / 2))) {
            items(options.size, key = { options[it] }) { index ->
                val distance = abs(index - (nearest ?: initialIndex))
                Box(Modifier.fillMaxWidth().height(rowHeight)
                    .clickable(enabled = enabled) { scope.launch { state.animateScrollToItem(index) } }
                    .graphicsLayer { alpha = when (distance) { 0 -> 1f; 1 -> .65f; else -> .3f } },
                    contentAlignment = Alignment.Center) {
                    Text(label(options[index]), color = if (distance == 0) BridgeEmber else BridgeWhite,
                        fontSize = if (distance == 0) 25.sp else 19.sp,
                        fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        }
        Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Default.KeyboardArrowRight, null, tint = BridgeEmber, modifier = Modifier.size(18.dp))
            Icon(Icons.Default.KeyboardArrowLeft, null, tint = BridgeEmber, modifier = Modifier.size(18.dp))
        }
    }
}
