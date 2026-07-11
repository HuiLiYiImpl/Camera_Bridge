import Foundation

actor ExperimentalNikonTransport: CameraTransport {
    private var session: PTPIPSession?
    private var pendingDiagnostics: [String] = []

    func connect(using config: CameraConnectionConfig) async throws -> CameraSession {
        let host = config.normalizedHost
        guard !host.isEmpty else {
            throw CameraAppError.missingHost
        }

        guard (1...65535).contains(config.port) else {
            throw CameraAppError.invalidPort
        }

        if let existingSession = session {
            await existingSession.close()
        }

        let ptpSession = PTPIPSession(host: host, port: UInt16(config.port))
        do {
            let deviceInfo = try await ptpSession.establishConnection()
            session = ptpSession

            return CameraSession(
                cameraName: deviceInfo.model ?? "Nikon Z5",
                connectedHost: host,
                port: config.port,
                capabilities: [.connectionProbe, .listAssets, .downloadAssets]
            )
        } catch {
            pendingDiagnostics.append(contentsOf: await ptpSession.consumeDiagnostics())
            await ptpSession.close()
            throw mapError(error)
        }
    }

    func fetchAssetsPage(
        for session: CameraSession,
        resetTraversal: Bool,
        limit: Int
    ) async throws -> PhotoAssetPage {
        guard let ptpSession = self.session else {
            throw CameraAppError.notConnected
        }

        do {
            return try await ptpSession.fetchAssetsPage(
                limit: limit,
                resetTraversal: resetTraversal
            )
        } catch {
            throw mapError(error)
        }
    }

    func downloadAsset(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data {
        guard let ptpSession = self.session else {
            throw CameraAppError.notConnected
        }

        guard let handle = UInt32(asset.remoteIdentifier) else {
            throw CameraAppError.unsupportedOperation("无法解析相机对象句柄：\(asset.remoteIdentifier)")
        }

        do {
            return try await ptpSession.downloadAsset(
                handle: handle,
                expectedByteSize: asset.byteSize
            )
        } catch {
            throw mapError(error)
        }
    }

    func downloadAssetToTemporaryFile(_ asset: PhotoAsset, from session: CameraSession) async throws -> URL {
        try await downloadAssetToTemporaryFile(asset, from: session, onProgress: nil)
    }

    func downloadAssetToTemporaryFile(
        _ asset: PhotoAsset,
        from session: CameraSession,
        onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
    ) async throws -> URL {
        guard let ptpSession = self.session else {
            throw CameraAppError.notConnected
        }

        guard let handle = UInt32(asset.remoteIdentifier) else {
            throw CameraAppError.unsupportedOperation("无法解析相机对象句柄：\(asset.remoteIdentifier)")
        }

        do {
            let expectedSize = max(asset.byteSize, 0)
            if let onProgress {
                await onProgress(
                    DownloadTransferProgress(
                        bytesTransferred: 0,
                        totalBytes: expectedSize,
                        resumedCount: 0,
                        currentOffset: 0,
                        chunkSize: 0
                    )
                )
            }

            return try await ptpSession.downloadAssetToTemporaryFile(
                handle: handle,
                suggestedFileName: asset.fileName,
                expectedByteSize: asset.byteSize,
                onProgress: { progress in
                    if let onProgress {
                        await onProgress(progress)
                    }
                }
            )
        } catch {
            throw mapError(error)
        }
    }

    func downloadTransferMode(for asset: PhotoAsset) async -> DownloadThroughputTransferMode {
        guard let ptpSession = session else {
            return .unknown
        }

        return await ptpSession.throughputTransferMode(forExpectedByteSize: asset.byteSize)
    }

    func downloadThumbnail(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data? {
        guard let ptpSession = self.session else {
            throw CameraAppError.notConnected
        }

        guard let handle = UInt32(asset.remoteIdentifier) else {
            throw CameraAppError.unsupportedOperation("无法解析相机对象句柄：\(asset.remoteIdentifier)")
        }

        do {
            return try await ptpSession.downloadThumbnail(handle: handle)
        } catch {
            throw mapError(error)
        }
    }

    func disconnect() async {
        if let session {
            await session.close()
        }
        session = nil
    }

    func consumeDiagnostics() async -> [String] {
        var messages = pendingDiagnostics
        pendingDiagnostics.removeAll()

        if let session {
            messages.append(contentsOf: await session.consumeDiagnostics())
        }

        return messages
    }

    private func mapError(_ error: Error) -> Error {
        switch error {
        case let appError as CameraAppError:
            return appError
        case let ptpError as PTPIPError:
            return CameraAppError.networkProbeFailed(ptpError.localizedDescription)
        default:
            return CameraAppError.networkProbeFailed(error.localizedDescription)
        }
    }
}
