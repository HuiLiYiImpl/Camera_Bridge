# Nikon Zf GPS 同步 PoC

## 来源与范围

Camera Bridge 的蓝牙协议层移植自 [nsg Android](https://github.com/hurui200320/nsg/tree/master/android)，其握手协议参考了 [furble](https://github.com/gkoh/furble)。移植时保留了 nsg 的扫描、配对、DeviceID、GATT 握手及重连状态机。原项目采用 **AGPL-3.0**；原许可证全文在 `THIRD_PARTY_LICENSES/nsg_AGPL-3.0.txt`。本仓库分发合并后的应用时，需要按 AGPL-3.0 履行源代码提供等义务。此处未加入遥控、图传或实时取景。

nsg README 报告 Android 端在 Z7 II 上真机验证，未报告 Zf 验证。尼康 [Zf 官方说明](https://onlinemanual.nikonimglib.com/zf/zh-cn/nwm_connect_to_smart_device_325.html)显示“位置数据（智能设备）”可接收纬度、经度、海拔和 UTC。本 PoC 的成功标准是 **相机 SD 卡中新拍摄的原始 JPEG/NEF 自带 GPS EXIF**，应用显示“写入成功”不等同于此标准。

## 代码链路

| 步骤 | 文件 |
| --- | --- |
| BLE 扫描、选择相机、已保存相机重扫 | `App/src/main/java/info/skyblond/nsp/service/NikonPairingSession.kt` |
| 经典蓝牙 Bond、SnapBridge DeviceID 恢复 | `NikonPairingSession.kt`、`ble/protocol/SnapBridgeIdSolver.kt`、`data/SettingsRepository.kt` |
| GATT 连接、特征发现、PAIR/NOT1 订阅和写入 | `ble/CameraBleManager.kt` |
| 四阶段握手、ID 特征写入 | `ble/protocol/NikonPairingEngine.kt`、`ble/protocol/PairingMessage.kt`、`NikonPairingSession.kt` |
| GEO 编码，内含 UTC | `ble/GeoPayloadGenerator.kt` |
| 可用时的独立 TIME 特征写入 | `ble/TimePayloadGenerator.kt`、`NikonPairingSession.kt`；10 字节格式来自 nsg ESP32 的 `TimeMessage.cpp` |
| 真实定位、质量过滤、前台服务、断线重连 | `service/CameraConnectionService.kt` |
| 协议与定位/UI 分界 | `service/NikonLocationClient.kt`：`connect(camera)`、`disconnect()`、`state`、`updateLocation(fix)` |
| 页面 | `GpsSyncActivity.kt`、`GpsSyncViewModel.kt`；入口在相机连接中心和设置页 |

Android 原版 nsg 只写 GEO（其中内嵌 UTC）；同一项目的 ESP32 版会先写独立 TIME。这里在相机暴露 TIME 特征时按 ESP32 顺序写 TIME→GEO；没有 TIME 特征时维持 Android 原路径。任何 Zf 不兼容均先抓包对比，不凭猜测修改报文。

## 定位与权限

仅接受 Android `Location` 中同时具备经纬度、海拔、水平精度 ≤100 m、单调时钟位置年龄 ≤120 秒的定位。无有效定位时不发送 GEO，旧版 nsg 的模拟坐标回退已移除。“最近一次确认发送”只有 GATT GEO 写入回调成功后才更新，尚不代表相机已将位置写进照片。

`targetSdk=35`。Android 12+ 请求 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`；Android 11 及以下使用旧版 `BLUETOOTH`/`BLUETOOTH_ADMIN` 声明。请求精确位置 `ACCESS_FINE_LOCATION`；Android 13+ 另外请求 `POST_NOTIFICATIONS`。前台服务声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`FOREGROUND_SERVICE_LOCATION`，仅从可见页面启动。断线后保持服务并按约 30 秒循环重试已保存的相机。若系统或用户杀死服务，后台自行重新启动仍受 Android 限制。

依赖：现有 AndroidX Compose、Activity、Lifecycle、Core KTX，新增 `kotlinx-coroutines-android:1.8.1`；未引入定位 SDK 或 BLE 第三方库。

## 构建与安装

```powershell
$env:JAVA_HOME = 'D:\code\androidStudio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :App:assembleDebug --no-daemon
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r .\App\build\outputs\apk\debug\App-debug.apk
```

安装包仍是 `com.yaoyihan.nikonconnect`，覆盖安装保留现有应用数据。手机内从 **相机连接中心 → Nikon GPS 同步** 或 **设置 → Nikon GPS 同步** 进入。

## Zf 实机验收

1. 记录 Zf **固件版本**、手机型号、Android 版本；本次安装设备由 ADB 报告为 `23127PN0CC / Android 16`，Zf 固件待相机菜单确认。
2. 相机：网络菜单 → 连接至智能设备 → 配对（Bluetooth）→ 蓝牙连接设为 ON；首次配对时选择“开始配对”。手机打开 Camera Bridge 的 GPS 同步页，授予精确位置、附近设备和通知权限，扫描并选择 Zf，在相机与手机确认配对。
3. 待页面显示“就绪”、当前 GPS 有海拔及合格精度、最近一次确认发送有时间。检查相机的“位置数据（智能设备）”页面是否出现纬度、经度、海拔、UTC。
4. 保持蓝牙同步，在相机上新拍一张 JPEG 和一张 NEF。**直接从 SD 卡复制原文件**，不要使用手机导出或后期编辑文件。
5. 用 ExifTool 对原文件运行：

```powershell
exiftool -G1 -a -s -Model -Software -GPSLatitude -GPSLongitude -GPSAltitude -GPSDateStamp -GPSTimeStamp .\DSC_0001.JPG .\DSC_0001.NEF
```

预期两个文件均有 `GPSLatitude`、`GPSLongitude`、`GPSAltitude`；记录输出和拍摄时间。若仅页面有坐标而文件没有 GPS 标签，仍算未通过。

## 失败日志与协议对比

先采集应用日志（替换序列号和输出目录）：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s f8da9b49 logcat -c
# 在相机和应用中重现一次连接或写入失败
& $adb -s f8da9b49 logcat -d -v threadtime -s NikonPairingSession:D CameraBleManager:D CameraConnectionService:D AndroidRuntime:E > .\zf-gps-logcat.txt
```

若 Zf 配对或握手失败，在手机开发者选项开启“启用蓝牙 HCI 信息收集日志”，重启蓝牙后分别录制 **SnapBridge 成功连接/发送位置** 和 **Camera Bridge 失败连接/发送位置** 两轮。每轮记录开始/结束时间、相机菜单状态、配对是否确认；使用 Android bugreport 导出 snoop 文件（不同系统的文件位置不同）：

```powershell
& $adb -s f8da9b49 bugreport .\zf-hci-bugreport.zip
```

在 Wireshark 中按同一相机地址与时间窗口过滤，逐步对比：扫描广播和 DeviceID、Bond、GATT 服务/特征发现、PAIR 四阶段消息、ID 写入、TIME/GEO 写入及回调状态。只修补第一个确定的差异，再复测 SD 卡原文件 EXIF。分享日志前遮蔽设备地址、DeviceID 与坐标。
