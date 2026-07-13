# Camera_Bridge

面向 Android 的尼康相机照片桥接工具，支持 **Wi‑Fi (PTP/IP)** 与 **USB (MTP)** 两种连接方式，提供原图浏览、预览、下载与分享功能。

## 功能

### 连接
- **Wi‑Fi 连接**：连接页三步操作指引（连接智能设备 → AP mode → 建立 Wi-Fi 连接）
- **USB 连接**：即插即用，通过 USB 数据线直接连接相机（需要手机支持 USB OTG）
- 记住上次连接的相机 Wi-Fi，连不上时自动跳转系统 Wi-Fi 设置
- 前台服务保持 Wi-Fi 连接不断开，Wi-Fi 锁防止息屏时相机热点休眠
- USB 诊断工具（设置页查看设备详细信息）

### 相册
- 按格式分类筛选（全部 / JPG / RAW / 视频）
- 多选批量下载
- 照片预览：双指缩放、拖拽、逆时针旋转
- 查看原图（从相机下载全分辨率）
- 预览内直接下载原图
- 分页加载，底部进度提示

### 下载
- 下载页查看高清原图（从本地加载，无需联网）
- 多选分享（QQ / 微信等系统分享面板）
- 多选删除（带二次确认）
- 按格式分类筛选（全部 / JPG / RAW / 视频）
- 下载通知进度条（实时百分比 + 进度条）
- 下载失败通知栏一键重试

### 其他
- 动态 chunk 拥塞控制提升下载速度
- 下载后自动保存到系统相册
- 暗色主题（Bridge Night 配色）
- Cube LUT 色彩预设（多种胶片风格滤镜）
- 水印叠加（EXIF 信息水印，可自定义位置与样式）

## Android 构建

```bash
./gradlew :app:assembleDebug
```

生成的 APK：

```text
App/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

### Wi‑Fi 连接
1. 在相机上开启 Wi‑Fi 热点（连接到智能设备 → AP mode）。
2. 手机连接相机 Wi‑Fi 热点。
3. 打开 Camera_Bridge，点击「开始建立连接」。
4. 连接成功后可浏览、预览和下载照片。

### USB 连接
1. 用 USB 数据线将相机连接到手机（相机需开启 MTP 模式）。
2. 打开 Camera_Bridge，自动检测 USB 相机。
3. 授予 USB 权限后即可浏览和下载照片。

> 小米设备建议将 Camera_Bridge 的电池策略设置为「无限制」，并关闭 WLAN 助理的自动切网功能。

## 已测试机型

| 设备 | 相机 | 连接方式 | 状态 |
|------|------|---------|------|
| 小米 14 | 尼康 Zf | Wi‑Fi (PTP/IP) | ✅ 通过 |
| 小米 14 | 尼康 Zf | USB (MTP) | ✅ 通过 |

## 目录

```text
App/src/main/java/   Kotlin 与 Jetpack Compose Android 源码
App/src/main/res/     Android 资源
gradle/               Gradle Wrapper
```
