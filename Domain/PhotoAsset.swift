import Foundation

enum PhotoAssetKind: String, Codable, CaseIterable, Sendable {
    case png
    case jpeg
    case raw
    case movie

    var badgeTitle: String {
        switch self {
        case .png:
            return "PNG"
        case .jpeg:
            return "JPEG"
        case .raw:
            return "RAW"
        case .movie:
            return "MOV"
        }
    }

    var systemImageName: String {
        switch self {
        case .png, .jpeg:
            return "photo"
        case .raw:
            return "camera.aperture"
        case .movie:
            return "film"
        }
    }
}

struct PhotoAssetThumbnailInfo: Equatable, Hashable, Sendable {
    let formatCode: UInt16
    let byteSize: Int64
    let pixelWidth: Int
    let pixelHeight: Int
}

struct PhotoAsset: Identifiable, Equatable, Hashable, Sendable {
    let id: UUID
    let remoteIdentifier: String
    let fileName: String
    let kind: PhotoAssetKind
    let byteSize: Int64
    let captureDate: Date
    let thumbnailInfo: PhotoAssetThumbnailInfo?

    init(
        id: UUID = UUID(),
        remoteIdentifier: String,
        fileName: String,
        kind: PhotoAssetKind,
        byteSize: Int64,
        captureDate: Date,
        thumbnailInfo: PhotoAssetThumbnailInfo? = nil
    ) {
        self.id = id
        self.remoteIdentifier = remoteIdentifier
        self.fileName = fileName
        self.kind = kind
        self.byteSize = byteSize
        self.captureDate = captureDate
        self.thumbnailInfo = thumbnailInfo
    }
}

struct PhotoAssetPage: Sendable {
    let assets: [PhotoAsset]
    let hasMore: Bool
}
