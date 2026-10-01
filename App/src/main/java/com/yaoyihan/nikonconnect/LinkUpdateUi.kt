package com.yaoyihan.nikonconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun LinkUpdatePrompt(vm: LinkUpdateViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.check(silent = true) }
    val release = state.release ?: return
    if (!state.dialogVisible) return
    AlertDialog(
        onDismissRequest = vm::dismiss,
        containerColor = BridgeSurface,
        titleContentColor = BridgeWhite,
        textContentColor = BridgeWhite,
        title = { Text("${release.title} · ${release.versionName}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                release.notes.forEach { Text("• $it") }
                if (release.sizeBytes > 0) Text("安装包 ${release.sizeBytes.prettySize()}", color = BridgeWhite.copy(alpha = .7f))
                Text("将在浏览器中打开蒲公英页面，下载完成后安装。", color = BridgeWhite.copy(alpha = .7f))
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.openDownloadPage(context) }) {
                Text("前往下载")
            }
        },
        dismissButton = {
            TextButton(onClick = vm::dismiss) {
                Text("稍后")
            }
        },
    )
}

@Composable
internal fun LinkUpdateCard(vm: LinkUpdateViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    Surface(Modifier.fillMaxWidth().bridgeGlass(RoundedCornerShape(20.dp)), color = Color.Transparent, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Link", color = BridgeWhite, style = MaterialTheme.typography.titleMedium)
            Text("当前版本 ${BuildConfig.VERSION_NAME} · Wi‑Fi / USB 相机导入", color = BridgeWhite.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
            state.release?.let { Text("发现新版本 ${it.versionName}", color = BridgeWhite) }
            state.message?.let { Text(it, color = BridgeWhite.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.check() }, enabled = !state.checking,
                    modifier = Modifier.bridgeGlass(RoundedCornerShape(18.dp)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = BridgeWhite)) {
                    Text(if (state.checking) "正在检查…" else "检查更新")
                }
                if (state.release != null) TextButton(onClick = vm::showRelease) {
                    Text("查看更新公告", color = BridgeWhite)
                }
            }
        }
    }
}
