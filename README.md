# Camera_Bridge

面向 Android 的相机 Wi‑Fi 照片桥接工具，适配尼康 Zf 的 PTP/IP 连接。

## 功能

- 连接相机 Wi‑Fi 后读取照片列表
- 首次加载 30 张，继续下滑动态加载，每页 15 张
- 点击照片预览
- 单张下载和多选下载
- 下载到系统相册或下载目录
- 前台服务保持后台连接，减少切换到后台后断开
- Wi‑Fi 锁防止息屏时相机热点休眠

## Android 构建

```bash
./gradlew :app:assembleDebug
```

生成的 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. 在相机上开启 Wi‑Fi 热点。
2. 手机连接相机 Wi‑Fi。
3. 打开 Camera_Bridge，点击连接相机。
4. 连接成功后可浏览、预览和下载照片。

小米设备建议将 Camera_Bridge 的电池策略设置为“无限制”，并关闭 WLAN 助理的自动切网功能。

## 目录

```text
App/src/main/java/   Kotlin 与 Jetpack Compose Android 源码
App/src/main/res/     Android 资源
gradle/               Gradle Wrapper
```
