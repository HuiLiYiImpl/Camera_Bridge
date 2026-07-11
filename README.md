# Camera_Bridge

面向 Android 的尼康相机 Wi‑Fi 照片桥接工具，通过 PTP/IP 协议连接相机，支持原图浏览、预览、下载与分享。

## 功能

### 连接
- 连接页三步操作指引（连接智能设备 → AP mode → 建立 Wi-Fi 连接）
- 记住上次连接的相机 Wi-Fi，连不上时自动跳转系统 Wi-Fi 设置
- 前台服务保持 Wi-Fi 连接不断开，Wi-Fi 锁防止息屏时相机热点休眠

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

## Android 构建

```bash
./gradlew :app:assembleDebug
```

生成的 APK：

```text
App/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. 在相机上开启 Wi‑Fi 热点（连接到智能设备 → AP mode）。
2. 手机连接相机 Wi‑Fi 热点。
3. 打开 Camera_Bridge，点击「开始建立连接」。
4. 连接成功后可浏览、预览和下载照片。

小米设备建议将 Camera_Bridge 的电池策略设置为「无限制」，并关闭 WLAN 助理的自动切网功能。

## 已测试机型

| 设备 | 相机 | 状态 |
|------|------|------|
| 小米 14 | 尼康 Zf | ✅ 通过 |

## 目录

```text
App/src/main/java/   Kotlin 与 Jetpack Compose Android 源码
App/src/main/res/     Android 资源
gradle/               Gradle Wrapper
```
