import Foundation

struct DownloadTransferProgress: Equatable, Sendable {
    let bytesTransferred: Int64
    let totalBytes: Int64
    let resumedCount: Int
    let currentOffset: Int64
    let chunkSize: Int64

    var fractionCompleted: Double {
        guard totalBytes > 0 else { return 0 }
        let progress = Double(bytesTransferred) / Double(totalBytes)
        return min(max(progress, 0), 1)
    }
}

protocol CameraTransport: Sendable {
    func connect(using config: CameraConnectionConfig) async throws -> CameraSession
    func fetchAssetsPage(
        for session: CameraSession,
        resetTraversal: Bool,
        limit: Int
    ) async throws -> PhotoAssetPage
    func downloadThumbnail(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data?
    func downloadAsset(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data
    func downloadAssetToTemporaryFile(_ asset: PhotoAsset, from session: CameraSession) async throws -> URL
    func downloadAssetToTemporaryFile(
        _ asset: PhotoAsset,
        from session: CameraSession,
        onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
    ) async throws -> URL
    func downloadTransferMode(for asset: PhotoAsset) async -> DownloadThroughputTransferMode
    func consumeDiagnostics() async -> [String]
    func disconnect() async
}

extension CameraTransport {
    func downloadThumbnail(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data? {
        nil
    }

    func downloadAssetToTemporaryFile(_ asset: PhotoAsset, from session: CameraSession) async throws -> URL {
        let data = try await downloadAsset(asset, from: session)
        let ext = URL(fileURLWithPath: asset.fileName).pathExtension
        let fileName = ext.isEmpty ? UUID().uuidString : UUID().uuidString + "." + ext
        let temporaryURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        do {
            try data.write(to: temporaryURL, options: .atomic)
        } catch {
            throw CameraAppError.fileSystemFailure("无法写入临时下载文件：\(error.localizedDescription)")
        }

        return temporaryURL
    }

    func downloadAssetToTemporaryFile(
        _ asset: PhotoAsset,
        from session: CameraSession,
        onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
    ) async throws -> URL {
        try await downloadAssetToTemporaryFile(asset, from: session)
    }

    func downloadTransferMode(for asset: PhotoAsset) async -> DownloadThroughputTransferMode {
        .unknown
    }
}
