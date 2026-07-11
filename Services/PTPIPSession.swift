import Foundation

actor PTPIPSession {
    static let defaultChunkSize = 4_194_304
    static let minimumChunkSize = 1_048_576
    static let maximumChunkSize = 8_388_608
    static let fullObjectDownloadThreshold = 16_777_216
    static let maxChunkRetryCount = 5
    static let connectionRetryCount = 5
    static let connectionRetryDelayNanoseconds: UInt64 = 300_000_000
    let host: String
    let port: UInt16
    let commandConnection: PTPIPTCPConnection
    let eventConnection: PTPIPTCPConnection
    let initiatorGUID: Data

    var responderFriendlyName = "Nikon 相机"
    var connectionNumber: UInt32?
    var nextTransactionID: UInt32 = 1
    var isOpen = false
    var diagnostics: [String] = []
    var assetTraversalState: AssetTraversalState?
    var thumbnailOperationSupport: ThumbnailOperationSupport = .unknown
    var deviceInfo: PTPIPDeviceInfo?
    var downloadStrategy: DownloadStrategy = .fullObjectOnly
    var eventMonitorTask: Task<Void, Never>?
    var probeTask: Task<Void, Never>?
    var latestSentProbeSequence: UInt64 = 0
    var latestAcknowledgedProbeSequence: UInt64 = 0
    var isCommandChannelBusy = false
    var commandChannelWaiters: [CheckedContinuation<Void, Never>] = []

    init(host: String, port: UInt16) {
        self.host = host
        self.port = port
        self.commandConnection = PTPIPTCPConnection(host: host, port: port)
        self.eventConnection = PTPIPTCPConnection(host: host, port: port)
        self.initiatorGUID = Self.loadOrCreateInitiatorGUID()
    }

    func establishConnection() async throws -> PTPIPDeviceInfo {
        recordDiagnostic("准备连接 \(host):\(port) ...")
        return try await openSessionSequence(reason: "初始连接")
    }

    func close() async {
        await closeSessionTransport()
    }

    func fetchAssetsPage(limit: Int, resetTraversal: Bool) async throws -> PhotoAssetPage {
        try await loadAssetsPage(limit: limit, resetTraversal: resetTraversal)
    }

    func downloadAsset(handle: UInt32, expectedByteSize: Int64? = nil) async throws -> Data {
        try await downloadAssetPayload(handle: handle, expectedByteSize: expectedByteSize)
    }

    func downloadAssetToTemporaryFile(handle: UInt32, suggestedFileName: String) async throws -> URL {
        try await downloadAssetToTemporaryFile(
            handle: handle,
            suggestedFileName: suggestedFileName,
            expectedByteSize: nil,
            onProgress: nil
        )
    }

    func downloadAssetToTemporaryFile(
        handle: UInt32,
        suggestedFileName: String,
        expectedByteSize: Int64? = nil,
        onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
    ) async throws -> URL {
        try await downloadAssetToTemporaryFilePayload(
            handle: handle,
            suggestedFileName: suggestedFileName,
            expectedByteSize: expectedByteSize,
            onProgress: onProgress
        )
    }

    func throughputTransferMode(forExpectedByteSize expectedByteSize: Int64?) -> DownloadThroughputTransferMode {
        switch downloadTransferMode(forExpectedByteSize: expectedByteSize) {
        case .fullObject:
            return .getObject
        case .partialObject:
            return .getPartialObject
        }
    }

    func downloadThumbnail(handle: UInt32) async throws -> Data? {
        try await loadThumbnailData(handle: handle)
    }

    func consumeDiagnostics() -> [String] {
        let current = diagnostics
        diagnostics.removeAll()
        return current
    }
}

extension PTPIPSession {
    enum ThumbnailOperationSupport {
        case unknown
        case supported
        case unsupported
    }

    enum DownloadStrategy: Equatable {
        case fullObjectOnly
        case hybrid
    }

    enum DownloadTransferMode: Equatable {
        case fullObject
        case partialObject(initialChunkSize: Int)
    }

    struct AdaptiveChunkController: Sendable, Equatable {
        let minimumChunkSize: Int
        let maximumChunkSize: Int
        private(set) var currentChunkSize: Int
        private var consecutiveStableChunkCount = 0

        init(initialChunkSize: Int, minimumChunkSize: Int, maximumChunkSize: Int) {
            let resolvedMinimum = min(minimumChunkSize, maximumChunkSize)
            let resolvedMaximum = max(maximumChunkSize, minimumChunkSize)
            self.minimumChunkSize = resolvedMinimum
            self.maximumChunkSize = resolvedMaximum
            self.currentChunkSize = min(max(initialChunkSize, resolvedMinimum), resolvedMaximum)
        }

        mutating func requestLength(remaining: UInt64) -> Int {
            min(currentChunkSize, Int(remaining))
        }

        mutating func registerSuccess(receivedBytes: Int, requestedBytes: Int) -> Int? {
            guard requestedBytes > 0, receivedBytes >= requestedBytes else {
                consecutiveStableChunkCount = 0
                return nil
            }

            consecutiveStableChunkCount += 1
            guard consecutiveStableChunkCount >= 2, currentChunkSize < maximumChunkSize else {
                return nil
            }

            consecutiveStableChunkCount = 0
            currentChunkSize = min(currentChunkSize * 2, maximumChunkSize)
            return currentChunkSize
        }

        mutating func registerRetryableFailure() -> Int? {
            consecutiveStableChunkCount = 0
            let updatedChunkSize = max(currentChunkSize / 2, minimumChunkSize)
            guard updatedChunkSize != currentChunkSize else {
                return nil
            }

            currentChunkSize = updatedChunkSize
            return currentChunkSize
        }
    }

    enum ObjectClassification {
        case asset(PhotoAsset)
        case directory
        case unsupported((UInt32, UInt16, String))
    }

    struct AssetTraversalState {
        var queue: [UInt32]
        var nextIndex: Int
        var seenHandles: Set<UInt32>
        var loadedAssetHandles: Set<UInt32>
        var exploredDirectoryHandles: Set<UInt32>
        var unsupportedHandles: Set<UInt32>
        var handleKindHints: [UInt32: PhotoAssetKind]
        var handleCaptureDateHints: [UInt32: Date]
    }

    struct HandleDiscovery {
        var handles: [UInt32]
        var kindHints: [UInt32: PhotoAssetKind]
        var captureDateHints: [UInt32: Date]
    }

    struct NikonObjectMetaData: Sendable {
        let handle: UInt32
        let kind: PhotoAssetKind?
        let captureDate: Date?
    }

    struct BackgroundTaskState: Sendable {
        let hasEventMonitorTask: Bool
        let hasProbeTask: Bool
    }

    static func makeStreamingDownloadProgress(
        bytesTransferred: UInt64,
        reportedTotalBytes: UInt64?,
        expectedTotalBytes: Int64?,
        chunkSize: Int
    ) -> DownloadTransferProgress {
        let clampedTransferred = min(bytesTransferred, UInt64(Int64.max))
        let transferred = Int64(clampedTransferred)
        let expectedTotal = max(expectedTotalBytes ?? 0, 0)
        let reportedTotal = reportedTotalBytes.map { min($0, UInt64(Int64.max)) }
        let resolvedReportedTotal = reportedTotal.map(Int64.init) ?? 0
        let resolvedTotal = max(transferred, max(resolvedReportedTotal, expectedTotal))

        return DownloadTransferProgress(
            bytesTransferred: transferred,
            totalBytes: resolvedTotal,
            resumedCount: 0,
            currentOffset: transferred,
            chunkSize: Int64(max(chunkSize, 0))
        )
    }
}
