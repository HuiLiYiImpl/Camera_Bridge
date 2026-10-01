# Link 遥控拍摄调研

调研日期：2026-09-30。当前阶段只调研，没有修改遥控功能代码，也没有对相机执行拍摄或连接模式切换。

## 结论

可以开发一版独立于 SnapBridge 的遥控功能。建议先扩展现有 Kotlin PTP/IP 客户端，采用 Wi-Fi 遥控，优先验证 Nikon Zf。公开资料证明 Zf 具备遥控能力，但尚不能确认 Link 当前使用的智能设备传图会话允许同样的命令。

第一版建议：第五个导航项「遥控」、大快门按钮、立即/2/5/10 秒拍摄、倒计时取消、连接及拍摄状态。页面沿用现有玻璃样式和主题色。实时取景、曝光参数、连拍、录像和 USB 遥控后续增加。

## 官方资料

尼康确实提供开发者 SDK，普通相机参考指南和开发者 SDK 是两类资料。

- [开发者 SDK 下载入口](https://sdk.nikonimaging.com/apply/)提供遥控库和 Command API Specifications，列明快门释放、快门速度、光圈、ISO 等能力。
- [官方发布记录](https://sdk.nikonimaging.com/information/en/)显示，2026-03-31 发布的统一 Z 系列 Remote Module SDK 2.0.0 已包含 Zf。
- 官方公开说明针对连接计算机的遥控应用，发布记录涉及 Windows/macOS。本次未下载 SDK 压缩包，也未看到 Android 直接集成说明，不能把它作为现成 Android SDK 使用。
- [SDK 申请流程](https://sdk.nikonimaging.com/apply/guide)需要选择类型、阅读许可协议，再下载对应文件。具体命令文档内容仍需下载后核对。

## 开源参考

| 来源 | 已确认内容 | 对 Link 的用途 |
| --- | --- | --- |
| [libgphoto2](https://github.com/gphoto/libgphoto2) | C 实现，LGPL-2.1；有 Nikon 拍摄、事件及实时取景协议代码 | 首选协议参考；首版不必移植整个 C 库 |
| [Zf Capture One Helper](https://github.com/open-nikonz/zf-capture-one-helper) | macOS/Swift 项目，Apache-2.0；作者报告 Zf 的 USB/Wi-Fi 遥控结果 | 参考 Zf 遥控初始化及 Wi-Fi 配对条件；不直接引入其桌面代理 |
| [furble](https://github.com/gkoh/furble) | ESP32 蓝牙遥控项目，MIT；Nikon ML-L7 模式实现快门 | 可作为未来纯蓝牙快门的参考；不是现有 GPS 智能设备会话的直接扩展 |
| [DslrDashboard](https://github.com/hubaiz/DslrDashboard) | 早期 Android USB 遥控代码 | 架构参考；代码年代较早，不能据此认定兼容 Zf |

libgphoto2 的 [Zf 设备能力记录](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/cameras/nikon-zf.txt)来自固件 V1.10，记录了 Generic Image Capture 和 Nikon Capture 1/2/3。它证明相关能力存在，不能替代当前固件、当前连接模式的实机验证。

Zf Helper 的[研究记录](https://github.com/open-nikonz/zf-capture-one-helper/blob/main/docs/RESEARCH.md)报告：建立会话后使用 ChangeCameraMode(1) 和 ChangeApplicationMode(1) 初始化遥控；其无线方案使用相机「连接至计算机」配置，包含首次配对和持久化客户端身份。作者也发现命令拍摄可能绕过相机自拍延时，因此 Link 倒计时应在手机端实现。以上是第三方项目观察，尚未在 Link 上验证。

furble 的 [NikonSmart.cpp](https://github.com/gkoh/furble/blob/master/lib/furble/NikonSmart.cpp)中智能设备模式的快门方法为空；真正的快门实现位于 [NikonRemote.cpp](https://github.com/gkoh/furble/blob/master/lib/furble/NikonRemote.cpp)，走独立遥控器模式。不能因为已有 GPS 蓝牙连接，就推断可以直接发送快门命令。尼康 Zf 的[官方 ML-L7 设置说明](https://onlinemanual.nikonimglib.com/zf/zh-cn/nwm_wireless_remote_ml-l7_options_331.html)可用于后续蓝牙遥控流程参考。

另外筛查了 darkgrade：其仓库使用 FCL-1.0-ALv2，包含使用限制，本次不选作主要集成依赖；NikonLink 的遥控仍列为计划功能，不能作为已实现的参考。

## 与现有项目的对应关系

检查了以下现有代码：

- `App/src/main/java/com/yaoyihan/nikonconnect/CameraClient.kt`：目前只有连接、资源读取、缩略图、下载等接口，没有遥控 API。
- `App/src/main/java/com/yaoyihan/nikonconnect/PtpIpClient.kt`：已有命令和事件两个连接通道；DeviceInfo 解析丢弃了支持操作和事件列表；事件通道完成握手后没有持续读取。
- 同一文件的请求错误只显示操作码，缺少结构化相机响应码。事务编号分配和所有请求需要统一串行化，避免拍摄、下载、缩略图和保活相互干扰。
- 当前连接使用固定 Android 客户端身份，并针对 Zf 智能设备传图会话做了兼容。若遥控被拒绝，需要另外实现电脑遥控配置的发现和配对，不能只更改命令或 GUID 就假定已获授权。
- `UsbMtpClient.kt` 使用 Android `MtpDevice`。通过本机 Android 35 SDK 的公开方法列表确认，它没有直接发送任意 Nikon vendor operation 的接口。USB 遥控需要新增底层 PTP 通道或原生实现，首版暂不纳入。

## 首版实现顺序

1. 保留相机支持操作/事件列表，建立遥控能力状态；不支持或连接模式不允许时显示明确原因。
2. 对 PTP 命令和事务编号使用统一串行入口，保留响应码，限制拍摄与下载同时执行。
3. 在用户进入遥控功能时按相机能力执行必要初始化，记录已改变的模式；退出、重连或失败时尽力恢复，避免无条件改写相机状态。
4. 提交一次拍摄命令后等待拍摄完成/新对象等确认。PTP OK 只能表示命令接受，不能直接等同于照片已经保存。结果未知时不要自动再次触发快门。
5. 手机端倒计时可取消；离开页面、切到后台或断连时取消尚未发送的拍摄。发送后取消等待不代表相机停止曝光，界面需区分两者。
6. 拍摄确认后刷新照片列表。照片先保存在相机卡上，下载沿用现有功能。

参考操作码来自 [libgphoto2 ptp.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)：

| 命令 | 操作码 | 备注 |
| --- | --- | --- |
| InitiateCapture | `0x100E` | 标准 PTP 拍摄；仅在设备宣告支持时考虑 |
| Nikon ChangeCameraMode | `0x90C2` | Nikon 模式初始化参考 |
| Nikon ChangeApplicationMode | `0x9435` | Zf Helper 使用的初始化之一 |
| Nikon InitiateCaptureRecInMedia | `0x9207` | libgphoto2 有 AF/存储目标参数和机型兼容处理，不能统一写死参数 |
| Nikon StartLiveView / EndLiveView / GetLiveViewImg | `0x9201` / `0x9202` / `0x9203` | 后续实时取景参考；还需图像数据解析和流畅性处理 |

具体分支和参数应结合 [libgphoto2 library.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c)及设备返回能力核对。若复制开源实现，应保留来源并按对应许可处理；目前只下载阅读了参考文本。

## 尚待确认

- Zf 当前固件在 Link 的智能设备 Wi-Fi 会话中是否允许快门和遥控初始化。
- 若需要「连接至计算机」，首次配对和相机确认流程在 Android 上的实现。
- 对焦失败、相机忙、连拍模式、长曝光及双卡情况下的完成事件和反馈。
- 其他 Nikon 机型支持范围，需要逐机型按能力判断，不能把 Zf 结果泛化到所有相机。

按用户此前偏好，实机功能验证交由用户；本次没有执行功能测试、打包或安装。
