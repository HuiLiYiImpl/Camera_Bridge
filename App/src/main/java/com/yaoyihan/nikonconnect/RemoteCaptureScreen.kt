package com.yaoyihan.nikonconnect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RemoteCaptureScreen(vm: MainViewModel, openConnection: () -> Unit) {
    val session by vm.session.collectAsState()
    val supported by vm.remoteSupported.collectAsState()
    val state by vm.remoteCapture.collectAsState()
    val busy by vm.isBusy.collectAsState()
    val tasks by vm.downloadTasks.collectAsState()
    var delaySeconds by rememberSaveable { mutableIntStateOf(0) }
    val wifiConnected = session?.transport == ConnectionTransport.WIFI
    val transferring = tasks.any { it.status == DownloadTaskStatus.DOWNLOADING || it.status == DownloadTaskStatus.CANCELLING || it.status == DownloadTaskStatus.QUEUED }
    val available = wifiConnected && supported && !busy && !transferring && !state.inProgress
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("遥控拍摄", color = BridgeWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("让手机成为你的快门", color = BridgeWhite.copy(alpha = .65f), fontSize = 14.sp)
        }
        Surface(Modifier.fillMaxWidth().bridgeGlass(RoundedCornerShape(24.dp)), color = Color.Transparent, shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (wifiConnected) Icons.Default.Wifi else Icons.Default.Link, null, tint = BridgeEmber)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(session?.name ?: "尚未连接相机", color = BridgeWhite, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = BridgeWhite.copy(alpha = .75f), fontSize = 14.sp)
                }
            }
        }
        Surface(Modifier.fillMaxWidth().bridgeGlass(RoundedCornerShape(28.dp)), color = Color.Transparent, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text(
                    when (state.phase) {
                        RemoteCapturePhase.COUNTDOWN -> "准备好，马上拍摄"
                        RemoteCapturePhase.PREPARING -> "准备拍摄"
                        RemoteCapturePhase.WAITING -> "等待相机确认"
                        else -> "轻点快门，记录此刻"
                    }, color = BridgeWhite.copy(alpha = .8f), fontSize = 16.sp,
                )
                Button(
                    onClick = { vm.startRemoteCapture(delaySeconds) },
                    enabled = available,
                    modifier = Modifier.size(154.dp).bridgeGlass(CircleShape),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BridgeEmber.copy(alpha = .22f), contentColor = BridgeWhite,
                        disabledContainerColor = Color.Transparent, disabledContentColor = BridgeWhite.copy(alpha = .45f),
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    when (state.phase) {
                        RemoteCapturePhase.COUNTDOWN -> Text(state.secondsRemaining.toString(), fontSize = 58.sp, fontWeight = FontWeight.Light, color = BridgeWhite)
                        RemoteCapturePhase.PREPARING, RemoteCapturePhase.WAITING -> CircularProgressIndicator(Modifier.size(42.dp), color = BridgeEmber, strokeWidth = 3.dp)
                        else -> Icon(Icons.Default.RadioButtonChecked, "拍摄", Modifier.size(70.dp), tint = if (available) BridgeEmber else BridgeWhite.copy(alpha = .45f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 2, 5, 10).forEach { seconds ->
                        FilterChip(
                            selected = delaySeconds == seconds,
                            onClick = { delaySeconds = seconds }, enabled = !state.inProgress,
                            label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if (seconds == 0) "立即" else "${seconds}秒") } },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent, labelColor = BridgeWhite,
                                selectedContainerColor = BridgeEmber, selectedLabelColor = BridgeNight,
                            ),
                        )
                    }
                }
                if (state.phase == RemoteCapturePhase.COUNTDOWN || state.phase == RemoteCapturePhase.PREPARING) {
                    TextButton(onClick = vm::cancelPendingRemoteCapture) { Text("取消拍摄", color = BridgeWhite) }
                }
                Text("照片保存在相机中，可前往「照片」查看和下载", color = BridgeWhite.copy(alpha = .6f), fontSize = 13.sp)
            }
        }
        if (!wifiConnected || !supported) {
            OutlinedButton(openConnection, Modifier.fillMaxWidth().height(52.dp).bridgeGlass(RoundedCornerShape(20.dp)), enabled = !state.inProgress && !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgeWhite), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Default.Link, null)
                Spacer(Modifier.width(8.dp))
                Text(if (session == null) "连接相机" else "查看相机连接")
            }
        }
        Text("倒计时期间离开页面或切到后台会取消待发送的拍摄。快门发送后，请等待相机完成曝光。", color = BridgeWhite.copy(alpha = .6f), fontSize = 13.sp)
    }
}
