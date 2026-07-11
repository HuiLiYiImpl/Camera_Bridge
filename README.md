# Camera_Bridge

一个专门为摄影师打造的 **iOS 原生照片传输应用**。采用极简、克制的 **Claude-style 浅色设计**，通过标准 PTP/IP 协议及双通道 TCP 通信，实现在手机连接相机 Wi-Fi 热点后，快速、稳定地浏览与批量下载相机照片。

---

## ✨ 核心特性与设计规范

### 🎨 Claude 风格极简界面 (Claude-style Aesthetic)
*   **温润背景**：全局采用暖白色底色（`#F9F9F8`），搭配无描边的阴影卡片层级，最大程度减少界面中的工程噪音，聚焦摄影作品本身。
*   **高对比度操作**：主按钮采用极致黑圆角胶囊（Capsule），辅以温润的琥珀金（Amber Gold）作为核心强调色。

### 🪄 物理级交互与微动效 (Micro-interactions)
*   **设备滚动切换标题**：未连接状态下，首屏主标题自动在 **尼康、索尼、佳能、富士** 品牌之间进行类似老虎机滚轮的上下滚动淡入淡出动画，昭示未来多品牌整合生态。
*   **镜头呼吸光晕 (`LensGlowView`)**：搜索相机或建立连接时，黄金分割视觉中心的镜头图标会带有波纹放大的琥珀色呼吸光圈动效；连接成功后则平滑转为常亮绿。
*   **照片扫光骨架屏 (`ShimmerView`)**：相机缩略图加载中时，原位展现细腻的扫光动效；数据传输完毕后以 `0.35s` 的渐现动画优雅浮现，完全消除加载时的闪烁感。
*   **全机触感反馈 (`Haptics`)**：点击主副按钮、勾选照片多选、连接成功或失败时，手机线性马达会触发不同层级的触觉微震（Medium/Light/Success/Error），带来扎实的物理操作回馈。

---

## 🗺️ 多品牌相机集成路线 (Roadmap)

应用底层采用通用 PTP/IP 协议设计，未来将全量支持主流相机品牌：

*   [x] **Nikon (实验性物理连接已就绪)**：通过相机的 Wi-Fi 热点默认 IP `192.168.1.1:15740` 建立稳定长连接，拉取元数据和批量下载。
*   [ ] **Sony (规划中)**：支持索尼 Wi-Fi 的 PTP/IP 通信握手协议。
*   [ ] **Canon (规划中)**：支持佳能 PTP over TCP 自定义配对及数据通道。
*   [ ] **Fujifilm (规划中)**：支持富士相机的专属套接字握手机制与浏览协议。

---

## 📂 项目结构

```text
├── App/                       # App 入口、全局配色、微badge组件
├── Domain/                    # 实体模型（状态、配置、照片对象定义）
├── Features/                  # SwiftUI 业务界面
│   ├── ConnectionSetup/       # 相机连接与状态控制（含 LensGlowView）
│   ├── PhotoBrowser/          # 照片网格浏览与大图查看（含 Shimmer 扫光）
│   ├── Downloads/             # 已下载历史（带 Trailing 刷新控制）
│   └── Settings/              # 偏好设置与运行记录
├── Shared/                    # 共享按钮、卡片、马达震动等高频组件
├── Infrastructure/            # 底层 TCP 套接字封装
└── Services/                  # PTP/IP 核心会话与编解码服务
```

---

## 🛠️ 构建与运行

### 环境要求
*   **macOS Sequoia (15.0+)**
*   **Xcode 16.0+**
*   **XcodeGen** (建议版本 `2.45.0+`，用于自动生成 `.xcodeproj`)

### 1. 生成项目工程
在主工程根目录下，通过本地 Xcode 环境变量运行项目生成脚本：
```bash
./scripts/generate_project.sh
```

### 2. 本地类型检查 (Type Check)
本地利用 macOS Target 进行基础自检（忽略 SwiftUI 缺少 macOS SDK 特性导致的不可用警告）：
```bash
DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer ./scripts/typecheck_macos.sh
```

### 3. 打开并调试
```bash
open NikonConnectIOS.xcodeproj
```
建议使用 **iOS 17.0+ 真机** 进行调试，可以体验完整的线性马达物理触觉反馈 (Haptics)。
