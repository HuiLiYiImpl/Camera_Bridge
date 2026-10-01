package com.yaoyihan.nikonconnect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaceNameSettingsCard() {
    val context = LocalContext.current
    val store = remember { PlaceNameStore(context.applicationContext) }
    val initial = remember { store.read() }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var key by remember { mutableStateOf(initial.key) }
    var showKey by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().bridgeGlass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("地点名称 · 腾讯地图", color = BridgeWhite, style = MaterialTheme.typography.titleMedium)
                    Text("在 GPS 页面显示地址和附近地标", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
                }
                Switch(enabled, { enabled = it; message = null }, colors = SwitchDefaults.colors(checkedThumbColor = BridgeNight, checkedTrackColor = BridgeEmber))
            }
            OutlinedTextField(key, { key = it.trim(); message = null }, Modifier.fillMaxWidth(),
                label = { Text("腾讯地图 API Key") }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } },
                shape = RoundedCornerShape(16.dp), colors = bridgeFieldColors())
            Text("开启并保存后，会将 GPS 坐标发送至腾讯地图解析。需要联网；Key 请启用 WebService API。免费额度与收费规则以腾讯控制台为准。", color = BridgeWhite.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (enabled && key.isBlank()) message = "请先填写 API Key"
                    else { store.save(PlaceNameSettings(enabled, key)); message = "已保存" }
                }) { Text("保存", color = BridgeEmber) }
                TextButton(onClick = { key = ""; enabled = false; store.save(PlaceNameSettings()); message = "已清除 Key 并关闭地点解析" }) {
                    Text("清除 Key", color = BridgeWhite)
                }
            }
            message?.let { Text(it, color = BridgeWhite, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
