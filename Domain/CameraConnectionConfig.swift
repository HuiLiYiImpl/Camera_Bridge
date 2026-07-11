import Foundation

struct CameraConnectionConfig: Codable, Equatable, Sendable {
    var host: String
    var port: Int
    var transportMode: CameraTransportMode
    var autoExportToPhotoLibrary: Bool
    var prioritizeJPEGDownloads: Bool

    private enum CodingKeys: String, CodingKey {
        case host
        case port
        case transportMode
        case autoExportToPhotoLibrary
        case prioritizeJPEGDownloads
    }

    init(
        host: String,
        port: Int,
        transportMode: CameraTransportMode,
        autoExportToPhotoLibrary: Bool,
        prioritizeJPEGDownloads: Bool = false
    ) {
        self.host = host
        self.port = port
        self.transportMode = transportMode
        self.autoExportToPhotoLibrary = autoExportToPhotoLibrary
        self.prioritizeJPEGDownloads = prioritizeJPEGDownloads
    }

    static let `default` = CameraConnectionConfig(
        host: CameraTransportMode.experimentalNikon.defaultHost ?? "",
        port: CameraTransportMode.experimentalNikon.defaultPort,
        transportMode: .experimentalNikon,
        autoExportToPhotoLibrary: false,
        prioritizeJPEGDownloads: false
    )

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        host = try container.decode(String.self, forKey: .host)
        port = try container.decode(Int.self, forKey: .port)
        transportMode = try container.decode(CameraTransportMode.self, forKey: .transportMode)
        autoExportToPhotoLibrary = try container.decode(Bool.self, forKey: .autoExportToPhotoLibrary)
        prioritizeJPEGDownloads = try container.decodeIfPresent(Bool.self, forKey: .prioritizeJPEGDownloads) ?? false
    }

    var normalizedHost: String {
        host.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
