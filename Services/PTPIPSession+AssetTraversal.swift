import Foundation

extension PTPIPSession {
    private static let allObjectFormatCode: UInt32 = 0
    private static let allStorageIdentifier: UInt32 = 0xFFFF_FFFF
    private static let allAssociationHandle: UInt32 = 0xFFFF_FFFF
    private static let rootAssociationHandle: UInt32 = 0
    private static let rawObjectFormatCode: UInt16 = 0x3000
    private static let jfifObjectFormatCode: UInt16 = 0x3808
    private static let aviObjectFormatCode: UInt16 = 0x300A
    private static let mpegObjectFormatCode: UInt16 = 0x300B
    private static let movObjectFormatCode: UInt16 = 0x300D
    private static let heifObjectFormatCode: UInt16 = 0xB200
    private static let mpegVendorObjectFormatCode: UInt16 = 0xB97E

    func loadAssetsPage(limit: Int, resetTraversal: Bool) async throws -> PhotoAssetPage {
        try ensureOpen()

        let pageSize = max(1, limit)
        if resetTraversal || assetTraversalState == nil {
            assetTraversalState = try await prepareAssetTraversalState()
        }

        guard var traversal = assetTraversalState else {
            throw PTPIPError.sessionUnavailable
        }

        var pageAssets: [PhotoAsset] = []
        var unsupportedObjects: [(UInt32, UInt16, String)] = []

        while pageAssets.count < pageSize, traversal.nextIndex < traversal.queue.count {
            let handle = traversal.queue[traversal.nextIndex]
            traversal.nextIndex += 1

            let info = try await fetchObjectInfo(handle: handle)
            let hintedKind = traversal.handleKindHints[info.handle]
            let hintedCaptureDate = traversal.handleCaptureDateHints[info.handle]

            switch classifyObject(
                info,
                hintedKind: hintedKind,
                hintedCaptureDate: hintedCaptureDate
            ) {
            case .asset(let asset):
                if traversal.loadedAssetHandles.insert(info.handle).inserted {
                    pageAssets.append(asset)
                }

            case .directory:
                if traversal.exploredDirectoryHandles.insert(info.handle).inserted {
                    let childDiscovery = await loadChildHandles(
                        parentHandle: handle,
                        storageID: info.storageID
                    )
                    for (childHandle, kind) in childDiscovery.kindHints {
                        mergeKindHint(
                            kind,
                            for: childHandle,
                            into: &traversal.handleKindHints
                        )
                    }

                    let childPreview = childDiscovery.handles.prefix(5).map(String.init).joined(separator: ", ")
                    recordDiagnostic(
                        "目录 \(info.fileName) handle=\(handle) 子对象 \(childDiscovery.handles.count) 个" +
                            (childPreview.isEmpty ? "" : " [\(childPreview)]")
                    )

                    var newChildren: [UInt32] = []
                    newChildren.reserveCapacity(childDiscovery.handles.count)
                    for child in childDiscovery.handles where traversal.seenHandles.insert(child).inserted {
                        newChildren.append(child)
                    }

                    if !newChildren.isEmpty {
                        traversal.queue.insert(contentsOf: newChildren, at: traversal.nextIndex)
                    }
                }

            case .unsupported(let unsupported):
                if traversal.unsupportedHandles.insert(unsupported.0).inserted {
                    unsupportedObjects.append(unsupported)
                }
            }
        }

        if !unsupportedObjects.isEmpty {
            let preview = unsupportedObjects
                .prefix(3)
                .map { "handle=\($0.0) format=0x\(String($0.1, radix: 16)) name=\($0.2)" }
                .joined(separator: " | ")
            recordDiagnostic("本批未识别对象 \(unsupportedObjects.count) 个。\(preview)")
        }

        let rawCount = pageAssets.filter { $0.kind == .raw }.count
        let jpegCount = pageAssets.filter { $0.kind == .jpeg || $0.kind == .png }.count
        let movieCount = pageAssets.filter { $0.kind == .movie }.count
        recordDiagnostic("本批分类 JPEG=\(jpegCount) RAW=\(rawCount) 视频=\(movieCount)")

        let hasMore = traversal.nextIndex < traversal.queue.count
        let summary = hasMore
            ? "本批读取 \(pageAssets.count) 张，累计 \(traversal.loadedAssetHandles.count) 张，仍可继续读取。"
            : "本批读取 \(pageAssets.count) 张，累计 \(traversal.loadedAssetHandles.count) 张，当前列表已读完。"
        recordDiagnostic(summary)

        assetTraversalState = traversal

        return PhotoAssetPage(assets: pageAssets, hasMore: hasMore)
    }

    func prepareAssetTraversalState() async throws -> AssetTraversalState {
        let storageIDs = try await loadStorageIDs()
        let discovery = try await loadObjectHandles(storageIDs: storageIDs)
        recordDiagnostic("准备分批读取照片，首轮候选对象 \(discovery.handles.count) 个。")
        return AssetTraversalState(
            queue: discovery.handles,
            nextIndex: 0,
            seenHandles: Set(discovery.handles),
            loadedAssetHandles: [],
            exploredDirectoryHandles: [],
            unsupportedHandles: [],
            handleKindHints: discovery.kindHints,
            handleCaptureDateHints: discovery.captureDateHints
        )
    }

    func loadStorageIDs() async throws -> [UInt32] {
        let data = try await requestDataIn(
            operation: .getStorageIDs,
            transactionID: consumeTransactionID(),
            parameters: []
        )
        var reader = PTPDataReader(data: data)
        let storageIDs = try reader.readPTPArrayUInt32()
        let preview = storageIDs.map { String(format: "0x%08X", $0) }.joined(separator: ", ")
        recordDiagnostic("StorageIDs(\(storageIDs.count)): \(preview.isEmpty ? "<empty>" : preview)")
        return storageIDs
    }

    func loadObjectHandles(storageIDs: [UInt32]) async throws -> HandleDiscovery {
        var merged: [UInt32] = []
        var seen = Set<UInt32>()
        var kindHints: [UInt32: PhotoAssetKind] = [:]
        var captureDateHints: [UInt32: Date] = [:]

        if shouldAttemptNikonObjectMetaDataDiscovery() {
            do {
                let metaData = try await loadNikonObjectMetaData(
                    storageID: Self.allStorageIdentifier,
                    associationHandle: Self.rootAssociationHandle
                )
                recordDiagnostic("Nikon 元数据策略 \(formatStrategy((Self.allStorageIdentifier, Self.allObjectFormatCode, Self.rootAssociationHandle))) -> \(metaData.count) 个对象")
                mergeDiscoveredObjectMetaData(
                    metaData,
                    merged: &merged,
                    seen: &seen,
                    kindHints: &kindHints,
                    captureDateHints: &captureDateHints
                )
            } catch {
                recordDiagnostic("Nikon 元数据全局策略失败: \(error.localizedDescription)")
            }

            if merged.isEmpty {
                for storageID in storageIDs {
                    do {
                        let metaData = try await loadNikonObjectMetaData(
                            storageID: storageID,
                            associationHandle: Self.rootAssociationHandle
                        )
                        recordDiagnostic("Nikon 元数据存储策略 \(formatStrategy((storageID, Self.allObjectFormatCode, Self.rootAssociationHandle))) -> \(metaData.count) 个对象")
                        mergeDiscoveredObjectMetaData(
                            metaData,
                            merged: &merged,
                            seen: &seen,
                            kindHints: &kindHints,
                            captureDateHints: &captureDateHints
                        )
                    } catch {
                        recordDiagnostic("Nikon 元数据存储策略 \(formatStrategy((storageID, Self.allObjectFormatCode, Self.rootAssociationHandle))) 失败: \(error.localizedDescription)")
                    }
                }
            }
        }

        for strategy in objectHandleStrategies(storageID: Self.allStorageIdentifier) {
            do {
                let handles = try await requestObjectHandles(strategy: strategy)
                recordDiagnostic("全局句柄策略 \(formatStrategy(strategy)) -> \(handles.count) 个对象")
                mergeDiscoveredHandles(
                    handles,
                    strategy: strategy,
                    merged: &merged,
                    seen: &seen,
                    kindHints: &kindHints
                )
            } catch {
                recordDiagnostic("全局句柄策略 \(formatStrategy(strategy)) 失败: \(error.localizedDescription)")
            }
        }

        for storageID in storageIDs {
            for strategy in objectHandleStrategies(storageID: storageID) {
                do {
                    let handles = try await requestObjectHandles(strategy: strategy)
                    recordDiagnostic("存储句柄策略 \(formatStrategy(strategy)) -> \(handles.count) 个对象")
                    mergeDiscoveredHandles(
                        handles,
                        strategy: strategy,
                        merged: &merged,
                        seen: &seen,
                        kindHints: &kindHints
                    )
                } catch {
                    recordDiagnostic("存储句柄策略 \(formatStrategy(strategy)) 失败: \(error.localizedDescription)")
                }
            }
        }

        return HandleDiscovery(
            handles: merged,
            kindHints: kindHints,
            captureDateHints: captureDateHints
        )
    }

    func shouldAttemptNikonObjectMetaDataDiscovery() -> Bool {
        guard let deviceInfo else {
            return false
        }

        if deviceInfo.supportsOperation(.getObjectsMetaData) {
            return true
        }

        let manufacturer = deviceInfo.manufacturer ?? ""
        let model = deviceInfo.model ?? ""
        return manufacturer.localizedCaseInsensitiveContains("nikon") ||
            model.localizedCaseInsensitiveContains("nikon")
    }

    func loadNikonObjectMetaData(
        storageID: UInt32,
        associationHandle: UInt32
    ) async throws -> [NikonObjectMetaData] {
        let data = try await requestDataIn(
            operation: .getObjectsMetaData,
            transactionID: consumeTransactionID(),
            parameters: [storageID, Self.allObjectFormatCode, associationHandle]
        )
        return try parseNikonObjectMetaData(data)
    }

    func parseNikonObjectMetaData(_ data: Data) throws -> [NikonObjectMetaData] {
        var reader = PTPDataReader(data: data)
        _ = try reader.readUInt32()
        let count = Int(try reader.readUInt32())
        var metaData: [NikonObjectMetaData] = []
        metaData.reserveCapacity(count)

        for _ in 0 ..< count {
            let record = try reader.readData(count: 16)
            metaData.append(try parseNikonObjectMetaDataRecord(record))
        }

        return sortNikonObjectMetaData(metaData)
    }

    func parseNikonObjectMetaDataRecord(_ data: Data) throws -> NikonObjectMetaData {
        guard data.count == 16 else {
            throw PTPIPError.malformedPayload("Nikon 元数据记录长度不是 16 字节。")
        }

        var reader = PTPDataReader(data: data)
        let handle = try reader.readUInt32()
        _ = try reader.readUInt32()
        _ = try reader.readUInt8()
        let second = Int(try reader.readUInt8())
        let minute = Int(try reader.readUInt8())
        let hour = Int(try reader.readUInt8())
        let day = Int(try reader.readUInt8())
        let month = Int(try reader.readUInt8())
        let year = Int(try reader.readUInt16())

        return NikonObjectMetaData(
            handle: handle,
            kind: photoKindHint(forHandle: handle),
            captureDate: Self.makeCalendarDate(
                year: year,
                month: month,
                day: day,
                hour: hour,
                minute: minute,
                second: second
            )
        )
    }

    func mergeDiscoveredObjectMetaData(
        _ metaData: [NikonObjectMetaData],
        merged: inout [UInt32],
        seen: inout Set<UInt32>,
        kindHints: inout [UInt32: PhotoAssetKind],
        captureDateHints: inout [UInt32: Date]
    ) {
        for item in metaData {
            if seen.insert(item.handle).inserted {
                merged.append(item.handle)
            }

            if let kind = item.kind {
                mergeKindHint(
                    kind,
                    for: item.handle,
                    into: &kindHints
                )
            }

            if let captureDate = item.captureDate, captureDateHints[item.handle] == nil {
                captureDateHints[item.handle] = captureDate
            }
        }
    }

    func sortNikonObjectMetaData(_ metaData: [NikonObjectMetaData]) -> [NikonObjectMetaData] {
        metaData.sorted { lhs, rhs in
            switch (lhs.captureDate, rhs.captureDate) {
            case let (lhsDate?, rhsDate?) where lhsDate != rhsDate:
                return lhsDate > rhsDate
            case (_?, nil):
                return true
            case (nil, _?):
                return false
            default:
                return lhs.handle > rhs.handle
            }
        }
    }

    func objectHandleStrategies(
        storageID: UInt32,
        associationHandles: [UInt32] = [0xFFFF_FFFF, 0]
    ) -> [(UInt32, UInt32, UInt32)] {
        let objectFormats: [UInt32] = [Self.allObjectFormatCode, UInt32(Self.rawObjectFormatCode)]
        var strategies: [(UInt32, UInt32, UInt32)] = []

        for objectFormat in objectFormats {
            for associationHandle in associationHandles {
                strategies.append((storageID, objectFormat, associationHandle))
            }
        }

        return strategies
    }

    func requestObjectHandles(strategy: (UInt32, UInt32, UInt32)) async throws -> [UInt32] {
        let data = try await requestDataIn(
            operation: .getObjectHandles,
            transactionID: consumeTransactionID(),
            parameters: [strategy.0, strategy.1, strategy.2]
        )
        var reader = PTPDataReader(data: data)
        return try reader.readPTPArrayUInt32()
    }

    func mergeDiscoveredHandles(
        _ handles: [UInt32],
        strategy: (UInt32, UInt32, UInt32),
        merged: inout [UInt32],
        seen: inout Set<UInt32>,
        kindHints: inout [UInt32: PhotoAssetKind]
    ) {
        let hintedKind = photoKindHint(forQueriedObjectFormat: strategy.1)

        for handle in handles {
            if seen.insert(handle).inserted {
                merged.append(handle)
            }

            if let handleHint = photoKindHint(forHandle: handle) {
                mergeKindHint(
                    handleHint,
                    for: handle,
                    into: &kindHints
                )
            }

            if let hintedKind {
                mergeKindHint(
                    hintedKind,
                    for: handle,
                    into: &kindHints
                )
            }
        }
    }

    func mergeKindHint(
        _ kind: PhotoAssetKind,
        for handle: UInt32,
        into hints: inout [UInt32: PhotoAssetKind]
    ) {
        switch (hints[handle], kind) {
        case (.raw, _), (_, .raw):
            hints[handle] = .raw
        case (.some, _):
            break
        case (.none, _):
            hints[handle] = kind
        }
    }

    func photoKindHint(forQueriedObjectFormat objectFormat: UInt32) -> PhotoAssetKind? {
        guard let objectFormat = UInt16(exactly: objectFormat) else {
            return nil
        }

        switch objectFormat {
        case Self.rawObjectFormatCode:
            return .raw
        case 0x3801, Self.jfifObjectFormatCode, Self.heifObjectFormatCode:
            return .jpeg
        case Self.movObjectFormatCode, Self.aviObjectFormatCode, Self.mpegObjectFormatCode, Self.mpegVendorObjectFormatCode:
            return .movie
        default:
            return nil
        }
    }

    func photoKindHint(forHandle handle: UInt32) -> PhotoAssetKind? {
        let fileFormat = Int(handle >> 27)

        switch fileFormat {
        case 1:
            return .raw
        case 5:
            return .jpeg
        case 9, 11, 12:
            return .movie
        case 13:
            return .jpeg
        default:
            return nil
        }
    }

    func fetchObjectInfo(handle: UInt32) async throws -> PTPIPObjectInfo {
        let data = try await requestDataIn(
            operation: .getObjectInfo,
            transactionID: consumeTransactionID(),
            parameters: [handle]
        )

        return try parseObjectInfo(data: data, handle: handle)
    }

    func classifyObject(_ info: PTPIPObjectInfo, hintedKind: PhotoAssetKind?) -> ObjectClassification {
        classifyObject(info, hintedKind: hintedKind, hintedCaptureDate: nil)
    }

    func classifyObject(
        _ info: PTPIPObjectInfo,
        hintedKind: PhotoAssetKind?,
        hintedCaptureDate: Date?
    ) -> ObjectClassification {
        if info.objectFormat == 0x3001 {
            return .directory
        }

        guard let kind = photoKind(for: info, hintedKind: hintedKind) else {
            return .unsupported((info.handle, info.objectFormat, info.fileName))
        }

        return .asset(
            PhotoAsset(
                remoteIdentifier: String(info.handle),
                fileName: info.fileName,
                kind: kind,
                byteSize: Int64(info.compressedSize),
                captureDate: hintedCaptureDate ?? info.captureDate ?? info.modificationDate ?? .distantPast,
                thumbnailInfo: info.thumbnailInfo
            )
        )
    }

    func loadChildHandles(parentHandle: UInt32, storageID: UInt32) async -> HandleDiscovery {
        let strategies =
            objectHandleStrategies(
                storageID: storageID,
                associationHandles: [parentHandle]
            ) +
            objectHandleStrategies(
                storageID: Self.allStorageIdentifier,
                associationHandles: [parentHandle]
            )

        var merged: [UInt32] = []
        var seen = Set<UInt32>()
        var kindHints: [UInt32: PhotoAssetKind] = [:]

        for strategy in strategies {
            do {
                let handles = try await requestObjectHandles(strategy: strategy)
                recordDiagnostic("子句柄策略 \(formatStrategy(strategy)) -> \(handles.count) 个对象")
                mergeDiscoveredHandles(
                    handles,
                    strategy: strategy,
                    merged: &merged,
                    seen: &seen,
                    kindHints: &kindHints
                )
            } catch {
                recordDiagnostic("子句柄策略 \(formatStrategy(strategy)) 失败: \(error.localizedDescription)")
            }
        }

        return HandleDiscovery(
            handles: merged,
            kindHints: kindHints,
            captureDateHints: [:]
        )
    }

    func formatStrategy(_ strategy: (UInt32, UInt32, UInt32)) -> String {
        let storage = String(format: "0x%08X", strategy.0)
        let format = String(format: "0x%08X", strategy.1)
        let association = String(format: "0x%08X", strategy.2)
        return "[storage=\(storage) format=\(format) assoc=\(association)]"
    }

    func parseObjectInfo(data: Data, handle: UInt32) throws -> PTPIPObjectInfo {
        var reader = PTPDataReader(data: data)
        let storageID = try reader.readUInt32()
        let objectFormat = try reader.readUInt16()
        _ = try reader.readUInt16()
        let compressedSize = try reader.readUInt32()
        let thumbFormat = try reader.readUInt16()
        let thumbCompressedSize = try reader.readUInt32()
        let thumbPixWidth = try reader.readUInt32()
        let thumbPixHeight = try reader.readUInt32()
        _ = try reader.readUInt32()
        _ = try reader.readUInt32()
        _ = try reader.readUInt32()
        _ = try reader.readUInt32()
        _ = try reader.readUInt16()
        _ = try reader.readUInt32()
        _ = try reader.readUInt32()
        let fileName = try reader.readPTPString()
        let captureDate = Self.parsePTPDate(try reader.readPTPString())
        let modificationDate = Self.parsePTPDate(try reader.readPTPString())
        _ = try? reader.readPTPString()

        let thumbnailInfo: PhotoAssetThumbnailInfo? =
            thumbCompressedSize > 0
                ? PhotoAssetThumbnailInfo(
                    formatCode: thumbFormat,
                    byteSize: Int64(thumbCompressedSize),
                    pixelWidth: Int(thumbPixWidth),
                    pixelHeight: Int(thumbPixHeight)
                )
                : nil

        return PTPIPObjectInfo(
            handle: handle,
            storageID: storageID,
            objectFormat: objectFormat,
            compressedSize: compressedSize,
            thumbnailInfo: thumbnailInfo,
            fileName: fileName,
            captureDate: captureDate,
            modificationDate: modificationDate
        )
    }

    func photoKind(for info: PTPIPObjectInfo) -> PhotoAssetKind? {
        photoKind(for: info, hintedKind: nil)
    }

    func photoKind(for info: PTPIPObjectInfo, hintedKind: PhotoAssetKind?) -> PhotoAssetKind? {
        if let hintedKind {
            let fallbackKind = photoKindFromObjectInfo(info)
            if hintedKind == .raw && fallbackKind != .raw {
                let formatText = String(format: "0x%04X", info.objectFormat)
                let name = info.fileName.isEmpty ? "<empty>" : info.fileName
                recordDiagnostic(
                    "对象 \(info.handle) 通过格式查询标记为 RAW，ObjectInfo format=\(formatText) file=\(name)"
                )
            }
            return hintedKind
        }

        if let objectInfoKind = photoKindFromObjectInfo(info) {
            return objectInfoKind
        }

        return photoKindHint(forHandle: info.handle)
    }

    func photoKindFromObjectInfo(_ info: PTPIPObjectInfo) -> PhotoAssetKind? {
        let ext = URL(fileURLWithPath: info.fileName).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg":
            return .jpeg
        case "png":
            return .png
        case "nef", "nrw", "raw":
            return .raw
        case "mov", "mp4":
            return .movie
        default:
            break
        }

        switch info.objectFormat {
        case Self.rawObjectFormatCode:
            return .raw
        case 0x3801, Self.jfifObjectFormatCode:
            return .jpeg
        case 0x380B:
            return .png
        case Self.movObjectFormatCode, Self.aviObjectFormatCode, Self.mpegObjectFormatCode, Self.mpegVendorObjectFormatCode:
            return .movie
        case 0x380D, 0x3802, 0x3810, 0x3811:
            return .raw
        case Self.heifObjectFormatCode:
            return .jpeg
        default:
            return nil
        }
    }

    static func parsePTPDate(_ value: String) -> Date? {
        guard !value.isEmpty else { return nil }

        let trimmed = String(value.prefix(15))
        guard trimmed.count >= 15 else { return nil }

        let year = Int(trimmed.prefix(4))
        let month = Int(trimmed.dropFirst(4).prefix(2))
        let day = Int(trimmed.dropFirst(6).prefix(2))
        let hour = Int(trimmed.dropFirst(9).prefix(2))
        let minute = Int(trimmed.dropFirst(11).prefix(2))
        let second = Int(trimmed.dropFirst(13).prefix(2))

        guard
            let year,
            let month,
            let day,
            let hour,
            let minute,
            let second
        else {
            return nil
        }

        return makeCalendarDate(
            year: year,
            month: month,
            day: day,
            hour: hour,
            minute: minute,
            second: second
        )
    }

    static func makeCalendarDate(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ) -> Date? {
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.timeZone = TimeZone.current
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        components.second = second
        return components.date
    }
}
