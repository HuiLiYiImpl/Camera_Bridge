import Foundation

protocol AssetThumbnailServing: Sendable {
    func clear() async
    func thumbnailData(
        for asset: PhotoAsset,
        using transport: any CameraTransport,
        session: CameraSession
    ) async -> Data?
    func previewData(
        for asset: PhotoAsset,
        using transport: any CameraTransport,
        session: CameraSession
    ) async -> Data?
}

extension AssetThumbnailService: AssetThumbnailServing {}
