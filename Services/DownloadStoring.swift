import Foundation

protocol DownloadStoring: Sendable {
    func downloadsDirectoryURL() async throws -> URL
    func listRecords() async throws -> [DownloadRecord]
    func loadDownloadJobs() async throws -> DownloadQueueState
    func saveDownloadQueueState(_ state: DownloadQueueState) async throws
    func upsertDownloadJob(_ job: DownloadJob, queueStatus: DownloadQueueStatus, activeJobID: UUID?) async throws -> DownloadQueueState
    func removeDownloadJobs(ids: [UUID], queueStatus: DownloadQueueStatus, activeJobID: UUID?) async throws -> DownloadQueueState
    func markInterruptedRunningJobs(reason: String?) async throws -> DownloadQueueState
    func storeDownloadedFile(at sourceURL: URL, from asset: PhotoAsset) async throws -> DownloadRecord
    func markExported(recordID: UUID) async throws -> DownloadRecord
}

extension DownloadStore: DownloadStoring {}
