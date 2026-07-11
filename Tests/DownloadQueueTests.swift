import Foundation
import XCTest
@testable import NikonConnectIOS

@MainActor
final class DownloadQueueTests: XCTestCase {
    private actor TransportStub: CameraTransport {
        enum StubError: Error, LocalizedError {
            case interrupted

            var errorDescription: String? {
                switch self {
                case .interrupted:
                    return "连接已中断"
                }
            }
        }

        enum Behavior {
            case succeed
            case failAfterFirstProgress
        }

        private let behavior: Behavior
        private let payload: Data
        private(set) var requestedAssets: [String] = []

        init(behavior: Behavior = .succeed, payload: Data = Data([1, 2, 3, 4])) {
            self.behavior = behavior
            self.payload = payload
        }

        func connect(using config: CameraConnectionConfig) async throws -> CameraSession {
            CameraSession(
                cameraName: "Stub Camera",
                connectedHost: config.normalizedHost,
                port: config.port,
                capabilities: [.connectionProbe, .listAssets, .downloadAssets]
            )
        }

        func fetchAssetsPage(
            for session: CameraSession,
            resetTraversal: Bool,
            limit: Int
        ) async throws -> PhotoAssetPage {
            PhotoAssetPage(assets: [], hasMore: false)
        }

        func downloadThumbnail(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data? {
            nil
        }

        func downloadAsset(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data {
            payload
        }

        func downloadAssetToTemporaryFile(_ asset: PhotoAsset, from session: CameraSession) async throws -> URL {
            try await downloadAssetToTemporaryFile(asset, from: session, onProgress: nil)
        }

        func downloadAssetToTemporaryFile(
            _ asset: PhotoAsset,
            from session: CameraSession,
            onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
        ) async throws -> URL {
            requestedAssets.append(asset.remoteIdentifier)
            if let onProgress {
                await onProgress(
                    DownloadTransferProgress(
                        bytesTransferred: Int64(payload.count / 2),
                        totalBytes: Int64(payload.count),
                        resumedCount: 0,
                        currentOffset: Int64(payload.count / 2),
                        chunkSize: Int64(payload.count / 2)
                    )
                )
            }

            if behavior == .failAfterFirstProgress {
                throw CameraAppError.networkProbeFailed("连接已中断")
            }

            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(URL(fileURLWithPath: asset.fileName).pathExtension)
            try payload.write(to: url, options: .atomic)
            return url
        }

        func consumeDiagnostics() async -> [String] {
            []
        }

        func disconnect() async {}

        func requestedAssetIdentifiers() -> [String] {
            requestedAssets
        }
    }

    private actor BlockingTransportStub: CameraTransport {
        private let payload: Data
        private var requestedAssets: [String] = []
        private var continuation: CheckedContinuation<Void, Never>?

        init(payload: Data = Data([1, 2, 3, 4])) {
            self.payload = payload
        }

        func connect(using config: CameraConnectionConfig) async throws -> CameraSession {
            CameraSession(
                cameraName: "Stub Camera",
                connectedHost: config.normalizedHost,
                port: config.port,
                capabilities: [.connectionProbe, .listAssets, .downloadAssets]
            )
        }

        func fetchAssetsPage(
            for session: CameraSession,
            resetTraversal: Bool,
            limit: Int
        ) async throws -> PhotoAssetPage {
            PhotoAssetPage(assets: [], hasMore: false)
        }

        func downloadThumbnail(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data? {
            nil
        }

        func downloadAsset(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data {
            payload
        }

        func downloadAssetToTemporaryFile(_ asset: PhotoAsset, from session: CameraSession) async throws -> URL {
            try await downloadAssetToTemporaryFile(asset, from: session, onProgress: nil)
        }

        func downloadAssetToTemporaryFile(
            _ asset: PhotoAsset,
            from session: CameraSession,
            onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
        ) async throws -> URL {
            requestedAssets.append(asset.remoteIdentifier)
            if let onProgress {
                await onProgress(
                    DownloadTransferProgress(
                        bytesTransferred: Int64(payload.count / 2),
                        totalBytes: Int64(payload.count),
                        resumedCount: 0,
                        currentOffset: Int64(payload.count / 2),
                        chunkSize: Int64(payload.count / 2)
                    )
                )
            }
            await waitUntilReleased()
            if let onProgress {
                await onProgress(
                    DownloadTransferProgress(
                        bytesTransferred: Int64(payload.count),
                        totalBytes: Int64(payload.count),
                        resumedCount: 0,
                        currentOffset: Int64(payload.count),
                        chunkSize: Int64(payload.count / 2)
                    )
                )
            }
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(URL(fileURLWithPath: asset.fileName).pathExtension)
            try payload.write(to: url, options: .atomic)
            return url
        }

        func releaseCurrentDownload() {
            continuation?.resume()
            continuation = nil
        }

        func isWaitingForRelease() -> Bool {
            continuation != nil
        }

        func consumeDiagnostics() async -> [String] { [] }
        func disconnect() async {}

        func requestedAssetIdentifiers() -> [String] {
            requestedAssets
        }

        private func waitUntilReleased() async {
            await withCheckedContinuation { continuation in
                self.continuation = continuation
            }
        }
    }

    private struct ExportServiceSpy: PhotoLibraryExporting {
        let shouldFail: Bool
        let exportHandler: @Sendable (URL) async -> Void

        init(shouldFail: Bool = false, exportHandler: @escaping @Sendable (URL) async -> Void = { _ in }) {
            self.shouldFail = shouldFail
            self.exportHandler = exportHandler
        }

        func exportFile(at url: URL) async throws {
            await exportHandler(url)
            if shouldFail {
                throw CameraAppError.photoLibraryExportFailed("测试导出失败")
            }
        }
    }

    func testEnqueueDownloadsUsesJPEGPriorityOrdering() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let transport = TransportStub()
        coordinator.setActiveSession(makeSession(), transport: transport)
        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(),
            sessionCoordinator: coordinator,
            shell: shell
        )

        let raw = makeAsset(id: "raw", fileName: "A.NEF", kind: .raw, byteSize: 10)
        let jpeg = makeAsset(id: "jpeg", fileName: "A.JPG", kind: .jpeg, byteSize: 5)
        let movie = makeAsset(id: "movie", fileName: "A.MOV", kind: .movie, byteSize: 20)

        let didQueue = await viewModel.enqueueDownloads(
            [raw, jpeg, movie],
            autoExportToPhotoLibrary: false,
            prioritizeJPEGDownloads: true
        )

        XCTAssertTrue(didQueue)
        XCTAssertEqual(viewModel.queuedJobs.map(\.remoteIdentifier), ["jpeg", "raw", "movie"])
    }

    func testLoadPersistedQueueMarksRunningJobsInterrupted() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let job = DownloadJob(
            remoteIdentifier: "running-job",
            fileName: "RUN.NEF",
            kind: .raw,
            byteSize: 1024,
            captureDate: Date(),
            autoExportToPhotoLibrary: false,
            status: .running
        )
        try await store.saveDownloadQueueState(
            DownloadQueueState(jobs: [job], activeJobID: job.id, status: .running)
        )

        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(),
            sessionCoordinator: coordinator,
            shell: shell
        )

        await viewModel.loadPersistedQueue()

        XCTAssertEqual(viewModel.queueStatus, .interrupted)
        XCTAssertEqual(viewModel.queuedJobs.first?.status, .interrupted)
    }

    func testResumeInterruptedDownloadsRunsQueueAndPersistsRecord() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let transport = TransportStub()
        coordinator.setActiveSession(makeSession(), transport: transport)
        let interruptedJob = DownloadJob(
            remoteIdentifier: "resume-job",
            fileName: "RESUME.JPG",
            kind: .jpeg,
            byteSize: 4,
            captureDate: Date(),
            autoExportToPhotoLibrary: false,
            status: .interrupted,
            bytesTransferred: 2,
            totalBytes: 4,
            currentOffset: 2,
            resumedCount: 0,
            errorMessage: "等待继续"
        )
        try await store.saveDownloadQueueState(
            DownloadQueueState(jobs: [interruptedJob], activeJobID: nil, status: .interrupted)
        )

        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(),
            sessionCoordinator: coordinator,
            shell: shell
        )
        await viewModel.loadPersistedQueue()
        await viewModel.resumeInterruptedDownloads()
        try await waitUntil(timeout: 2) {
            viewModel.queuedJobs.first?.status == .completed
        }

        let records = try await store.listRecords()
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(viewModel.queueStatus, .idle)
        XCTAssertEqual(viewModel.queuedJobs.first?.status, .completed)
    }

    func testDownloadFailureMarksJobInterrupted() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let transport = TransportStub(behavior: .failAfterFirstProgress)
        coordinator.setActiveSession(makeSession(), transport: transport)
        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(),
            sessionCoordinator: coordinator,
            shell: shell
        )

        let didQueue = await viewModel.enqueueDownloads(
            [makeAsset(id: "fail", fileName: "FAIL.MOV", kind: .movie, byteSize: 20)],
            autoExportToPhotoLibrary: false,
            prioritizeJPEGDownloads: false
        )
        XCTAssertTrue(didQueue)

        try await waitUntil(timeout: 2) {
            viewModel.queueStatus == .interrupted
        }

        XCTAssertEqual(viewModel.queuedJobs.first?.status, .interrupted)
        XCTAssertNotNil(viewModel.queuedJobs.first?.errorMessage)
    }

    func testCancelQueuedJobMarksItCancelled() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let transport = TransportStub()
        coordinator.setActiveSession(makeSession(), transport: transport)
        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(),
            sessionCoordinator: coordinator,
            shell: shell
        )

        let first = makeAsset(id: "first", fileName: "FIRST.JPG", kind: .jpeg, byteSize: 5)
        let second = makeAsset(id: "second", fileName: "SECOND.NEF", kind: .raw, byteSize: 8)
        _ = await viewModel.enqueueDownloads(
            [first, second],
            autoExportToPhotoLibrary: false,
            prioritizeJPEGDownloads: false
        )

        guard let queuedJob = viewModel.queuedJobs.last else {
            XCTFail("Expected queued job")
            return
        }
        viewModel.cancelJob(queuedJob)

        XCTAssertEqual(viewModel.queuedJobs.last?.status, .cancelled)
    }

    func testPhotoLibraryExportFailureKeepsDownloadCompleted() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let transport = TransportStub()
        coordinator.setActiveSession(makeSession(), transport: transport)
        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(shouldFail: true),
            sessionCoordinator: coordinator,
            shell: shell
        )

        _ = await viewModel.enqueueDownloads(
            [makeAsset(id: "export", fileName: "EXPORT.JPG", kind: .jpeg, byteSize: 4)],
            autoExportToPhotoLibrary: true,
            prioritizeJPEGDownloads: false
        )

        try await waitUntil(timeout: 2) {
            viewModel.queuedJobs.first?.status == .completed
        }

        let records = try await store.listRecords()
        XCTAssertEqual(records.count, 1)
        XCTAssertFalse(records[0].exportedToPhotoLibrary)
        XCTAssertEqual(viewModel.queuedJobs.first?.status, .completed)
    }

    func testBackgroundCompletionPausesQueueBeforeStartingNextJob() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let transport = BlockingTransportStub()
        coordinator.setActiveSession(makeSession(), transport: transport)
        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(),
            sessionCoordinator: coordinator,
            shell: shell
        )

        let first = makeAsset(id: "first", fileName: "FIRST.JPG", kind: .jpeg, byteSize: 4)
        let second = makeAsset(id: "second", fileName: "SECOND.JPG", kind: .jpeg, byteSize: 4)
        _ = await viewModel.enqueueDownloads(
            [first, second],
            autoExportToPhotoLibrary: false,
            prioritizeJPEGDownloads: false
        )

        try await waitUntilAsync(timeout: 2) {
            await transport.isWaitingForRelease()
        }
        viewModel.handleScenePhaseChange(.background)
        await transport.releaseCurrentDownload()

        try await waitUntil(timeout: 2) {
            viewModel.queueStatus == .paused && viewModel.queuedJobs.first?.status == .completed
        }

        let requestedAssetIdentifiers = await transport.requestedAssetIdentifiers()
        XCTAssertEqual(viewModel.queuedJobs.map(\.status), [.completed, .queued])
        XCTAssertEqual(requestedAssetIdentifiers, ["first"])
        XCTAssertTrue(shell.activityLog.contains { $0.message.contains("队列已暂停") })
    }

    private func makeRootDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        return url
    }

    private func makeSession() -> CameraSession {
        CameraSession(
            cameraName: "Nikon Zf",
            connectedHost: "192.168.0.10",
            port: 15740,
            capabilities: [.connectionProbe, .listAssets, .downloadAssets]
        )
    }

    private func makeAsset(id: String, fileName: String, kind: PhotoAssetKind, byteSize: Int64) -> PhotoAsset {
        PhotoAsset(
            remoteIdentifier: id,
            fileName: fileName,
            kind: kind,
            byteSize: byteSize,
            captureDate: Date(timeIntervalSince1970: 1_720_000_000)
        )
    }

    private func waitUntil(timeout: TimeInterval, condition: @escaping @MainActor () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() {
                return
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("Condition not met within \(timeout) seconds")
    }

    private func waitUntilAsync(timeout: TimeInterval, condition: @escaping @Sendable () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() {
                return
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("Condition not met within \(timeout) seconds")
    }
}
