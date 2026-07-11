import UIKit
import XCTest
@testable import NikonConnectIOS

final class AssetThumbnailServiceTests: XCTestCase {
    private actor RawAssetTransportStub: CameraTransport {
        struct Counts: Equatable, Sendable {
            let thumbnailRequests: Int
            let assetRequests: Int
            let temporaryFileRequests: Int
        }

        private let rawFileData: Data
        private let thumbnailResponse: Data?
        private let assetResponse: Data
        private var thumbnailRequests = 0
        private var assetRequests = 0
        private var temporaryFileRequests = 0

        init(rawFileData: Data, thumbnailResponse: Data? = nil, assetResponse: Data = Data("invalid".utf8)) {
            self.rawFileData = rawFileData
            self.thumbnailResponse = thumbnailResponse
            self.assetResponse = assetResponse
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
            thumbnailRequests += 1
            return thumbnailResponse
        }

        func downloadAsset(_ asset: PhotoAsset, from session: CameraSession) async throws -> Data {
            assetRequests += 1
            return assetResponse
        }

        func downloadAssetToTemporaryFile(_ asset: PhotoAsset, from session: CameraSession) async throws -> URL {
            temporaryFileRequests += 1
            let temporaryURL = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension("nef")
            try rawFileData.write(to: temporaryURL, options: .atomic)
            return temporaryURL
        }

        func consumeDiagnostics() async -> [String] {
            []
        }

        func disconnect() async {}

        func counts() -> Counts {
            Counts(
                thumbnailRequests: thumbnailRequests,
                assetRequests: assetRequests,
                temporaryFileRequests: temporaryFileRequests
            )
        }
    }

    func testRawThumbnailFallsBackToTemporaryFileAndCachesInMemory() async throws {
        let cacheDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: cacheDirectory) }

        let service = AssetThumbnailService(cacheDirectory: cacheDirectory)
        let transport = RawAssetTransportStub(rawFileData: try sampleImageData())
        let asset = makeRawAsset()

        let firstThumbnail = await service.thumbnailData(
            for: asset,
            using: transport,
            session: makeSession()
        )

        XCTAssertNotNil(firstThumbnail)
        XCTAssertNotNil(firstThumbnail.flatMap(UIImage.init(data:)))

        let countsAfterFirstLoad = await transport.counts()
        XCTAssertEqual(countsAfterFirstLoad.thumbnailRequests, 1)
        XCTAssertEqual(countsAfterFirstLoad.assetRequests, 0)
        XCTAssertEqual(countsAfterFirstLoad.temporaryFileRequests, 1)

        let secondThumbnail = await service.thumbnailData(
            for: asset,
            using: transport,
            session: makeSession()
        )

        XCTAssertEqual(firstThumbnail, secondThumbnail)

        let countsAfterSecondLoad = await transport.counts()
        XCTAssertEqual(countsAfterSecondLoad.thumbnailRequests, 1)
        XCTAssertEqual(countsAfterSecondLoad.assetRequests, 0)
        XCTAssertEqual(countsAfterSecondLoad.temporaryFileRequests, 1)
    }

    func testRawPreviewUsesDiskCacheAcrossServiceInstances() async throws {
        let cacheDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: cacheDirectory) }

        let asset = makeRawAsset()
        let firstTransport = RawAssetTransportStub(rawFileData: try sampleImageData())
        let firstService = AssetThumbnailService(cacheDirectory: cacheDirectory)

        let firstPreview = await firstService.previewData(
            for: asset,
            using: firstTransport,
            session: makeSession()
        )

        XCTAssertNotNil(firstPreview)
        XCTAssertNotNil(firstPreview.flatMap(UIImage.init(data:)))

        let firstCounts = await firstTransport.counts()
        XCTAssertEqual(firstCounts.assetRequests, 0)
        XCTAssertEqual(firstCounts.temporaryFileRequests, 1)

        let secondTransport = RawAssetTransportStub(rawFileData: Data("different".utf8))
        let secondService = AssetThumbnailService(cacheDirectory: cacheDirectory)

        let cachedPreview = await secondService.previewData(
            for: asset,
            using: secondTransport,
            session: makeSession()
        )

        XCTAssertEqual(firstPreview, cachedPreview)

        let secondCounts = await secondTransport.counts()
        XCTAssertEqual(secondCounts.thumbnailRequests, 0)
        XCTAssertEqual(secondCounts.assetRequests, 0)
        XCTAssertEqual(secondCounts.temporaryFileRequests, 0)
    }

    private func makeSession() -> CameraSession {
        CameraSession(
            cameraName: "Nikon Zf",
            connectedHost: "192.168.0.10",
            port: 15740,
            capabilities: [.connectionProbe, .listAssets, .downloadAssets]
        )
    }

    private func makeRawAsset() -> PhotoAsset {
        PhotoAsset(
            remoteIdentifier: "42",
            fileName: "DSC_0042.NEF",
            kind: .raw,
            byteSize: 4_096,
            captureDate: Date(timeIntervalSince1970: 1_720_000_000)
        )
    }

    private func sampleImageData() throws -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
        let image = renderer.image { context in
            UIColor(red: 0.99, green: 0.85, blue: 0.05, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }

        guard let data = image.jpegData(compressionQuality: 0.95) else {
            throw XCTSkip("无法构造测试图片数据。")
        }

        return data
    }
}
