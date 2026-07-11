import XCTest
@testable import NikonConnectIOS

final class DownloadStoreTests: XCTestCase {
    func testStoreAndListRecords() async throws {
        let rootURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = DownloadStore(rootDirectory: rootURL)

        let asset = PhotoAsset(
            remoteIdentifier: "asset-1",
            fileName: "sample.png",
            kind: .png,
            byteSize: 10,
            captureDate: Date()
        )

        _ = try await store.store(data: Data([0, 1, 2, 3]), from: asset)
        let records = try await store.listRecords()

        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.fileName, "sample.png")
    }

    func testStoreDownloadedFileMovesTemporaryFile() async throws {
        let fileManager = FileManager.default
        let rootURL = fileManager.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = DownloadStore(rootDirectory: rootURL)
        let temporaryURL = fileManager.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        let originalData = Data([9, 8, 7, 6, 5])
        try originalData.write(to: temporaryURL, options: .atomic)

        let asset = PhotoAsset(
            remoteIdentifier: "asset-2",
            fileName: "streamed.jpg",
            kind: .jpeg,
            byteSize: Int64(originalData.count),
            captureDate: Date()
        )

        let record = try await store.storeDownloadedFile(at: temporaryURL, from: asset)

        XCTAssertFalse(fileManager.fileExists(atPath: temporaryURL.path))
        XCTAssertTrue(fileManager.fileExists(atPath: record.savedURL.path))
        XCTAssertEqual(record.byteSize, Int64(originalData.count))
    }

    func testLoadDownloadJobsReturnsEmptyStateByDefault() async throws {
        let rootURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = DownloadStore(rootDirectory: rootURL)

        let state = try await store.loadDownloadJobs()

        XCTAssertEqual(state.status, .idle)
        XCTAssertTrue(state.jobs.isEmpty)
        XCTAssertNil(state.activeJobID)
    }

    func testUpsertAndPersistDownloadJobs() async throws {
        let rootURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = DownloadStore(rootDirectory: rootURL)
        let job = DownloadJob(
            remoteIdentifier: "asset-queue-1",
            fileName: "queued.nef",
            kind: .raw,
            byteSize: 2048,
            captureDate: Date(),
            autoExportToPhotoLibrary: false
        )

        _ = try await store.upsertDownloadJob(job, queueStatus: .running, activeJobID: job.id)
        let state = try await store.loadDownloadJobs()

        XCTAssertEqual(state.status, .running)
        XCTAssertEqual(state.activeJobID, job.id)
        XCTAssertEqual(state.jobs, [job])
    }

    func testMarkInterruptedRunningJobsUpdatesOnlyRunningJobs() async throws {
        let rootURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = DownloadStore(rootDirectory: rootURL)
        let runningJob = DownloadJob(
            remoteIdentifier: "asset-running",
            fileName: "running.mov",
            kind: .movie,
            byteSize: 4096,
            captureDate: Date(),
            autoExportToPhotoLibrary: false,
            status: .running
        )
        let queuedJob = DownloadJob(
            remoteIdentifier: "asset-queued",
            fileName: "queued.jpg",
            kind: .jpeg,
            byteSize: 1024,
            captureDate: Date(),
            autoExportToPhotoLibrary: false,
            status: .queued
        )

        try await store.saveDownloadQueueState(
            DownloadQueueState(
                jobs: [runningJob, queuedJob],
                activeJobID: runningJob.id,
                status: .running
            )
        )

        let state = try await store.markInterruptedRunningJobs(reason: "Background expired")

        XCTAssertEqual(state.status, .interrupted)
        XCTAssertNil(state.activeJobID)
        XCTAssertEqual(state.jobs.first?.status, .interrupted)
        XCTAssertEqual(state.jobs.first?.errorMessage, "Background expired")
        XCTAssertEqual(state.jobs.last?.status, .queued)
    }
}
