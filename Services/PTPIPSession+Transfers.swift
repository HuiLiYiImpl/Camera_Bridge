import Foundation

extension PTPIPSession {
    func downloadAssetPayload(handle: UInt32, expectedByteSize: Int64?) async throws -> Data {
        try ensureOpen()
        switch downloadTransferMode(forExpectedByteSize: expectedByteSize) {
        case .fullObject:
            recordDiagnostic("开始整包下载对象 handle=\(handle)")
            do {
                return try await requestDataIn(
                    operation: .getObject,
                    transactionID: consumeTransactionID(),
                    parameters: [handle]
                )
            } catch {
                guard shouldFallbackToPartialDownload(after: error) else {
                    throw error
                }

                let totalLength = try await resolvedExpectedByteSize(
                    for: handle,
                    expectedByteSize: expectedByteSize
                )
                recordDiagnostic(
                    "整包下载失败，回退到分块续传 handle=\(handle)，初始 chunk=\(Self.chunkSizeLabel(Self.defaultChunkSize))。"
                )
                return try await downloadAssetDataInChunks(
                    handle: handle,
                    totalLength: totalLength,
                    initialChunkSize: Self.defaultChunkSize
                )
            }

        case .partialObject(let initialChunkSize):
            let totalLength = try await resolvedExpectedByteSize(
                for: handle,
                expectedByteSize: expectedByteSize
            )
            recordDiagnostic(
                "开始分块下载对象 handle=\(handle)，初始 chunk=\(Self.chunkSizeLabel(initialChunkSize))。"
            )
            return try await downloadAssetDataInChunks(
                handle: handle,
                totalLength: totalLength,
                initialChunkSize: initialChunkSize
            )
        }
    }

    func downloadAssetToTemporaryFilePayload(
        handle: UInt32,
        suggestedFileName: String,
        expectedByteSize: Int64?,
        onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
    ) async throws -> URL {
        try ensureOpen()
        switch downloadTransferMode(forExpectedByteSize: expectedByteSize) {
        case .fullObject:
            recordDiagnostic("开始流式整包下载对象 handle=\(handle)")
            do {
                return try await requestDataInToTemporaryFile(
                    operation: .getObject,
                    transactionID: consumeTransactionID(),
                    parameters: [handle],
                    suggestedFileName: suggestedFileName,
                    expectedTotalBytes: expectedByteSize,
                    onProgress: onProgress
                )
            } catch {
                guard shouldFallbackToPartialDownload(after: error) else {
                    throw error
                }

                let totalLength = try await resolvedExpectedByteSize(
                    for: handle,
                    expectedByteSize: expectedByteSize
                )
                recordDiagnostic(
                    "整包流式下载失败，回退到分块续传 handle=\(handle)，初始 chunk=\(Self.chunkSizeLabel(Self.defaultChunkSize))。"
                )
                return try await downloadObjectToTemporaryFileInChunks(
                    handle: handle,
                    totalLength: totalLength,
                    suggestedFileName: suggestedFileName,
                    initialChunkSize: Self.defaultChunkSize,
                    onProgress: onProgress
                )
            }

        case .partialObject(let initialChunkSize):
            let totalLength = try await resolvedExpectedByteSize(
                for: handle,
                expectedByteSize: expectedByteSize
            )
            recordDiagnostic(
                "开始分块流式下载对象 handle=\(handle)，初始 chunk=\(Self.chunkSizeLabel(initialChunkSize))。"
            )
            return try await downloadObjectToTemporaryFileInChunks(
                handle: handle,
                totalLength: totalLength,
                suggestedFileName: suggestedFileName,
                initialChunkSize: initialChunkSize,
                onProgress: onProgress
            )
        }
    }

    func loadThumbnailData(handle: UInt32) async throws -> Data? {
        try ensureOpen()

        if thumbnailOperationSupport == .unsupported {
            return nil
        }

        recordDiagnostic("开始读取缩略图 handle=\(handle)")

        do {
            let data = try await requestDataIn(
                operation: .getThumb,
                transactionID: consumeTransactionID(),
                parameters: [handle]
            )
            thumbnailOperationSupport = .supported
            return data.isEmpty ? nil : data
        } catch let error as PTPIPError {
            switch error {
            case .unexpectedResponse(code: PTPResponseCode.noThumbnailPresent.rawValue):
                thumbnailOperationSupport = .supported
                recordDiagnostic("对象 handle=\(handle) 没有可用缩略图。")
                return nil
            case .unexpectedResponse(code: PTPResponseCode.operationNotSupported.rawValue):
                thumbnailOperationSupport = .unsupported
                recordDiagnostic("相机未公开 GetThumb，将回退到原始文件读取。")
                return nil
            default:
                throw error
            }
        }
    }

    func configureDownloadStrategy(using deviceInfo: PTPIPDeviceInfo) {
        if deviceInfo.supportsOperation(.getPartialObject) {
            downloadStrategy = .hybrid
            recordDiagnostic(
                "相机支持 GetPartialObject，启用混合下载策略：小文件整包，大文件分块，chunk 在 \(Self.chunkSizeLabel(Self.minimumChunkSize))-\(Self.chunkSizeLabel(Self.maximumChunkSize)) 之间自适应。"
            )
        } else {
            downloadStrategy = .fullObjectOnly
            recordDiagnostic("相机未声明 GetPartialObject，回退为整包下载。")
        }
    }

    func normalizedExpectedByteSize(_ expectedByteSize: Int64?) -> UInt64? {
        guard let expectedByteSize, expectedByteSize > 0 else {
            return nil
        }

        return UInt64(expectedByteSize)
    }

    func downloadTransferMode(forExpectedByteSize expectedByteSize: Int64?) -> DownloadTransferMode {
        switch downloadStrategy {
        case .fullObjectOnly:
            return .fullObject
        case .hybrid:
            guard
                let resolvedSize = normalizedExpectedByteSize(expectedByteSize),
                resolvedSize > UInt64(Self.fullObjectDownloadThreshold)
            else {
                return .fullObject
            }
            return .partialObject(initialChunkSize: Self.defaultChunkSize)
        }
    }

    func resolvedExpectedByteSize(for handle: UInt32, expectedByteSize: Int64?) async throws -> UInt64 {
        if let resolvedSize = normalizedExpectedByteSize(expectedByteSize) {
            return resolvedSize
        }

        let objectInfo = try await fetchObjectInfo(handle: handle)
        return UInt64(objectInfo.compressedSize)
    }

    func shouldFallbackToPartialDownload(after error: Error) -> Bool {
        guard downloadStrategy == .hybrid else {
            return false
        }

        return shouldRetryChunkTransfer(after: error)
    }

    func downloadAssetDataInChunks(
        handle: UInt32,
        totalLength: UInt64,
        initialChunkSize: Int
    ) async throws -> Data {
        var collected = Data(capacity: Int(totalLength))
        try await downloadObjectInChunks(
            handle: handle,
            totalLength: totalLength,
            initialChunkSize: initialChunkSize
        ) { chunk in
            collected.append(chunk)
        }
        return collected
    }

    func downloadObjectInChunks(
        handle: UInt32,
        totalLength: UInt64,
        initialChunkSize: Int,
        onChunk: (Data) throws -> Void,
        onProgress: (@Sendable (DownloadTransferProgress) async -> Void)? = nil
    ) async throws {
        if totalLength == 0 {
            return
        }

        guard totalLength <= UInt64(UInt32.max) else {
            throw CameraAppError.unsupportedOperation("当前版本暂不支持大于 4 GiB 的可恢复下载。")
        }

        try await withExclusiveCommandChannelAccess {
            var chunkController = AdaptiveChunkController(
                initialChunkSize: initialChunkSize,
                minimumChunkSize: Self.minimumChunkSize,
                maximumChunkSize: Self.maximumChunkSize
            )
            var offset: UInt64 = 0
            var retryCount = 0
            var resumedCount = 0

            while offset < totalLength {
                try Task.checkCancellation()
                let remaining = totalLength - offset
                let requestLength = UInt64(chunkController.requestLength(remaining: remaining))

                do {
                    let payload = try await requestDataInDirect(
                        operation: .getPartialObject,
                        transactionID: consumeTransactionID(),
                        parameters: [handle, UInt32(offset), UInt32(requestLength)]
                    )
                    guard !payload.isEmpty else {
                        throw PTPIPError.malformedPayload("GetPartialObject 返回了空数据。")
                    }

                    try Task.checkCancellation()
                    try onChunk(payload)
                    offset += UInt64(payload.count)
                    retryCount = 0
                    if let updatedChunkSize = chunkController.registerSuccess(
                        receivedBytes: payload.count,
                        requestedBytes: Int(requestLength)
                    ) {
                        recordDiagnostic("链路稳定，分块大小上调到 \(Self.chunkSizeLabel(updatedChunkSize))。")
                    }

                    if let onProgress {
                        await onProgress(
                            DownloadTransferProgress(
                                bytesTransferred: Int64(offset),
                                totalBytes: Int64(totalLength),
                                resumedCount: resumedCount,
                                currentOffset: Int64(offset),
                                chunkSize: Int64(requestLength)
                            )
                        )
                    }
                } catch {
                    guard shouldRetryChunkTransfer(after: error), retryCount < Self.maxChunkRetryCount else {
                        throw error
                    }

                    retryCount += 1
                    resumedCount += 1
                    if let updatedChunkSize = chunkController.registerRetryableFailure() {
                        recordDiagnostic("检测到传输抖动，分块大小下调到 \(Self.chunkSizeLabel(updatedChunkSize))。")
                    }

                    if let onProgress {
                        await onProgress(
                            DownloadTransferProgress(
                                bytesTransferred: Int64(offset),
                                totalBytes: Int64(totalLength),
                                resumedCount: resumedCount,
                                currentOffset: Int64(offset),
                                chunkSize: Int64(requestLength)
                            )
                        )
                    }

                    try Task.checkCancellation()
                    try await prepareChunkRetry(
                        after: error,
                        offset: offset,
                        totalLength: totalLength,
                        attempt: retryCount
                    )
                }
            }
        }
    }

    func downloadObjectToTemporaryFileInChunks(
        handle: UInt32,
        totalLength: UInt64,
        suggestedFileName: String,
        initialChunkSize: Int,
        onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
    ) async throws -> URL {
        let temporaryURL = Self.makeTemporaryDownloadURL(suggestedFileName: suggestedFileName)
        guard let outputStream = OutputStream(url: temporaryURL, append: false) else {
            throw CameraAppError.fileSystemFailure("无法创建临时下载文件。")
        }

        outputStream.open()
        defer { outputStream.close() }

        do {
            try await downloadObjectInChunks(
                handle: handle,
                totalLength: totalLength,
                initialChunkSize: initialChunkSize
            ) { chunk in
                try Self.write(chunk, to: outputStream)
            } onProgress: { progress in
                if let onProgress {
                    await onProgress(progress)
                }
            }
            return temporaryURL
        } catch {
            try? FileManager.default.removeItem(at: temporaryURL)
            throw error
        }
    }

    func shouldRetryChunkTransfer(after error: Error) -> Bool {
        guard let ptpError = error as? PTPIPError else {
            return false
        }

        switch ptpError {
        case .timeout, .connectionClosed:
            return true
        case .unexpectedResponse(let code):
            return code == PTPResponseCode.deviceBusy.rawValue ||
                code == PTPResponseCode.incompleteTransfer.rawValue ||
                code == PTPResponseCode.transactionCancelled.rawValue
        default:
            return false
        }
    }

    func prepareChunkRetry(
        after error: Error,
        offset: UInt64,
        totalLength: UInt64,
        attempt: Int
    ) async throws {
        recordDiagnostic(
            "分块传输在 \(offset)/\(totalLength) 处中断，准备第 \(attempt) 次恢复：\(error.localizedDescription)"
        )

        if case PTPIPError.unexpectedResponse(code: PTPResponseCode.deviceBusy.rawValue) = error {
            try await Task.sleep(nanoseconds: UInt64(attempt) * 50_000_000)
            return
        }

        _ = try await openSessionSequence(reason: "断流恢复")
    }

    static func makeTemporaryDownloadURL(suggestedFileName: String) -> URL {
        let ext = URL(fileURLWithPath: suggestedFileName).pathExtension
        let fileName = ext.isEmpty ? UUID().uuidString : UUID().uuidString + "." + ext
        return FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
    }

    static func chunkSizeLabel(_ chunkSize: Int) -> String {
        "\(max(chunkSize / 1_048_576, 1))MB"
    }

    static func write(_ data: Data, to outputStream: OutputStream) throws {
        if data.isEmpty {
            return
        }

        try data.withUnsafeBytes { rawBuffer in
            guard let baseAddress = rawBuffer.bindMemory(to: UInt8.self).baseAddress else {
                return
            }

            var offset = 0
            while offset < data.count {
                let written = outputStream.write(baseAddress.advanced(by: offset), maxLength: data.count - offset)
                if written < 0 {
                    throw CameraAppError.fileSystemFailure(
                        outputStream.streamError?.localizedDescription ?? "写入临时下载文件失败。"
                    )
                }
                if written == 0 {
                    throw CameraAppError.fileSystemFailure("写入临时下载文件时没有写入任何数据。")
                }
                offset += written
            }
        }
    }
}
