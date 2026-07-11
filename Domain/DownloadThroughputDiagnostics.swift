import Foundation

/// The transfer shape used for one download, captured before the file starts.
enum DownloadThroughputTransferMode: String, Codable, Equatable, Sendable {
    case getObject
    case getPartialObject
    case unknown

    var displayTitle: String {
        switch self {
        case .getObject:
            return "GetObject"
        case .getPartialObject:
            return "GetPartialObject"
        case .unknown:
            return "未知"
        }
    }
}

enum DownloadThroughputScene: String, Codable, Equatable, Sendable {
    case foreground
    case inactive
    case background

    var displayTitle: String {
        switch self {
        case .foreground:
            return "前台"
        case .inactive:
            return "切换中"
        case .background:
            return "后台"
        }
    }
}

struct DownloadThroughputChunkSample: Codable, Equatable, Sendable {
    let startedAt: Date
    let finishedAt: Date
    let bytesTransferred: Int64
    let deltaBytes: Int64
    let totalBytes: Int64
    let chunkSize: Int64
    let scene: DownloadThroughputScene

    var durationSeconds: TimeInterval {
        max(finishedAt.timeIntervalSince(startedAt), 0)
    }

    var bytesPerSecond: Double {
        guard durationSeconds > 0 else { return 0 }
        return Double(deltaBytes) / durationSeconds
    }
}

struct DownloadThroughputReport: Identifiable, Codable, Equatable, Sendable {
    let id: UUID
    let fileName: String
    let fileKind: PhotoAssetKind
    let byteSize: Int64
    let itemNumber: Int
    let totalItemCount: Int
    let transferMode: DownloadThroughputTransferMode
    let initialScene: DownloadThroughputScene
    let currentScene: DownloadThroughputScene
    let startedAt: Date
    let finishedAt: Date
    let lastBytesTransferred: Int64
    let chunkSamples: [DownloadThroughputChunkSample]
    let liveActivityUpdateCount: Int
    let queuePersistenceCount: Int
    let backgroundExpirationCount: Int
    let terminalStatus: DownloadJobStatus

    var durationSeconds: TimeInterval {
        max(finishedAt.timeIntervalSince(startedAt), 0)
    }

    var averageBytesPerSecond: Double {
        guard durationSeconds > 0 else { return 0 }
        return Double(lastBytesTransferred) / durationSeconds
    }

    var averageSpeedText: String {
        Self.speedText(bytesPerSecond: averageBytesPerSecond)
    }

    var displaySummary: String {
        "\(fileName) · \(transferMode.displayTitle) · \(currentScene.displayTitle) · \(averageSpeedText)"
    }

    static func speedText(bytesPerSecond: Double) -> String {
        guard bytesPerSecond.isFinite, bytesPerSecond > 0 else { return "0 MB/s" }
        let megabytesPerSecond = bytesPerSecond / 1_048_576
        return String(format: "%.1f MB/s", megabytesPerSecond)
    }
}

@MainActor
final class DownloadThroughputDiagnosticsRecorder {
    private var active: ActiveRecording?

    func start(
        job: DownloadJob,
        itemNumber: Int,
        totalItemCount: Int,
        transferMode: DownloadThroughputTransferMode,
        scene: DownloadThroughputScene,
        at date: Date = Date()
    ) {
        active = ActiveRecording(
            id: UUID(),
            fileName: job.fileName,
            fileKind: job.kind,
            byteSize: job.byteSize,
            itemNumber: itemNumber,
            totalItemCount: totalItemCount,
            transferMode: transferMode,
            initialScene: scene,
            currentScene: scene,
            startedAt: date,
            lastProgressAt: date,
            lastBytesTransferred: 0,
            chunkSamples: [],
            liveActivityUpdateCount: 0,
            queuePersistenceCount: 0,
            backgroundExpirationCount: 0
        )
    }

    func recordSceneChange(_ scene: DownloadThroughputScene, at date: Date = Date()) {
        active?.currentScene = scene
    }

    func recordProgress(
        _ progress: DownloadTransferProgress,
        scene: DownloadThroughputScene,
        at date: Date = Date()
    ) {
        guard var active else { return }
        let transferred = max(progress.bytesTransferred, 0)
        let deltaBytes = max(transferred - active.lastBytesTransferred, 0)
        if deltaBytes > 0 {
            active.chunkSamples.append(
                DownloadThroughputChunkSample(
                    startedAt: active.lastProgressAt,
                    finishedAt: date,
                    bytesTransferred: transferred,
                    deltaBytes: deltaBytes,
                    totalBytes: max(progress.totalBytes, transferred),
                    chunkSize: max(progress.chunkSize, 0),
                    scene: scene
                )
            )
        }
        active.lastProgressAt = date
        active.lastBytesTransferred = transferred
        active.currentScene = scene
        self.active = active
    }

    func recordLiveActivityUpdate() {
        active?.liveActivityUpdateCount += 1
    }

    func recordQueuePersistence() {
        active?.queuePersistenceCount += 1
    }

    func recordBackgroundExpiration() {
        active?.backgroundExpirationCount += 1
    }

    func finish(status: DownloadJobStatus, at date: Date = Date()) -> DownloadThroughputReport? {
        guard let active else { return nil }
        self.active = nil
        return DownloadThroughputReport(
            id: active.id,
            fileName: active.fileName,
            fileKind: active.fileKind,
            byteSize: active.byteSize,
            itemNumber: active.itemNumber,
            totalItemCount: active.totalItemCount,
            transferMode: active.transferMode,
            initialScene: active.initialScene,
            currentScene: active.currentScene,
            startedAt: active.startedAt,
            finishedAt: date,
            lastBytesTransferred: active.lastBytesTransferred,
            chunkSamples: active.chunkSamples,
            liveActivityUpdateCount: active.liveActivityUpdateCount,
            queuePersistenceCount: active.queuePersistenceCount,
            backgroundExpirationCount: active.backgroundExpirationCount,
            terminalStatus: status
        )
    }

    private struct ActiveRecording {
        let id: UUID
        let fileName: String
        let fileKind: PhotoAssetKind
        let byteSize: Int64
        let itemNumber: Int
        let totalItemCount: Int
        let transferMode: DownloadThroughputTransferMode
        let initialScene: DownloadThroughputScene
        var currentScene: DownloadThroughputScene
        let startedAt: Date
        var lastProgressAt: Date
        var lastBytesTransferred: Int64
        var chunkSamples: [DownloadThroughputChunkSample]
        var liveActivityUpdateCount: Int
        var queuePersistenceCount: Int
        var backgroundExpirationCount: Int
    }
}
