import XCTest
@testable import NikonConnectIOS

final class PTPIPSessionTransferStrategyTests: XCTestCase {
    func testHybridModeKeepsSmallFileOnFullObjectDownload() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)

        await session.configureDownloadStrategy(
            using: PTPIPDeviceInfo(
                model: nil,
                manufacturer: nil,
                operationsSupported: [PTPOperationCode.getPartialObject.rawValue]
            )
        )

        let mode = await session.downloadTransferMode(
            forExpectedByteSize: Int64(PTPIPSession.fullObjectDownloadThreshold)
        )

        XCTAssertEqual(mode, .fullObject)
    }

    func testHybridModeSwitchesLargeFileToPartialObjectDownload() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)

        await session.configureDownloadStrategy(
            using: PTPIPDeviceInfo(
                model: nil,
                manufacturer: nil,
                operationsSupported: [PTPOperationCode.getPartialObject.rawValue]
            )
        )

        let mode = await session.downloadTransferMode(
            forExpectedByteSize: Int64(PTPIPSession.fullObjectDownloadThreshold + 1)
        )

        XCTAssertEqual(mode, .partialObject(initialChunkSize: PTPIPSession.defaultChunkSize))
    }

    func testFullObjectOnlyStrategyIgnoresLargeFileSize() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)

        await session.configureDownloadStrategy(
            using: PTPIPDeviceInfo(
                model: nil,
                manufacturer: nil,
                operationsSupported: []
            )
        )

        let mode = await session.downloadTransferMode(forExpectedByteSize: 64 * 1_048_576)

        XCTAssertEqual(mode, .fullObject)
    }

    func testAdaptiveChunkControllerGrowsAfterStableChunksAndShrinksAfterFailure() {
        var controller = PTPIPSession.AdaptiveChunkController(
            initialChunkSize: PTPIPSession.defaultChunkSize,
            minimumChunkSize: PTPIPSession.minimumChunkSize,
            maximumChunkSize: PTPIPSession.maximumChunkSize
        )

        XCTAssertEqual(controller.currentChunkSize, PTPIPSession.defaultChunkSize)
        XCTAssertNil(
            controller.registerSuccess(
                receivedBytes: PTPIPSession.defaultChunkSize,
                requestedBytes: PTPIPSession.defaultChunkSize
            )
        )
        XCTAssertEqual(
            controller.registerSuccess(
                receivedBytes: PTPIPSession.defaultChunkSize,
                requestedBytes: PTPIPSession.defaultChunkSize
            ),
            PTPIPSession.maximumChunkSize
        )
        XCTAssertEqual(controller.currentChunkSize, PTPIPSession.maximumChunkSize)
        XCTAssertEqual(controller.registerRetryableFailure(), PTPIPSession.defaultChunkSize)
        XCTAssertEqual(controller.registerRetryableFailure(), 2_097_152)
        XCTAssertEqual(controller.registerRetryableFailure(), PTPIPSession.minimumChunkSize)
        XCTAssertNil(controller.registerRetryableFailure())
    }

    func testNormalizedExpectedByteSizeRejectsMissingOrNonPositiveValue() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)

        let missing = await session.normalizedExpectedByteSize(nil)
        let zero = await session.normalizedExpectedByteSize(0)
        let negative = await session.normalizedExpectedByteSize(-1)
        let positive = await session.normalizedExpectedByteSize(12_345)

        XCTAssertNil(missing)
        XCTAssertNil(zero)
        XCTAssertNil(negative)
        XCTAssertEqual(positive, 12_345)
    }

    func testHybridModeAllowsRetryableFullObjectFallback() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)

        await session.configureDownloadStrategy(
            using: PTPIPDeviceInfo(
                model: nil,
                manufacturer: nil,
                operationsSupported: [PTPOperationCode.getPartialObject.rawValue]
            )
        )

        let timeoutFallback = await session.shouldFallbackToPartialDownload(
            after: PTPIPError.timeout("test")
        )
        let malformedPayloadFallback = await session.shouldFallbackToPartialDownload(
            after: PTPIPError.malformedPayload("test")
        )

        XCTAssertTrue(timeoutFallback)
        XCTAssertFalse(malformedPayloadFallback)
    }

    func testThroughputTransferModeMatchesHybridDownloadStrategy() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)

        await session.configureDownloadStrategy(
            using: PTPIPDeviceInfo(
                model: nil,
                manufacturer: nil,
                operationsSupported: [PTPOperationCode.getPartialObject.rawValue]
            )
        )

        let jpegMode = await session.throughputTransferMode(forExpectedByteSize: 8_388_608)
        let rawMode = await session.throughputTransferMode(forExpectedByteSize: 64_000_000)

        XCTAssertEqual(jpegMode, .getObject)
        XCTAssertEqual(rawMode, .getPartialObject)
    }

    func testStreamingDownloadProgressUsesExpectedSizeWhenReportedSizeIsMissing() {
        let progress = PTPIPSession.makeStreamingDownloadProgress(
            bytesTransferred: 3_145_728,
            reportedTotalBytes: nil,
            expectedTotalBytes: 8_388_608,
            chunkSize: 1_048_576
        )

        XCTAssertEqual(progress.bytesTransferred, 3_145_728)
        XCTAssertEqual(progress.totalBytes, 8_388_608)
        XCTAssertEqual(progress.currentOffset, 3_145_728)
        XCTAssertEqual(progress.chunkSize, 1_048_576)
        XCTAssertEqual(progress.fractionCompleted, 0.375, accuracy: 0.0001)
    }

    func testStreamingDownloadProgressNeverReportsTotalBelowTransferredBytes() {
        let progress = PTPIPSession.makeStreamingDownloadProgress(
            bytesTransferred: 5_242_880,
            reportedTotalBytes: 4_194_304,
            expectedTotalBytes: 0,
            chunkSize: 2_097_152
        )

        XCTAssertEqual(progress.bytesTransferred, 5_242_880)
        XCTAssertEqual(progress.totalBytes, 5_242_880)
        XCTAssertEqual(progress.fractionCompleted, 1)
    }
}
