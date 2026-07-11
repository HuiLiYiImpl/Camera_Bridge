import Foundation

enum CameraAppError: LocalizedError, Sendable {
    case missingHost
    case invalidPort
    case notConnected
    case networkProbeFailed(String)
    case unsupportedOperation(String)
    case fileSystemFailure(String)
    case photoLibraryAccessDenied
    case photoLibraryExportFailed(String)

    var errorDescription: String? {
        switch self {
        case .missingHost:
            return "请输入相机地址。"
        case .invalidPort:
            return "端口号无效。"
        case .notConnected:
            return "当前没有可用的相机会话。"
        case .networkProbeFailed(let reason):
            return "连接相机失败：\(reason)"
        case .unsupportedOperation(let message):
            return message
        case .fileSystemFailure(let reason):
            return "本地文件写入失败：\(reason)"
        case .photoLibraryAccessDenied:
            return "系统相册权限未授予。"
        case .photoLibraryExportFailed(let reason):
            return "保存到系统相册失败：\(reason)"
        }
    }

    var recoverySuggestion: String? {
        switch self {
        case .missingHost:
            return "先在 iPhone 设置里连接相机 Wi-Fi，再输入相机 IP 地址。"
        case .invalidPort:
            return "请确认端口为 1 到 65535 之间的数字。"
        case .notConnected:
            return "先建立连接，再读取照片列表或执行下载。"
        case .networkProbeFailed:
            return "确认 iPhone 已连接到相机热点，并允许本地网络访问。"
        case .unsupportedOperation:
            return "当前工程已经预留了协议层接口，下一步可以在这里接入 Nikon 真实传输实现。"
        case .fileSystemFailure:
            return "检查设备存储空间是否充足。"
        case .photoLibraryAccessDenied:
            return "到系统设置里为 App 开启“照片”写入权限。"
        case .photoLibraryExportFailed:
            return "可以先保存在 App 本地目录，再稍后导出。"
        }
    }
}
