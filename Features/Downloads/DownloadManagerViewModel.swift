import Foundation
import SwiftUI

enum DownloadAssetPrioritizer {
    static func reordered(_ assets: [PhotoAsset], prioritizeJPEGDownloads: Bool) -> [PhotoAsset] {
        guard prioritizeJPEGDownloads else {
            return assets
        }

        let jpegLikeAssets = assets.filter { $0.kind == .jpeg || $0.kind == .png }
        let deferredAssets = assets.filter { $0.kind != .jpeg && $0.kind != .png }

        return jpegLikeAssets + deferredAssets
    }
}

@MainActor
final class DownloadManagerViewModel: ObservableObject {
    @Published var downloads: [DownloadRecord] = []
    @Published var downloadDirectoryPath = ""
    @Published var queuedJobs: [DownloadJob] = []
    @Published var queueStatus: DownloadQueueStatus = .idle
    @Published var activeDownloadProgress: ActiveDownloadProgress?
    @Published var throughputReports: [DownloadThroughputReport] = []
    @Published var isDownloading = false

    private let downloadStore: any DownloadStoring
    private let photoLibraryExportService: any PhotoLibraryExporting
    private let sessionCoordinator: CameraSessionCoordinator
    private let shell: AppShellViewModel
    private let backgroundExecutionService: BackgroundDownloadExecutionService
    private let liveActivityController: DownloadLiveActivityController
    private let throughputDiagnosticsRecorder = DownloadThroughputDiagnosticsRecorder()

    private var queueState = DownloadQueueState()
    private var queueID = UUID()
    private var queueRunnerTask: Task<Void, Never>?
    private var queueStopReason: QueueStopReason = .none
    private var currentTemporaryURL: URL?
    private var currentThroughputScene: DownloadThroughputScene = .foreground
    private var shouldPauseQueueAfterCurrentBackgroundJob = false
    private var lastProgressPersistenceDate = Date.distantPast
    private var lastLiveActivityUpdateDate = Date.distantPast

    private enum QueueStopReason {
        case none
        case pause
        case cancelActiveJob
        case interrupted(String?)
    }

    init(
        downloadStore: any DownloadStoring,
        photoLibraryExportService: any PhotoLibraryExporting,
        sessionCoordinator: CameraSessionCoordinator,
        shell: AppShellViewModel,
        backgroundExecutionService: BackgroundDownloadExecutionService = BackgroundDownloadExecutionService(),
        liveActivityController: DownloadLiveActivityController = DownloadLiveActivityController()
    ) {
        self.downloadStore = downloadStore
        self.photoLibraryExportService = photoLibraryExportService
        self.sessionCoordinator = sessionCoordinator
        self.shell = shell
        self.backgroundExecutionService = backgroundExecutionService
        self.liveActivityController = liveActivityController
    }

    func canDownload(_ assets: [PhotoAsset], session: CameraSession?) -> Bool {
        session?.capabilities.contains(.downloadAssets) == true && !assets.isEmpty
    }

    var canPauseQueue: Bool {
        queueState.status == .running && queueState.activeJobID != nil
    }

    var canResumeQueue: Bool {
        !isDownloading && sessionCoordinator.hasActiveSession && queuedJobs.contains(where: { $0.status.canResume })
    }

    var hasQueuedJobs: Bool {
        !queuedJobs.isEmpty
    }

    func refreshDownloads() async {
        do {
            let directory = try await downloadStore.downloadsDirectoryURL()
            let records = try await downloadStore.listRecords()
            downloadDirectoryPath = directory.path
            downloads = records
        } catch {
            shell.handle(error)
        }
    }

    func loadPersistedQueue() async {
        do {
            let state = try await downloadStore.markInterruptedRunningJobs(reason: "下载在后台被中断，可重新继续。")
            applyQueueState(state)
        } catch {
            shell.handle(error)
        }
    }

    @discardableResult
    func downloadAssets(
        _ assets: [PhotoAsset],
        autoExportToPhotoLibrary: Bool,
        prioritizeJPEGDownloads: Bool
    ) async -> Bool {
        await enqueueDownloads(
            assets,
            autoExportToPhotoLibrary: autoExportToPhotoLibrary,
            prioritizeJPEGDownloads: prioritizeJPEGDownloads
        )
    }

    @discardableResult
    func enqueueDownloads(
        _ assets: [PhotoAsset],
        autoExportToPhotoLibrary: Bool,
        prioritizeJPEGDownloads: Bool
    ) async -> Bool {
        guard sessionCoordinator.hasActiveSession else {
            shell.handle(CameraAppError.notConnected)
            return false
        }

        guard !assets.isEmpty else { return true }

        let orderedAssets = DownloadAssetPrioritizer.reordered(
            assets,
            prioritizeJPEGDownloads: prioritizeJPEGDownloads
        )
        let didReorder = orderedAssets.map(\.id) != assets.map(\.id)
        let prioritizedJPEGCount = orderedAssets.filter { $0.kind == .jpeg || $0.kind == .png }.count
        let deferredAssetCount = max(orderedAssets.count - prioritizedJPEGCount, 0)
        let createdAt = Date()
        let newJobs = orderedAssets.enumerated().map { index, asset in
            DownloadJob(
                asset: asset,
                autoExportToPhotoLibrary: autoExportToPhotoLibrary,
                createdAt: createdAt.addingTimeInterval(Double(index) * 0.001)
            )
        }

        queueState.jobs.append(contentsOf: newJobs)
        if queueState.status == .idle {
            queueState.status = .running
        }
        syncPublishedState()

        do {
            try await persistQueueState()
        } catch {
            shell.handle(error)
            return false
        }

        shell.appendLog("已加入下载队列：\(newJobs.count) 个文件。")
        if prioritizeJPEGDownloads, didReorder, prioritizedJPEGCount > 0, deferredAssetCount > 0 {
            shell.appendLog("JPEG 优先模式已启用，先下载 \(prioritizedJPEGCount) 个 JPEG/PNG，再处理剩余 \(deferredAssetCount) 个 RAW/视频。")
        }

        await startQueueIfPossible()
        return true
    }

    func startQueueIfPossible() async {
        guard queueRunnerTask == nil else { return }
        guard queueState.jobs.contains(where: { $0.status.canResume }) else {
            queueState.status = .idle
            queueState.activeJobID = nil
            syncPublishedState()
            return
        }
        guard sessionCoordinator.hasActiveSession else {
            queueState.status = .interrupted
            syncPublishedState()
            do {
                try await persistQueueState()
            } catch {
                shell.handle(error)
            }
            return
        }

        queueStopReason = .none
        queueState.status = .running
        syncPublishedState()

        do {
            try await persistQueueState()
        } catch {
            shell.handle(error)
            return
        }

        queueRunnerTask = Task { [weak self] in
            await self?.runQueue()
        }
    }

    func pauseQueue() {
        if let queueRunnerTask {
            queueStopReason = .pause
            queueRunnerTask.cancel()
            return
        }

        guard queueState.hasPendingWork else { return }
        queueState.status = .paused
        queueState.activeJobID = nil
        syncPublishedState()
        Task {
            try? await persistQueueState()
        }
    }

    func resumeInterruptedDownloads() async {
        guard queuedJobs.contains(where: { $0.status.canResume }) else { return }
        queueState.status = .running
        syncPublishedState()

        do {
            try await persistQueueState()
        } catch {
            shell.handle(error)
            return
        }

        await startQueueIfPossible()
    }

    func retryJob(_ job: DownloadJob) async {
        guard let index = queueState.jobs.firstIndex(where: { $0.id == job.id }) else { return }
        queueState.jobs[index].status = .queued
        queueState.jobs[index].bytesTransferred = 0
        queueState.jobs[index].totalBytes = max(queueState.jobs[index].byteSize, 0)
        queueState.jobs[index].currentOffset = 0
        queueState.jobs[index].resumedCount = 0
        queueState.jobs[index].completedAt = nil
        queueState.jobs[index].updatedAt = Date()
        queueState.jobs[index].errorMessage = nil
        if queueState.status == .idle {
            queueState.status = .paused
        }
        syncPublishedState()

        do {
            try await persistQueueState()
        } catch {
            shell.handle(error)
            return
        }

        await startQueueIfPossible()
    }

    func cancelJob(_ job: DownloadJob) {
        guard let index = queueState.jobs.firstIndex(where: { $0.id == job.id }) else { return }

        if queueState.activeJobID == job.id, queueState.jobs[index].status == .running {
            queueStopReason = .cancelActiveJob
            queueRunnerTask?.cancel()
            return
        }

        queueState.jobs[index].status = .cancelled
        queueState.jobs[index].completedAt = Date()
        queueState.jobs[index].updatedAt = Date()
        queueState.jobs[index].errorMessage = "用户取消了下载。"
        normalizeQueueStatusAfterManualUpdate()
        syncPublishedState()

        Task {
            try? await persistQueueState()
        }
    }

    func cancelAllDownloads() {
        let now = Date()
        for index in queueState.jobs.indices where !queueState.jobs[index].status.isTerminal {
            queueState.jobs[index].status = .cancelled
            queueState.jobs[index].completedAt = now
            queueState.jobs[index].updatedAt = now
            queueState.jobs[index].errorMessage = "用户取消了下载。"
        }
        queueState.activeJobID = nil
        queueState.status = .idle
        activeDownloadProgress = nil
        syncPublishedState()

        if queueRunnerTask != nil {
            queueStopReason = .cancelActiveJob
            queueRunnerTask?.cancel()
        }

        Task {
            try? await persistQueueState()
        }
    }

    func clearFinishedJobs() {
        queueState.jobs.removeAll { $0.status.isTerminal }
        normalizeQueueStatusAfterManualUpdate()
        syncPublishedState()

        Task {
            try? await persistQueueState()
        }
    }

    func handleScenePhaseChange(_ scenePhase: ScenePhase) {
        switch scenePhase {
        case .active:
            currentThroughputScene = .foreground
            shouldPauseQueueAfterCurrentBackgroundJob = false
            throughputDiagnosticsRecorder.recordSceneChange(.foreground)
            backgroundExecutionService.end()
            Task {
                try? await persistQueueState()
            }
        case .inactive:
            currentThroughputScene = .inactive
            throughputDiagnosticsRecorder.recordSceneChange(.inactive)
            Task {
                try? await persistQueueState()
            }
        case .background:
            currentThroughputScene = .background
            shouldPauseQueueAfterCurrentBackgroundJob = isDownloading
            throughputDiagnosticsRecorder.recordSceneChange(.background)
            if isDownloading {
                backgroundExecutionService.beginIfNeeded(name: "Nikon download") { [weak self] in
                    self?.throughputDiagnosticsRecorder.recordBackgroundExpiration()
                    self?.interruptActiveDownload(reason: "iOS 已暂停后台传输。重新打开 App 并连接相机后可继续。")
                }
            }
            Task {
                try? await persistQueueState()
            }
        @unknown default:
            break
        }
    }

    private func runQueue() async {
        defer {
            queueRunnerTask = nil
            queueStopReason = .none
            currentTemporaryURL = nil
            backgroundExecutionService.end()
            lastProgressPersistenceDate = .distantPast
            lastLiveActivityUpdateDate = .distantPast
        }

        while !Task.isCancelled {
            guard let job = nextRunnableJob() else {
                queueState.activeJobID = nil
                queueState.status = .idle
                activeDownloadProgress = nil
                syncPublishedState()
                do {
                    try await persistQueueState()
                } catch {
                    shell.handle(error)
                }
                return
            }

            guard let session = sessionCoordinator.activeSession,
                  let transport = sessionCoordinator.activeTransport else {
                updateJob(job.id) { queuedJob in
                    queuedJob.status = .interrupted
                    queuedJob.updatedAt = Date()
                    queuedJob.errorMessage = "请重新连接 Nikon 相机后继续下载。"
                }
                queueState.activeJobID = nil
                queueState.status = .interrupted
                syncPublishedState()
                do {
                    try await persistQueueState()
                } catch {
                    shell.handle(error)
                }
                return
            }

            let itemNumber = (queueState.jobs.firstIndex(where: { $0.id == job.id }) ?? 0) + 1
            let totalItemCount = queueState.jobs.count
            updateJob(job.id) { queuedJob in
                queuedJob.status = .running
                queuedJob.startedAt = queuedJob.startedAt ?? Date()
                queuedJob.updatedAt = Date()
                queuedJob.errorMessage = nil
                queuedJob.totalBytes = max(queuedJob.totalBytes, queuedJob.byteSize)
            }
            queueState.activeJobID = job.id
            queueState.status = .running
            syncPublishedState()
            do {
                try await persistQueueState()
            } catch {
                shell.handle(error)
            }

            let transferMode = await transport.downloadTransferMode(for: job.asset)
            throughputDiagnosticsRecorder.start(
                job: currentJob(id: job.id) ?? job,
                itemNumber: itemNumber,
                totalItemCount: totalItemCount,
                transferMode: transferMode,
                scene: currentThroughputScene
            )

            shell.appendLog("开始下载 \(job.fileName)（\(itemNumber)/\(totalItemCount)）")
            backgroundExecutionService.beginIfNeeded(name: "Nikon download") { [weak self] in
                self?.throughputDiagnosticsRecorder.recordBackgroundExpiration()
                self?.interruptActiveDownload(reason: "iOS 已暂停后台传输。重新打开 App 并连接相机后可继续。")
            }
            updateLiveActivity(forJobID: job.id, force: true)
            updateActiveDownloadProgress(
                for: job.asset,
                itemNumber: itemNumber,
                totalItemCount: totalItemCount,
                transportProgress: DownloadTransferProgress(
                    bytesTransferred: currentJob(id: job.id)?.bytesTransferred ?? 0,
                    totalBytes: max(currentJob(id: job.id)?.totalBytes ?? 0, job.byteSize),
                    resumedCount: currentJob(id: job.id)?.resumedCount ?? 0,
                    currentOffset: currentJob(id: job.id)?.currentOffset ?? 0,
                    chunkSize: 0
                )
            )

            do {
                try Task.checkCancellation()
                let temporaryURL = try await transport.downloadAssetToTemporaryFile(
                    job.asset,
                    from: session
                ) { progress in
                    await MainActor.run {
                        self.applyProgress(progress, toJobID: job.id)
                    }
                }
                currentTemporaryURL = temporaryURL

                let measuredSize = Self.fileSize(at: temporaryURL)
                let finalSize = max(measuredSize, max(currentJob(id: job.id)?.totalBytes ?? 0, job.byteSize))
                finalizeProgress(forJobID: job.id, finalSize: finalSize)

                var record = try await downloadStore.storeDownloadedFile(at: temporaryURL, from: job.asset)
                currentTemporaryURL = nil
                shell.appendLog("已将 \(record.fileName) 保存到应用存储。")

                if job.autoExportToPhotoLibrary {
                    do {
                        try await photoLibraryExportService.exportFile(at: record.savedURL)
                        record = try await downloadStore.markExported(recordID: record.id)
                        shell.appendLog("已将 \(record.fileName) 导出到系统相册。")
                    } catch {
                        shell.appendLog("\(record.fileName) 导出到系统相册失败：\(shell.userFacingMessage(for: error))")
                    }
                }

                updateJob(job.id) { queuedJob in
                    queuedJob.status = .completed
                    queuedJob.bytesTransferred = finalSize
                    queuedJob.totalBytes = max(finalSize, max(queuedJob.totalBytes, queuedJob.byteSize))
                    queuedJob.currentOffset = queuedJob.bytesTransferred
                    queuedJob.completedAt = Date()
                    queuedJob.updatedAt = Date()
                    queuedJob.errorMessage = nil
                }
                queueState.activeJobID = nil
                finishThroughputRecording(status: .completed)
                if !queueState.jobs.contains(where: { $0.status.canResume || $0.status == .running }) {
                    queueState.status = .idle
                    activeDownloadProgress = nil
                    endLiveActivity(forJobID: job.id, force: true)
                } else if shouldPauseQueueAfterCurrentBackgroundJob && currentThroughputScene == .background {
                    queueState.status = .paused
                    activeDownloadProgress = nil
                    shouldPauseQueueAfterCurrentBackgroundJob = false
                    endLiveActivity(forJobID: job.id, force: true, message: "已暂停队列，回到 App 继续")
                    shell.appendLog("已完成当前后台下载，队列已暂停。回到 App 后可继续剩余文件。")
                    syncPublishedState()
                    try await persistQueueState()
                    await refreshDownloads()
                    return
                } else {
                    updateLiveActivity(forJobID: job.id, force: true)
                }
                syncPublishedState()
                try await persistQueueState()
                await refreshDownloads()
            } catch is CancellationError {
                cleanupTemporaryDownloadFile()
                let shouldContinue = await handleCancellation(forJobID: job.id)
                if !shouldContinue {
                    return
                }
            } catch {
                cleanupTemporaryDownloadFile()
                let mappedStatus = interruptibleStatus(for: error)
                let message = shell.userFacingMessage(for: error)
                updateJob(job.id) { queuedJob in
                    queuedJob.status = mappedStatus
                    queuedJob.completedAt = mappedStatus.isTerminal ? Date() : nil
                    queuedJob.updatedAt = Date()
                    queuedJob.errorMessage = message
                }
                queueState.activeJobID = nil
                queueState.status = mappedStatus == .interrupted ? .interrupted : .paused
                finishThroughputRecording(status: mappedStatus)
                syncPublishedState()
                do {
                    try await persistQueueState()
                } catch {
                    shell.handle(error)
                }
                shell.appendLog("\(job.fileName) 下载失败：\(message)")
                return
            }
        }
    }

    private func handleCancellation(forJobID jobID: UUID) async -> Bool {
        switch queueStopReason {
        case .none:
            updateJob(jobID) { queuedJob in
                queuedJob.status = .interrupted
                queuedJob.updatedAt = Date()
                queuedJob.errorMessage = "下载任务被中断，可稍后继续。"
            }
            queueState.activeJobID = nil
            queueState.status = .interrupted
            syncPublishedState()
            try? await persistQueueState()
            return false

        case .pause:
            updateJob(jobID) { queuedJob in
                queuedJob.status = .paused
                queuedJob.updatedAt = Date()
                queuedJob.errorMessage = nil
            }
            queueState.activeJobID = nil
            queueState.status = .paused
            syncPublishedState()
            try? await persistQueueState()
            shell.appendLog("下载队列已暂停。")
            return false

        case .cancelActiveJob:
            updateJob(jobID) { queuedJob in
                queuedJob.status = .cancelled
                queuedJob.completedAt = Date()
                queuedJob.updatedAt = Date()
                queuedJob.errorMessage = "用户取消了下载。"
            }
            queueState.activeJobID = nil
            normalizeQueueStatusAfterManualUpdate(preferredStatus: .paused)
            syncPublishedState()
            try? await persistQueueState()
            shell.appendLog("已取消当前下载，队列已暂停。")
            return false

        case .interrupted(let message):
            updateJob(jobID) { queuedJob in
                queuedJob.status = .interrupted
                queuedJob.updatedAt = Date()
                queuedJob.errorMessage = message
            }
            queueState.activeJobID = nil
            queueState.status = .interrupted
            syncPublishedState()
            try? await persistQueueState()
            return false
        }
    }

    private func interruptActiveDownload(reason: String) {
        guard let activeJobID = queueState.activeJobID else { return }
        queueStopReason = .interrupted(reason)
        updateJob(activeJobID) { job in
            job.status = .interrupted
            job.updatedAt = Date()
            job.errorMessage = reason
        }
        updateLiveActivity(forJobID: activeJobID, force: true)
        finishThroughputRecording(status: .interrupted)
        queueRunnerTask?.cancel()
    }

    private func applyProgress(_ transportProgress: DownloadTransferProgress, toJobID jobID: UUID) {
        guard let index = queueState.jobs.firstIndex(where: { $0.id == jobID }) else { return }
        let asset = queueState.jobs[index].asset
        let itemNumber = index + 1
        let totalItemCount = queueState.jobs.count
        let knownTotal = max(asset.byteSize, 0)
        let resolvedTotal = max(max(transportProgress.totalBytes, transportProgress.bytesTransferred), knownTotal)
        let resolvedTransferred = min(max(transportProgress.bytesTransferred, 0), resolvedTotal)
        let resolvedOffset = min(max(transportProgress.currentOffset, resolvedTransferred), resolvedTotal)

        queueState.jobs[index].bytesTransferred = resolvedTransferred
        queueState.jobs[index].totalBytes = resolvedTotal
        queueState.jobs[index].currentOffset = resolvedOffset
        queueState.jobs[index].resumedCount = max(transportProgress.resumedCount, 0)
        queueState.jobs[index].updatedAt = Date()

        updateActiveDownloadProgress(
            for: asset,
            itemNumber: itemNumber,
            totalItemCount: totalItemCount,
            transportProgress: transportProgress
        )
        throughputDiagnosticsRecorder.recordProgress(transportProgress, scene: currentThroughputScene)
        syncPublishedState(keepExistingProgress: true)
        persistProgressIfNeeded()
        updateLiveActivity(forJobID: jobID)
    }

    private func finalizeProgress(forJobID jobID: UUID, finalSize: Int64) {
        guard let index = queueState.jobs.firstIndex(where: { $0.id == jobID }) else { return }
        let asset = queueState.jobs[index].asset
        let itemNumber = index + 1
        let totalItemCount = queueState.jobs.count
        let previousResumedCount = queueState.jobs[index].resumedCount
        let fallbackChunkSize = max(queueState.jobs[index].totalBytes, finalSize)

        queueState.jobs[index].bytesTransferred = finalSize
        queueState.jobs[index].totalBytes = max(finalSize, max(queueState.jobs[index].totalBytes, asset.byteSize))
        queueState.jobs[index].currentOffset = finalSize
        queueState.jobs[index].updatedAt = Date()

        let finalProgress = DownloadTransferProgress(
            bytesTransferred: finalSize,
            totalBytes: max(finalSize, asset.byteSize),
            resumedCount: previousResumedCount,
            currentOffset: finalSize,
            chunkSize: fallbackChunkSize
        )
        updateActiveDownloadProgress(
            for: asset,
            itemNumber: itemNumber,
            totalItemCount: totalItemCount,
            transportProgress: finalProgress,
            fallbackToAssetByteSize: false
        )
        throughputDiagnosticsRecorder.recordProgress(finalProgress, scene: currentThroughputScene)
        syncPublishedState(keepExistingProgress: true)
    }

    private func updateActiveDownloadProgress(
        for asset: PhotoAsset,
        itemNumber: Int,
        totalItemCount: Int,
        transportProgress: DownloadTransferProgress,
        fallbackToAssetByteSize: Bool = true
    ) {
        let knownTotal = fallbackToAssetByteSize ? max(asset.byteSize, 0) : 0
        let resolvedTotal = max(max(transportProgress.totalBytes, transportProgress.bytesTransferred), knownTotal)
        let resolvedTransferred = min(max(transportProgress.bytesTransferred, 0), resolvedTotal)
        let resolvedOffset = min(max(transportProgress.currentOffset, resolvedTransferred), resolvedTotal)

        activeDownloadProgress = ActiveDownloadProgress(
            fileName: asset.fileName,
            currentItemNumber: itemNumber,
            totalItemCount: totalItemCount,
            bytesTransferred: resolvedTransferred,
            totalBytes: resolvedTotal,
            resumedCount: max(transportProgress.resumedCount, 0),
            currentOffset: resolvedOffset,
            chunkSize: max(transportProgress.chunkSize, 0)
        )
    }

    private func progressSourceJob() -> DownloadJob? {
        if let activeJob = queueState.activeJob {
            return activeJob
        }

        return queueState.jobs.reversed().first { job in
            job.status == .paused || job.status == .interrupted || job.status == .running
        }
    }

    private func currentJob(id: UUID) -> DownloadJob? {
        queueState.jobs.first(where: { $0.id == id })
    }

    private func nextRunnableJob() -> DownloadJob? {
        queueState.jobs.first { $0.status.canResume }
    }

    private func updateJob(_ jobID: UUID, mutate: (inout DownloadJob) -> Void) {
        guard let index = queueState.jobs.firstIndex(where: { $0.id == jobID }) else { return }
        mutate(&queueState.jobs[index])
    }

    private func updateLiveActivity(forJobID jobID: UUID, force: Bool = false) {
        guard let job = currentJob(id: jobID),
              let itemNumber = queueState.jobs.firstIndex(where: { $0.id == jobID }).map({ $0 + 1 }) else {
            return
        }

        let now = Date()
        guard force || now.timeIntervalSince(lastLiveActivityUpdateDate) >= 1 else { return }
        lastLiveActivityUpdateDate = now
        throughputDiagnosticsRecorder.recordLiveActivityUpdate()
        let state = DownloadLiveActivityState(
            job: job,
            itemNumber: itemNumber,
            totalItemCount: max(queueState.jobs.count, 1)
        )

        if job.status == .running {
            liveActivityController.start(
                queueID: queueID,
                totalItemCount: max(queueState.jobs.count, 1),
                state: state
            )
        } else {
            liveActivityController.update(state: state)
        }
    }

    private func endLiveActivity(forJobID jobID: UUID, force: Bool = false, message: String = "下载队列已完成") {
        guard let job = currentJob(id: jobID),
              let itemNumber = queueState.jobs.firstIndex(where: { $0.id == jobID }).map({ $0 + 1 }) else {
            return
        }

        let state = DownloadLiveActivityState(
            job: job,
            itemNumber: itemNumber,
            totalItemCount: max(queueState.jobs.count, 1),
            message: message
        )
        liveActivityController.end(state: state, dismissalPolicy: force ? .after(Date().addingTimeInterval(15 * 60)) : .default)
    }

    private func syncPublishedState(keepExistingProgress: Bool = false) {
        queuedJobs = queueState.jobs
        queueStatus = queueState.status
        isDownloading = queueState.status == .running && queueState.activeJobID != nil
        shell.setGlobalActivityTitle(isDownloading ? CameraWorkflowState.downloading.title : nil)

        if !keepExistingProgress {
            if let job = progressSourceJob(),
               let itemNumber = queueState.jobs.firstIndex(where: { $0.id == job.id }).map({ $0 + 1 }) {
                activeDownloadProgress = ActiveDownloadProgress(
                    fileName: job.fileName,
                    currentItemNumber: itemNumber,
                    totalItemCount: max(queueState.jobs.count, 1),
                    bytesTransferred: job.bytesTransferred,
                    totalBytes: max(job.totalBytes, job.byteSize),
                    resumedCount: job.resumedCount,
                    currentOffset: job.currentOffset,
                    chunkSize: 0
                )
            } else if queueState.status == .idle {
                activeDownloadProgress = nil
            }
        }
    }

    private func normalizeQueueStatusAfterManualUpdate(preferredStatus: DownloadQueueStatus = .paused) {
        if queueState.jobs.contains(where: { $0.status == .running }) {
            queueState.status = .running
        } else if queueState.jobs.contains(where: { $0.status.canResume }) {
            queueState.status = preferredStatus
        } else {
            queueState.status = .idle
            queueState.activeJobID = nil
            activeDownloadProgress = nil
        }
    }

    private func applyQueueState(_ state: DownloadQueueState) {
        queueState = state
        syncPublishedState()
    }

    private func persistQueueState() async throws {
        throughputDiagnosticsRecorder.recordQueuePersistence()
        try await downloadStore.saveDownloadQueueState(queueState)
    }

    private func persistProgressIfNeeded() {
        let now = Date()
        guard now.timeIntervalSince(lastProgressPersistenceDate) >= 0.75 else { return }
        lastProgressPersistenceDate = now
        throughputDiagnosticsRecorder.recordQueuePersistence()
        let snapshot = queueState
        Task {
            try? await self.downloadStore.saveDownloadQueueState(snapshot)
        }
    }

    private func finishThroughputRecording(status: DownloadJobStatus) {
        guard let report = throughputDiagnosticsRecorder.finish(status: status) else { return }
        throughputReports.insert(report, at: 0)
        if throughputReports.count > 20 {
            throughputReports = Array(throughputReports.prefix(20))
        }
        shell.appendLog("传输诊断：\(report.displaySummary)")
    }

    private func cleanupTemporaryDownloadFile() {
        guard let currentTemporaryURL else { return }
        try? FileManager.default.removeItem(at: currentTemporaryURL)
        self.currentTemporaryURL = nil
    }

    private func interruptibleStatus(for error: Error) -> DownloadJobStatus {
        if error is CancellationError {
            return .cancelled
        }

        if let cameraError = error as? CameraAppError {
            switch cameraError {
            case .notConnected, .networkProbeFailed:
                return .interrupted
            case .fileSystemFailure:
                return .failed
            default:
                return .failed
            }
        }

        return .failed
    }

    private static func fileSize(at url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? 0
    }
}
