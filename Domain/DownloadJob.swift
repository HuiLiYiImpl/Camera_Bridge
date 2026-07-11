import Foundation

enum DownloadJobStatus: String, Codable, CaseIterable, Sendable {
    case queued
    case running
    case paused
    case interrupted
    case cancelled
    case completed
    case failed

    var isTerminal: Bool {
        switch self {
        case .cancelled, .completed, .failed:
            return true
        case .queued, .running, .paused, .interrupted:
            return false
        }
    }

    var canResume: Bool {
        switch self {
        case .queued, .paused, .interrupted:
            return true
        case .running, .cancelled, .completed, .failed:
            return false
        }
    }

    var displayTitle: String {
        switch self {
        case .queued:
            return "等待中"
        case .running:
            return "下载中"
        case .paused:
            return "已暂停"
        case .interrupted:
            return "已中断"
        case .cancelled:
            return "已取消"
        case .completed:
            return "已完成"
        case .failed:
            return "失败"
        }
    }
}

struct DownloadJob: Identifiable, Codable, Equatable, Sendable {
    let id: UUID
    let remoteIdentifier: String
    let fileName: String
    let kind: PhotoAssetKind
    let byteSize: Int64
    let captureDate: Date
    let autoExportToPhotoLibrary: Bool
    var status: DownloadJobStatus
    var bytesTransferred: Int64
    var totalBytes: Int64
    var currentOffset: Int64
    var resumedCount: Int
    let createdAt: Date
    var startedAt: Date?
    var completedAt: Date?
    var updatedAt: Date
    var errorMessage: String?

    init(
        id: UUID = UUID(),
        remoteIdentifier: String,
        fileName: String,
        kind: PhotoAssetKind,
        byteSize: Int64,
        captureDate: Date,
        autoExportToPhotoLibrary: Bool,
        status: DownloadJobStatus = .queued,
        bytesTransferred: Int64 = 0,
        totalBytes: Int64 = 0,
        currentOffset: Int64 = 0,
        resumedCount: Int = 0,
        createdAt: Date = Date(),
        startedAt: Date? = nil,
        completedAt: Date? = nil,
        updatedAt: Date = Date(),
        errorMessage: String? = nil
    ) {
        self.id = id
        self.remoteIdentifier = remoteIdentifier
        self.fileName = fileName
        self.kind = kind
        self.byteSize = byteSize
        self.captureDate = captureDate
        self.autoExportToPhotoLibrary = autoExportToPhotoLibrary
        self.status = status
        self.bytesTransferred = bytesTransferred
        self.totalBytes = totalBytes
        self.currentOffset = currentOffset
        self.resumedCount = resumedCount
        self.createdAt = createdAt
        self.startedAt = startedAt
        self.completedAt = completedAt
        self.updatedAt = updatedAt
        self.errorMessage = errorMessage
    }

    init(asset: PhotoAsset, autoExportToPhotoLibrary: Bool, createdAt: Date = Date()) {
        self.init(
            remoteIdentifier: asset.remoteIdentifier,
            fileName: asset.fileName,
            kind: asset.kind,
            byteSize: asset.byteSize,
            captureDate: asset.captureDate,
            autoExportToPhotoLibrary: autoExportToPhotoLibrary,
            totalBytes: max(asset.byteSize, 0),
            createdAt: createdAt,
            updatedAt: createdAt
        )
    }

    var asset: PhotoAsset {
        PhotoAsset(
            remoteIdentifier: remoteIdentifier,
            fileName: fileName,
            kind: kind,
            byteSize: byteSize,
            captureDate: captureDate
        )
    }

    var fractionCompleted: Double {
        guard totalBytes > 0 else { return 0 }
        let progress = Double(bytesTransferred) / Double(totalBytes)
        return min(max(progress, 0), 1)
    }

    var percentageText: String {
        "\(Int((fractionCompleted * 100).rounded()))%"
    }
}
