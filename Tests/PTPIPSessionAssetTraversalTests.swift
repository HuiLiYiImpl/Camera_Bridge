import XCTest
@testable import NikonConnectIOS

final class PTPIPSessionAssetTraversalTests: XCTestCase {
    private let rawEncodedHandle: UInt32 = (1 << 27) | (1 << 24) | 42
    private let jpegEncodedHandle: UInt32 = (5 << 27) | (1 << 24) | 42
    private let movEncodedHandle: UInt32 = (11 << 27) | (1 << 24) | 42

    func testPhotoKindTreatsUndefinedObjectFormatAsRaw() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)
        let info = PTPIPObjectInfo(
            handle: rawEncodedHandle,
            storageID: 1,
            objectFormat: 0x3000,
            compressedSize: 1_024,
            thumbnailInfo: PhotoAssetThumbnailInfo(
                formatCode: 0x3801,
                byteSize: 512,
                pixelWidth: 160,
                pixelHeight: 120
            ),
            fileName: "DSC_0042",
            captureDate: nil,
            modificationDate: nil
        )

        let kind = await session.photoKind(for: info)
        XCTAssertEqual(kind, .raw)
    }

    func testPhotoKindUsesRawHandleHintWhenObjectInfoHasNoExtension() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)
        let info = PTPIPObjectInfo(
            handle: rawEncodedHandle,
            storageID: 1,
            objectFormat: 0,
            compressedSize: 1_024,
            thumbnailInfo: nil,
            fileName: "DSC_0042",
            captureDate: nil,
            modificationDate: nil
        )

        let kind = await session.photoKind(for: info, hintedKind: .raw)
        XCTAssertEqual(kind, .raw)
    }

    func testPhotoKindFallsBackToHandleEncodedRawFormat() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)
        let info = PTPIPObjectInfo(
            handle: rawEncodedHandle,
            storageID: 1,
            objectFormat: 0,
            compressedSize: 1_024,
            thumbnailInfo: nil,
            fileName: "DSC_0042",
            captureDate: nil,
            modificationDate: nil
        )

        let kind = await session.photoKind(for: info)
        XCTAssertEqual(kind, .raw)
    }

    func testPhotoKindHintDecodesNikonHandleFileFormat() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)

        let rawKind = await session.photoKindHint(forHandle: rawEncodedHandle)
        let jpegKind = await session.photoKindHint(forHandle: jpegEncodedHandle)
        let movKind = await session.photoKindHint(forHandle: movEncodedHandle)

        XCTAssertEqual(rawKind, .raw)
        XCTAssertEqual(jpegKind, .jpeg)
        XCTAssertEqual(movKind, .movie)
    }

    func testObjectHandleStrategiesIncludeRawSpecificQuery() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)
        let strategies = await session.objectHandleStrategies(storageID: 0x0001_0001)

        XCTAssertTrue(
            strategies.contains(where: { $0.0 == 0x0001_0001 && $0.1 == 0x3000 && $0.2 == 0xFFFF_FFFF })
        )
        XCTAssertTrue(
            strategies.contains(where: { $0.0 == 0x0001_0001 && $0.1 == 0 && $0.2 == 0xFFFF_FFFF })
        )
    }

    func testChildHandleStrategiesIncludeRawSpecificQuery() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)
        let strategies = await session.objectHandleStrategies(
            storageID: 0x0001_0001,
            associationHandles: [0x0002_0003]
        )

        XCTAssertTrue(
            strategies.contains(where: { $0.0 == 0x0001_0001 && $0.1 == 0x3000 && $0.2 == 0x0002_0003 })
        )
        XCTAssertTrue(
            strategies.contains(where: { $0.0 == 0x0001_0001 && $0.1 == 0 && $0.2 == 0x0002_0003 })
        )
    }

    func testBackgroundTasksDoNotStartActiveProbeLoop() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)

        await session.startBackgroundTasksIfNeeded()
        let state = await session.backgroundTaskState()
        await session.stopBackgroundTasks()

        XCTAssertTrue(state.hasEventMonitorTask)
        XCTAssertFalse(state.hasProbeTask)
    }

    func testParseNikonObjectMetaDataDecodesRawHandleAndCaptureDate() async throws {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)
        let payload = makeNikonObjectMetaDataPayload(
            records: [
                (
                    handle: rawEncodedHandle,
                    attribute: 0,
                    second: 54,
                    minute: 10,
                    hour: 20,
                    day: 7,
                    month: 7,
                    year: 2026
                )
            ]
        )

        let metaData = try await session.parseNikonObjectMetaData(payload)

        XCTAssertEqual(metaData.count, 1)
        XCTAssertEqual(metaData.first?.handle, rawEncodedHandle)
        XCTAssertEqual(metaData.first?.kind, .raw)

        let captureDate = try XCTUnwrap(metaData.first?.captureDate)
        let components = Calendar(identifier: .gregorian).dateComponents(
            in: TimeZone.current,
            from: captureDate
        )
        XCTAssertEqual(components.year, 2026)
        XCTAssertEqual(components.month, 7)
        XCTAssertEqual(components.day, 7)
        XCTAssertEqual(components.hour, 20)
        XCTAssertEqual(components.minute, 10)
        XCTAssertEqual(components.second, 54)
    }

    func testParseNikonObjectMetaDataSortsNewestFirst() async throws {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)
        let newerHandle = jpegEncodedHandle + 1
        let payload = makeNikonObjectMetaDataPayload(
            records: [
                (
                    handle: rawEncodedHandle,
                    attribute: 0,
                    second: 0,
                    minute: 0,
                    hour: 8,
                    day: 1,
                    month: 7,
                    year: 2026
                ),
                (
                    handle: newerHandle,
                    attribute: 0,
                    second: 0,
                    minute: 0,
                    hour: 9,
                    day: 1,
                    month: 7,
                    year: 2026
                )
            ]
        )

        let metaData = try await session.parseNikonObjectMetaData(payload)

        XCTAssertEqual(metaData.map(\.handle), [newerHandle, rawEncodedHandle])
    }

    func testClassifyObjectUsesHintedCaptureDateWhenObjectInfoDateIsMissing() async {
        let session = PTPIPSession(host: "127.0.0.1", port: 15740)
        let hintedDate = Date(timeIntervalSince1970: 1_783_933_854)
        let info = PTPIPObjectInfo(
            handle: rawEncodedHandle,
            storageID: 1,
            objectFormat: 0,
            compressedSize: 1_024,
            thumbnailInfo: nil,
            fileName: "DSC_0042",
            captureDate: nil,
            modificationDate: nil
        )

        let classification = await session.classifyObject(
            info,
            hintedKind: .raw,
            hintedCaptureDate: hintedDate
        )

        guard case let .asset(asset) = classification else {
            return XCTFail("Expected asset classification")
        }

        XCTAssertEqual(asset.kind, .raw)
        XCTAssertEqual(asset.captureDate, hintedDate)
    }

    @MainActor
    func testPhotoAssetMergePreservesCameraOrderInsteadOfSortingByCaptureDate() {
        let olderRaw = PhotoAsset(
            remoteIdentifier: "raw-1",
            fileName: "DSC_0001.NEF",
            kind: .raw,
            byteSize: 10,
            captureDate: Date(timeIntervalSince1970: 100)
        )
        let newerJPEG = PhotoAsset(
            remoteIdentifier: "jpeg-1",
            fileName: "DSC_0002.JPG",
            kind: .jpeg,
            byteSize: 10,
            captureDate: Date(timeIntervalSince1970: 200)
        )

        let merged = PhotoAssetMerge.preservingCameraOrder(
            existing: [],
            incoming: [olderRaw, newerJPEG],
            resetTraversal: true
        )

        XCTAssertEqual(merged.map(\.remoteIdentifier), ["raw-1", "jpeg-1"])
    }

    private func makeNikonObjectMetaDataPayload(
        records: [(handle: UInt32, attribute: UInt32, second: UInt8, minute: UInt8, hour: UInt8, day: UInt8, month: UInt8, year: UInt16)]
    ) -> Data {
        var data = Data()
        data.appendUInt32LE(0)
        data.appendUInt32LE(UInt32(records.count))

        for record in records {
            data.appendUInt32LE(record.handle)
            data.appendUInt32LE(record.attribute)
            data.append(0)
            data.append(record.second)
            data.append(record.minute)
            data.append(record.hour)
            data.append(record.day)
            data.append(record.month)
            data.appendUInt16LE(record.year)
        }

        return data
    }
}
