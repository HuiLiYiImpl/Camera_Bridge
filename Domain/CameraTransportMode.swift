import Foundation

enum CameraTransportMode: String, CaseIterable, Codable, Identifiable, Sendable {
    case experimentalNikon

    var id: String { rawValue }

    var title: String {
        "Nikon Wi-Fi"
    }

    var detail: String {
        "使用尼康相机 Wi-Fi 地址 192.168.1.1:15740 建立连接。"
    }

    var defaultHost: String? {
        "192.168.1.1"
    }

    // Based on the CIPA PTP/IP specification default TCP port.
    var defaultPort: Int { 15740 }
}
