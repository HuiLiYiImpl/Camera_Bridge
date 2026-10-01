package com.yaoyihan.nikonconnect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaoyihan.nikonconnect.lut.RemoteLiveLutSurface

@Composable
internal fun RemoteCaptureScreen(vm: MainViewModel, openConnection: () -> Unit,
    fullscreen: Boolean, onFullscreen: () -> Unit, onExitRemote: () -> Unit,
    delaySeconds: Int, onDelayChange: (Int) -> Unit) {
    val session by vm.session.collectAsState()
    val supported by vm.remoteSupported.collectAsState()
    val state by vm.remoteCapture.collectAsState()
    val busy by vm.isBusy.collectAsState()
    val tasks by vm.downloadTasks.collectAsState()
    val editor by vm.remoteParameterEditor.collectAsState()
    val focus by vm.remoteFocus.collectAsState()
    val wifiConnected = session?.transport == ConnectionTransport.WIFI
    val transferring = tasks.any { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING || it.status == DownloadTaskStatus.QUEUED }
    val available = wifiConnected && supported && !busy && !transferring && !state.inProgress && !editor.busy && !focus.inProgress
    RemoteParameterPanel(editor, fullscreen, vm::closeRemoteParameterEditor, vm::setRemoteParameter)
    if (fullscreen) {
        RemoteLandscapeScreen(vm, state, available, onFullscreen, onExitRemote, delaySeconds, onDelayChange)
        return
    }
    val subtitle = when {
        session == null -> "连接相机 Wi-Fi 后即可拍摄"
        !wifiConnected -> "首版遥控支持 Wi-Fi，请切换连接方式"
        !supported -> "当前连接未提供遥控拍摄能力"
        state.inProgress -> state.message
        state.phase != RemoteCapturePhase.IDLE -> state.message
        transferring -> "正在传输文件，完成后即可拍摄"
        busy -> "正在读取相机，请稍候"
        else -> state.message
    }

    var timerOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 82.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("遥控拍摄", color = BridgeWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Icon(if (wifiConnected) Icons.Default.Wifi else Icons.Default.Link, null,
                Modifier.size(14.dp), tint = BridgeWhite.copy(alpha = .75f))
            Spacer(Modifier.width(5.dp))
            Text(session?.name ?: "尚未连接", Modifier.weight(1f), color = BridgeWhite.copy(alpha = .75f),
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onExitRemote) {
                Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = BridgeWhite)
                Spacer(Modifier.width(4.dp))
                Text("退出", color = BridgeWhite, fontSize = 12.sp)
            }
            if (!wifiConnected || !supported) {
                IconButton(onClick = openConnection, enabled = !state.inProgress && !busy) {
                    Icon(Icons.Default.Link, "查看相机连接", tint = BridgeWhite)
                }
            }
        }
        RemoteViewfinder(vm, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            RemoteGlassAction(
                icon = if (state.phase == RemoteCapturePhase.COUNTDOWN || state.phase == RemoteCapturePhase.PREPARING) Icons.Default.Close else Icons.Default.Timer,
                label = if (state.phase == RemoteCapturePhase.COUNTDOWN || state.phase == RemoteCapturePhase.PREPARING) "取消" else if (delaySeconds == 0) "延时" else "${delaySeconds}秒",
                enabled = state.phase != RemoteCapturePhase.WAITING,
            ) {
                if (state.phase == RemoteCapturePhase.COUNTDOWN || state.phase == RemoteCapturePhase.PREPARING) vm.cancelPendingRemoteCapture()
                else timerOpen = true
            }
            RemoteShutter(state, available) { vm.startRemoteCapture(delaySeconds) }
            RemoteGlassAction(Icons.Default.OpenInFull, "全屏", enabled = !editor.busy, onClick = onFullscreen)
        }
        Text(subtitle, Modifier.fillMaxWidth(), color = BridgeWhite.copy(alpha = .7f), fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (timerOpen) RemoteTimerDialog(state, onDelayChange) { timerOpen = false }
}

@Composable
private fun RemoteGlassAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(Modifier.size(48.dp).bridgeGlass(RoundedCornerShape(16.dp)), color = Color.Transparent, shape = RoundedCornerShape(16.dp)) {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(icon, label, Modifier.size(23.dp), tint = BridgeWhite.copy(alpha = if (enabled) .95f else .35f))
            }
        }
        Text(label, color = BridgeWhite.copy(alpha = .75f), fontSize = 11.sp)
    }
}

@Composable
private fun RemoteShutter(state: RemoteCaptureState, available: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = available,
        modifier = Modifier.size(76.dp).background(Color.White.copy(alpha = .08f), CircleShape)
            .border(2.dp, Color.White.copy(alpha = if (available || state.inProgress) .95f else .35f), CircleShape)
            .semantics { contentDescription = "拍摄" }) {
        when (state.phase) {
            RemoteCapturePhase.COUNTDOWN -> Text(state.secondsRemaining.toString(), fontSize = 32.sp, color = Color.White)
            RemoteCapturePhase.PREPARING, RemoteCapturePhase.WAITING -> CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
            else -> Box(Modifier.size(60.dp).background(Color.White.copy(alpha = if (available) .95f else .3f), CircleShape))
        }
    }
}

@Composable
private fun RemoteTimerDialog(state: RemoteCaptureState, onDelayChange: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("拍摄延时") },
        text = { Column { listOf(0, 2, 5, 10).forEach { seconds ->
            TextButton(onClick = { onDelayChange(seconds); onDismiss() }, enabled = !state.inProgress) {
                Text(if (seconds == 0) "立即拍摄" else "${seconds}秒后拍摄")
            }
        } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

/** Frame updates are collected here so the shutter controls do not collect every bitmap. */
@Composable
private fun RemoteViewfinder(vm: MainViewModel, modifier: Modifier, showParameters: Boolean = true) {
    val preview by vm.remoteLiveView.collectAsState()
    val focus by vm.remoteFocus.collectAsState()
    val latestPreview by rememberUpdatedState(preview)
    val colorPreview by vm.remotePreviewColor.collectAsState()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val parameters = preview.parameters
    Surface(if (showParameters) modifier.bridgeGlass(RoundedCornerShape(24.dp)) else modifier, color = Color.Transparent, shape = if (showParameters) RoundedCornerShape(24.dp) else RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).onSizeChanged { viewportSize = it }
                .pointerInput(vm, viewportSize) {
                    detectTapGestures { offset ->
                        val current = latestPreview
                        current.frame?.let { bitmap ->
                            if (current.streaming) focusPointFromTap(offset.x, offset.y, viewportSize.width, viewportSize.height,
                                bitmap.width, bitmap.height)?.let { vm.focusRemoteAt(it, current.focusGeometry) }
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                preview.frame?.let { frame ->
                    val lut = colorPreview.lut
                    if (lut != null) RemoteLiveLutSurface(frame, lut, colorPreview.intensity, Modifier.fillMaxSize())
                    else Image(frame.asImageBitmap(), "相机实时画面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                if (colorPreview.lut != null || colorPreview.loading) {
                    Text(if (colorPreview.loading) "加载预览预设…" else "仅预览 · ${colorPreview.entry?.name.orEmpty()}",
                        Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp, vertical = 5.dp).pointerInput(Unit) { detectTapGestures {} },
                        color = BridgeWhite, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (preview.streaming) focus.point?.let { point ->
                    val frame = preview.frame
                    val color = when (focus.phase) {
                        RemoteFocusPhase.FOCUSED -> Color(0xFF73E5A0)
                        RemoteFocusPhase.FAILED -> Color(0xFFFF7979)
                        else -> Color(0xFFFFD166)
                    }
                    if (frame != null) Canvas(Modifier.fillMaxSize()) {
                        focusImageBounds(size.width.toInt(), size.height.toInt(), frame.width, frame.height)?.let { bounds ->
                            val side = 46.dp.toPx()
                            val center = Offset(bounds.left + point.x * bounds.width, bounds.top + point.y * bounds.height)
                            clipRect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height) {
                                drawRect(color, center - Offset(side / 2, side / 2), Size(side, side), style = Stroke(2.dp.toPx()))
                            }
                        }
                    }
                    Text(focus.message, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 34.dp)
                        .background(Color.Black.copy(alpha = .65f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        color = color, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!preview.streaming) {
                    Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (preview.loading) CircularProgressIndicator(Modifier.size(28.dp), color = BridgeEmber, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Videocam, null, Modifier.size(36.dp), tint = BridgeWhite.copy(alpha = .65f))
                        Spacer(Modifier.height(12.dp))
                        Text(preview.message, color = BridgeWhite, fontSize = 13.sp)
                        if (preview.failed) TextButton(onClick = vm::retryRemotePreview) { Text("重试取景", color = BridgeEmber) }
                    }
                }
                if (preview.streaming) {
                    Row(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .5f), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 5.dp)
                        .pointerInput(Unit) { detectTapGestures {} },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Circle, null, Modifier.size(7.dp), tint = BridgeEmber)
                        Spacer(Modifier.width(6.dp))
                        Text("实时", color = Color.White, fontSize = 11.sp)
                    }
                }
                if (showParameters) {
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp).pointerInput(Unit) { detectTapGestures {} }, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(listOfNotNull(parameters.mode, parameters.focalLength).joinToString(" · ").ifBlank { "实时取景" },
                            color = Color.White.copy(alpha = .85f), fontSize = 11.sp)
                        Text(parameters.battery?.let { "$it%" } ?: "—", color = Color.White.copy(alpha = .85f), fontSize = 11.sp)
                    }
                }
            }
            if (showParameters) {
            RemoteParameterStrip(vm, Modifier.fillMaxWidth())
            RemoteColorControls(vm, fullscreen = false)
            }
        }
    }
}

@Composable
private fun RemoteParameterValue(label: String, value: String, modifier: Modifier, active: Boolean, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = BridgeWhite.copy(alpha = .6f), fontSize = 10.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = if (active) BridgeEmber else BridgeWhite, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.UnfoldMore, null, Modifier.padding(start = 2.dp).size(12.dp),
                tint = if (active) BridgeEmber else BridgeWhite.copy(alpha = .4f))
        }
    }
}

@Composable
private fun RemoteParameterStrip(vm: MainViewModel, modifier: Modifier = Modifier) {
    val preview by vm.remoteLiveView.collectAsState()
    val editor by vm.remoteParameterEditor.collectAsState()
    val parameters = preview.parameters
    Row(modifier.background(BridgeSurface.copy(alpha = .85f)).bridgeGlass(RoundedCornerShape(0.dp))) {
        RemoteParameterValue("快门", parameters.shutter ?: "—", Modifier.weight(1f), editor.parameter == RemoteParameter.SHUTTER) { vm.openRemoteParameter(RemoteParameter.SHUTTER) }
        RemoteParameterValue("光圈", parameters.aperture ?: "—", Modifier.weight(1f), editor.parameter == RemoteParameter.APERTURE) { vm.openRemoteParameter(RemoteParameter.APERTURE) }
        RemoteParameterValue("ISO", parameters.iso ?: "—", Modifier.weight(1f), editor.parameter == RemoteParameter.ISO) { vm.openRemoteParameter(RemoteParameter.ISO) }
        RemoteParameterValue("曝光", parameters.compensation?.removeSuffix(" EV") ?: "—", Modifier.weight(1f), editor.parameter == RemoteParameter.COMPENSATION) { vm.openRemoteParameter(RemoteParameter.COMPENSATION) }
        RemoteParameterValue("白平衡", parameters.whiteBalance ?: "—", Modifier.weight(1f), editor.parameter == RemoteParameter.WHITE_BALANCE) { vm.openRemoteParameter(RemoteParameter.WHITE_BALANCE) }
    }
}

@Composable
private fun RemoteLandscapeScreen(vm: MainViewModel, state: RemoteCaptureState, available: Boolean,
    onExit: () -> Unit, onExitRemote: () -> Unit, delaySeconds: Int, onDelayChange: (Int) -> Unit) {
    val preview by vm.remoteLiveView.collectAsState()
    var timerOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().background(Color.Black).displayCutoutPadding()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            RemoteViewfinder(vm, Modifier.fillMaxSize(), showParameters = false)
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .5f))
                .pointerInput(Unit) { detectTapGestures {} },
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) { Icon(Icons.Default.FullscreenExit, "退出横屏", tint = Color.White) }
                Text(preview.parameters.mode ?: "—", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(if (state.phase == RemoteCapturePhase.IDLE) preview.message else state.message,
                    Modifier.weight(1f), color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preview.parameters.battery?.let { "$it%" } ?: "—", color = Color.White, modifier = Modifier.padding(12.dp))
                TextButton(onClick = onExitRemote) { Text("退出遥控", color = Color.White, fontSize = 12.sp) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                RemoteParameterStrip(vm, Modifier.fillMaxWidth())
                RemoteColorControls(vm, fullscreen = true)
            }
        }
        Column(Modifier.width(104.dp).fillMaxHeight().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { timerOpen = true }, enabled = !state.inProgress) {
                Text(if (delaySeconds == 0) "立即" else "${delaySeconds}秒", color = Color.White)
            }
            RemoteShutter(state, available) { vm.startRemoteCapture(delaySeconds) }
            if (state.phase == RemoteCapturePhase.COUNTDOWN || state.phase == RemoteCapturePhase.PREPARING) {
                TextButton(onClick = vm::cancelPendingRemoteCapture) { Text("取消", color = Color.White) }
            } else IconButton(onClick = onExit) { Icon(Icons.Default.ScreenRotation, "返回竖屏", tint = Color.White) }
        }
    }
    if (timerOpen) RemoteTimerDialog(state, onDelayChange) { timerOpen = false }
}
