package com.yaoyihan.nikonconnect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.skyblond.nsp.service.ConnectionState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class GpsSyncActivity : ComponentActivity() {
    private val vm: GpsSyncViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.setPlaceVisible(true)
    }

    override fun onStop() {
        vm.setPlaceVisible(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val store = remember { AppStore(applicationContext) }
            val prefs = remember { getSharedPreferences("nikon_connect", Context.MODE_PRIVATE) }
            var theme by remember { mutableStateOf(store.config().colorTheme) }
            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "color_theme") theme = store.config().colorTheme
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            BridgeTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = BridgeNight) {
                    GpsSyncScreen(vm, ::finish)
                }
            }
        }
    }
}

@Composable
private fun GpsSyncScreen(vm: GpsSyncViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val required = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    }
    var permissionsGranted by remember {
        mutableStateOf(required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = required.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val bluetooth = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    var bluetoothEnabled by remember { mutableStateOf(bluetooth?.isEnabled == true) }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothEnabled = bluetooth?.isEnabled == true
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) permissionLauncher.launch(required)
        else vm.bind()
    }
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            vm.bind()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(Modifier.fillMaxSize().bridgeBackground().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Link", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("GPS 连接", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = back, modifier = Modifier.bridgeGlass(CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(Modifier.size(52.dp), shape = RoundedCornerShape(17.dp), color = BridgeEmber.copy(alpha = .15f)) {
                    Icon(Icons.Default.LocationOn, null, Modifier.padding(13.dp), tint = BridgeEmber)
                }
                Column {
                    Text("GPS 同步", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("将手机位置同步到相机", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("通过蓝牙连接 Nikon 相机，拍摄时记录位置信息。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (!permissionsGranted) item {
            GpsCard {
                Text("开启位置与蓝牙权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("需要精确位置和蓝牙权限。请选择精确位置与使用期间允许。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                GpsButton(onClick = { permissionLauncher.launch(required) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("授予权限") }
            }
        } else if (!bluetoothEnabled) item {
            GpsCard {
                Text("蓝牙已关闭", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("开启手机蓝牙后，即可扫描相机并同步 GPS。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                GpsButton(onClick = { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("开启蓝牙")
                }
            }
        } else {
            item {
                GpsCard {
                    Text("连接状态", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.bound) state.connection.label(context) else "正在准备服务…")
                    Text("保持相机在‘连接到智能设备’的蓝牙等待界面，首次配对需在相机和手机确认。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.connection != ConnectionState.Idle) {
                        OutlinedButton(onClick = vm::stop, enabled = state.bound) { Text("停止同步") }
                    }
                }
            }
            item {
                GpsCard {
                    Text("当前 GPS", style = MaterialTheme.typography.titleMedium)
                    val gps = state.gps
                    if (gps.hasFix) {
                        state.place.address?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        Text(state.place.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(String.format(Locale.US, "%.6f, %.6f", gps.latitude, gps.longitude))
                        Text("海拔 ${gps.altitudeMeters?.let { String.format(Locale.US, "%.1f m", it) } ?: "--"} · 精度 ${gps.accuracyMeters?.let { String.format(Locale.US, "%.0f m", it) } ?: "--"}")
                        Text("定位时间 ${formatTime(gps.lastFixTime)}")
                    } else Text("等待有效定位：需海拔、精度 ≤100 m、位置年龄 ≤120 秒")
                    Text("最近一次确认发送 ${formatTime(gps.lastSentTime)}")
                }
            }
            item { Text("已配对相机", style = MaterialTheme.typography.titleMedium) }
            if (state.saved.isEmpty()) item { Text("暂无，请扫描并配对相机") }
            items(state.saved, key = { "saved:${it.name}" }) { camera ->
                GpsCard {
                    Text(camera.name, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GpsButton(onClick = { vm.connect(camera) }, enabled = state.bound) { Text("开始同步") }
                        TextButton(onClick = { vm.recoverCameraId(camera) }, enabled = state.bound) { Text("恢复配对标识") }
                    }
                }
            }
            item {
                GpsButton(onClick = vm::scan, enabled = state.bound, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("扫描新相机")
                }
            }
            if (state.discovered.isNotEmpty()) item { Text("发现的相机", style = MaterialTheme.typography.titleMedium) }
            items(state.discovered, key = { "found:${it.address}" }) { camera ->
                GpsCard {
                    Text(camera.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (camera.isNew) "可新配对" else "已有配对记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GpsButton(onClick = { vm.pair(camera) }, enabled = state.bound) { Text("连接 / 配对") }
                }
            }
            item { Text("调试日志", style = MaterialTheme.typography.titleMedium) }
            if (state.logs.isEmpty()) item { Text("暂无日志") }
            items(state.logs.take(30)) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
    }
}

@Composable
private fun GpsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.bridgeGlass(RoundedCornerShape(18.dp)),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = BridgeWhite,
            disabledContainerColor = BridgeWhite.copy(alpha = .04f),
            disabledContentColor = BridgeWhite.copy(alpha = .45f),
        ),
        content = content,
    )
}

@Composable
private fun GpsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().bridgeGlass(RoundedCornerShape(26.dp)), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

private fun formatTime(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))
} ?: "尚未发送"
