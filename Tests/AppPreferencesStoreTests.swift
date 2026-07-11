import XCTest
@testable import NikonConnectIOS

final class AppPreferencesStoreTests: XCTestCase {
    func testSaveAndLoadConnectionConfig() {
        let defaults = UserDefaults(suiteName: #function)!
        defaults.removePersistentDomain(forName: #function)
        let store = AppPreferencesStore(userDefaults: defaults)

        let config = CameraConnectionConfig(
            host: "192.168.1.1",
            port: 15740,
            transportMode: .experimentalNikon,
            autoExportToPhotoLibrary: true,
            prioritizeJPEGDownloads: true
        )

        store.saveConnectionConfig(config)
        let loaded = store.loadConnectionConfig()

        XCTAssertEqual(loaded, config)
    }

    func testLoadConnectionConfigFallsBackWhenJPEGPriorityPreferenceMissing() throws {
        let defaults = UserDefaults(suiteName: #function)!
        defaults.removePersistentDomain(forName: #function)
        let store = AppPreferencesStore(userDefaults: defaults)

        let legacyConfigData = """
        {
          "host": "192.168.1.1",
          "port": 15740,
          "transportMode": "experimentalNikon",
          "autoExportToPhotoLibrary": true
        }
        """.data(using: .utf8)!
        defaults.set(legacyConfigData, forKey: "cameraConnectionConfig")

        let loaded = store.loadConnectionConfig()

        XCTAssertEqual(loaded.host, "192.168.1.1")
        XCTAssertEqual(loaded.port, 15740)
        XCTAssertEqual(loaded.transportMode, .experimentalNikon)
        XCTAssertTrue(loaded.autoExportToPhotoLibrary)
        XCTAssertFalse(loaded.prioritizeJPEGDownloads)
    }

    func testDownloadAssetPrioritizerMovesJPEGAheadOfRAWWhilePreservingGroupOrder() {
        let baseDate = Date(timeIntervalSince1970: 1_720_000_000)
        let rawA = PhotoAsset(
            remoteIdentifier: "raw-a",
            fileName: "DSC_1001.NEF",
            kind: .raw,
            byteSize: 10,
            captureDate: baseDate
        )
        let jpegA = PhotoAsset(
            remoteIdentifier: "jpeg-a",
            fileName: "DSC_1001.JPG",
            kind: .jpeg,
            byteSize: 5,
            captureDate: baseDate
        )
        let rawB = PhotoAsset(
            remoteIdentifier: "raw-b",
            fileName: "DSC_1002.NEF",
            kind: .raw,
            byteSize: 10,
            captureDate: baseDate.addingTimeInterval(-1)
        )
        let jpegB = PhotoAsset(
            remoteIdentifier: "jpeg-b",
            fileName: "DSC_1002.JPG",
            kind: .jpeg,
            byteSize: 5,
            captureDate: baseDate.addingTimeInterval(-1)
        )
        let movie = PhotoAsset(
            remoteIdentifier: "movie",
            fileName: "DSC_1003.MOV",
            kind: .movie,
            byteSize: 20,
            captureDate: baseDate.addingTimeInterval(-2)
        )

        let ordered = DownloadAssetPrioritizer.reordered(
            [rawA, jpegA, rawB, movie, jpegB],
            prioritizeJPEGDownloads: true
        )

        XCTAssertEqual(
            ordered.map(\.remoteIdentifier),
            ["jpeg-a", "jpeg-b", "raw-a", "raw-b", "movie"]
        )
    }

    func testDownloadAssetPrioritizerKeepsOriginalOrderWhenSelectionHasNoJPEGAssets() {
        let baseDate = Date(timeIntervalSince1970: 1_720_000_000)
        let rawA = PhotoAsset(
            remoteIdentifier: "raw-a",
            fileName: "DSC_1001.NEF",
            kind: .raw,
            byteSize: 10,
            captureDate: baseDate
        )
        let movie = PhotoAsset(
            remoteIdentifier: "movie",
            fileName: "DSC_1002.MOV",
            kind: .movie,
            byteSize: 20,
            captureDate: baseDate.addingTimeInterval(-1)
        )
        let rawB = PhotoAsset(
            remoteIdentifier: "raw-b",
            fileName: "DSC_1003.NEF",
            kind: .raw,
            byteSize: 10,
            captureDate: baseDate.addingTimeInterval(-2)
        )

        let ordered = DownloadAssetPrioritizer.reordered(
            [rawA, movie, rawB],
            prioritizeJPEGDownloads: true
        )

        XCTAssertEqual(
            ordered.map(\.remoteIdentifier),
            ["raw-a", "movie", "raw-b"]
        )
    }
}
