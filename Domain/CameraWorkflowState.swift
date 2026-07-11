import Foundation

enum CameraWorkflowState: String, Sendable {
    case waitingForWifi
    case connecting
    case connected
    case loadingPhotos
    case downloading
    case error

    var title: String {
        switch self {
        case .waitingForWifi:
            return "等待 Wi-Fi"
        case .connecting:
            return "连接中"
        case .connected:
            return "已连接"
        case .loadingPhotos:
            return "读取照片中"
        case .downloading:
            return "下载中"
        case .error:
            return "需要处理"
        }
    }

    var symbolName: String {
        switch self {
        case .waitingForWifi:
            return "wifi"
        case .connecting:
            return "antenna.radiowaves.left.and.right"
        case .connected:
            return "checkmark.circle.fill"
        case .loadingPhotos:
            return "rectangle.stack"
        case .downloading:
            return "arrow.down.circle"
        case .error:
            return "exclamationmark.triangle.fill"
        }
    }
}
