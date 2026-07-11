import Foundation
import SwiftUI
import XCTest
@testable import NikonConnectIOS

@MainActor
final class DownloadThroughputDiagnosticsTests: XCTestCase {
    private actor TransportStub: CameraTransport {
        private let payload: Data
        private let transferMode: DownloadThroughputTransferMode

        init(payload: Data = Data(repeating: 7, count: 8_388_608), transferMode: DownloadThroughputTransferMode = .getObject) {
            self.payload = payload
            self.transferMode = transferMode
        }

        func connect(using config: CameraConnectionConfig) async throws -> CameraSession {
            CameraSession(
                cameraName: "Stub Camera",
                connectedHost: config.normalizedHost,
                port: config.port,
                capabilities: [.connectionProbe, .listAssets, .downloadAssets]
            )
        }

        func fetchAssetsPage(for session: CameraSession, resetTraversal: Bool, limit: Int) async throws -> PhotoAssetPage {
            PhotoAssetPage(assets: [], hasMore: false)
        }

        func downloadAsset(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data {
            payload
        }

        func downloadAssetToTemporaryFile(
            _ asset: PhotoAsset,
            from session: CameraSession,
            onProgress: (@Sendable (DownloadTransferProgress) async -> Void)?
        ) async throws -> URL {
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

        func downloadTransferMode(for asset: PhotoAsset) async -> DownloadThroughputTransferMode {
            transferMode
        }

        func consumeDiagnostics() async -> [String] { [] }
        func disconnect() async {}
    }

    private struct ExportServiceSpy: PhotoLibraryExporting {
        func exportFile(at url: URL) async throws {}
    }

    func testRecorderBuildsForegroundAndBackgroundThroughputReport() throws {
        let recorder = DownloadThroughputDiagnosticsRecorder()
        let job = makeJob(byteSize: 8_388_608)
        let startedAt = Date(timeIntervalSince1970: 100)

        recorder.start(
            job: job,
            itemNumber: 1,
            totalItemCount: 1,
            transferMode: .getObject,
            scene: .foreground,
            at: startedAt
        )
        recorder.recordProgress(
            DownloadTransferProgress(
                bytesTransferred: 4_194_304,
                totalBytes: 8_388_608,
                resumedCount: 0,
                currentOffset: 4_194_304,
                chunkSize: 4_194_304
            ),
            scene: .foreground,
            at: startedAt.addingTimeInterval(2)
        )
        recorder.recordSceneChange(.background, at: startedAt.addingTimeInterval(3))
        recorder.recordProgress(
            DownloadTransferProgress(
                bytesTransferred: 8_388_608,
                totalBytes: 8_388_608,
                resumedCount: 0,
                currentOffset: 8_388_608,
                chunkSize: 4_194_304
            ),
            scene: .background,
            at: startedAt.addingTimeInterval(6)
        )
        recorder.recordLiveActivityUpdate()
        recorder.recordQueuePersistence()

        let report = try XCTUnwrap(recorder.finish(status: .completed, at: startedAt.addingTimeInterval(8)))

        XCTAssertEqual(report.initialScene, .foreground)
        XCTAssertEqual(report.currentScene, .background)
        XCTAssertEqual(report.transferMode, .getObject)
        XCTAssertEqual(report.durationSeconds, 8)
        XCTAssertEqual(report.averageBytesPerSecond, 1_048_576, accuracy: 0.1)
        XCTAssertEqual(report.chunkSamples.count, 2)
        XCTAssertEqual(report.chunkSamples[0].bytesPerSecond, 2_097_152, accuracy: 0.1)
        XCTAssertEqual(report.chunkSamples[1].bytesPerSecond, 1_048_576, accuracy: 0.1)
        XCTAssertEqual(report.liveActivityUpdateCount, 1)
        XCTAssertEqual(report.queuePersistenceCount, 1)
    }

    func testReportDisplaySummaryCallsOutBackgroundAndSpeed() {
        let report = DownloadThroughputReport(
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
            fileName: "DSC_0001.JPG",
            fileKind: .jpeg,
            byteSize: 8_388_608,
            itemNumber: 1,
            totalItemCount: 1,
            transferMode: .getObject,
            initialScene: .foreground,
            currentScene: .background,
            startedAt: Date(timeIntervalSince1970: 100),
            finishedAt: Date(timeIntervalSince1970: 108),
            lastBytesTransferred: 8_388_608,
            chunkSamples: [],
            liveActivityUpdateCount: 2,
            queuePersistenceCount: 3,
            backgroundExpirationCount: 0,
            terminalStatus: .completed
        )

        XCTAssertEqual(report.averageSpeedText, "1.0 MB/s")
        XCTAssertTrue(report.displaySummary.contains("GetObject"))
        XCTAssertTrue(report.displaySummary.contains("后台"))
        XCTAssertTrue(report.displaySummary.contains("1.0 MB/s"))
    }

    func testDownloadManagerPublishesThroughputReportForCompletedDownload() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let transport = TransportStub(transferMode: .getObject)
        coordinator.setActiveSession(makeSession(), transport: transport)
        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(),
            sessionCoordinator: coordinator,
            shell: shell
        )

        _ = await viewModel.enqueueDownloads(
            [makeAsset(byteSize: 8_388_608)],
            autoExportToPhotoLibrary: false,
            prioritizeJPEGDownloads: false
        )

        try await waitUntil(timeout: 2) {
            viewModel.throughputReports.first?.terminalStatus == .completed
        }

        let report = try XCTUnwrap(viewModel.throughputReports.first)
        XCTAssertEqual(report.transferMode, .getObject)
        XCTAssertEqual(report.initialScene, .foreground)
        XCTAssertEqual(report.lastBytesTransferred, 8_388_608)
        XCTAssertFalse(report.chunkSamples.isEmpty)
        XCTAssertGreaterThan(report.queuePersistenceCount, 0)
    }

    func testDownloadManagerMarksReportAsBackgroundWhenDownloadStartsInBackground() async throws {
        let store = DownloadStore(rootDirectory: makeRootDirectory())
        let shell = AppShellViewModel()
        let coordinator = CameraSessionCoordinator()
        let transport = TransportStub(transferMode: .getPartialObject)
        coordinator.setActiveSession(makeSession(), transport: transport)
        let viewModel = DownloadManagerViewModel(
            downloadStore: store,
            photoLibraryExportService: ExportServiceSpy(),
            sessionCoordinator: coordinator,
            shell: shell
        )
        viewModel.handleScenePhaseChange(.background)

        _ = await viewModel.enqueueDownloads(
            [makeAsset(byteSize: 32_000_000)],
            autoExportToPhotoLibrary: false,
            prioritizeJPEGDownloads: false
        )

        try await waitUntil(timeout: 2) {
            viewModel.throughputReports.first?.terminalStatus == .completed
        }

        let report = try XCTUnwrap(viewModel.throughputReports.first)
        XCTAssertEqual(report.transferMode, .getPartialObject)
        XCTAssertEqual(report.initialScene, .background)
    }

    private func makeJob(byteSize: Int64) -> DownloadJob {
        DownloadJob(
            remoteIdentifier: "1",
            fileName: "DSC_0001.JPG",
            kind: .jpeg,
            byteSize: byteSize,
            captureDate: Date(timeIntervalSince1970: 1_720_000_000),
            autoExportToPhotoLibrary: false
        )
    }

    private func makeAsset(byteSize: Int64) -> PhotoAsset {
        PhotoAsset(
            remoteIdentifier: "1",
            fileName: "DSC_0001.JPG",
            kind: .jpeg,
            byteSize: byteSize,
            captureDate: Date(timeIntervalSince1970: 1_720_000_000)
        )
    }

    private func makeSession() -> CameraSession {
        CameraSession(
            cameraName: "Nikon Z5",
            connectedHost: "192.168.1.1",
            port: 15740,
            capabilities: [.connectionProbe, .listAssets, .downloadAssets]
        )
    }

    private func makeRootDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
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
}
