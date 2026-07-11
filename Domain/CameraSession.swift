import Foundation

enum CameraCapability: String, Codable, Hashable, CaseIterable, Sendable {
    case connectionProbe
    case listAssets
    case downloadAssets

    var title: String {
        switch self {
        case .connectionProbe:
            return "连通性探测"
        case .listAssets:
            return "读取照片列表"
        case .downloadAssets:
            return "下载照片"
        }
    }
}

struct CameraSession: Identifiable, Equatable, Sendable {
    let id: UUID
    let cameraName: String
    let connectedHost: String
    let port: Int
    let connectedAt: Date
    let capabilities: Set<CameraCapability>

    init(
        id: UUID = UUID(),
        cameraName: String,
        connectedHost: String,
        port: Int,
        connectedAt: Date = Date(),
        capabilities: Set<CameraCapability>
    ) {
        self.id = id
        self.cameraName = cameraName
        self.connectedHost = connectedHost
        self.port = port
        self.connectedAt = connectedAt
        self.capabilities = capabilities
    }
}
