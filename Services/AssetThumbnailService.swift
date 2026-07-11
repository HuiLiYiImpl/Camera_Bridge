import AVFoundation
import CryptoKit
import Foundation
import ImageIO

actor AssetThumbnailService {
    private enum DerivativeKind {
        case thumbnail
        case preview

        var maxPixelSize: Int {
            switch self {
            case .thumbnail:
                return 420
            case .preview:
                return 4096
            }
        }

        var fallbackThumbnailPixelSize: Int {
            switch self {
            case .thumbnail:
                return 420
            case .preview:
                return 2048
            }
        }

        var compressionQuality: CGFloat {
            switch self {
            case .thumbnail:
                return 0.82
            case .preview:
                return 0.9
            }
        }

        var cropBlackBorders: Bool {
            switch self {
            case .thumbnail:
                return true
            case .preview:
                return false
            }
        }

        var cacheFolderName: String {
            switch self {
            case .thumbnail:
                return "thumbnails"
            case .preview:
                return "previews"
            }
        }
    }

    private let cacheDirectory: URL
    private var cachedThumbnailData: [String: Data] = [:]
    private var inFlightTasks: [String: Task<Data?, Never>] = [:]
    private var unavailableIdentifiers = Set<String>()
    private var cachedPreviewData: [String: Data] = [:]
    private var previewTasks: [String: Task<Data?, Never>] = [:]
    private var unavailablePreviewIdentifiers = Set<String>()

    init(cacheDirectory: URL? = nil) {
        if let cacheDirectory {
            self.cacheDirectory = cacheDirectory
        } else {
            let rootDirectory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            self.cacheDirectory = rootDirectory
                .appendingPathComponent("Nikon Connect", isDirectory: true)
                .appendingPathComponent("AssetPreviewCache", isDirectory: true)
        }
    }

    func clear() {
        cachedThumbnailData.removeAll()
        inFlightTasks.removeAll()
        unavailableIdentifiers.removeAll()
        cachedPreviewData.removeAll()
        previewTasks.removeAll()
        unavailablePreviewIdentifiers.removeAll()
    }

    func thumbnailData(
        for asset: PhotoAsset,
        using transport: any CameraTransport,
        session: CameraSession
    ) async -> Data? {
        await derivativeData(
            for: asset,
            kind: .thumbnail,
            using: transport,
            session: session
        )
    }

    func previewData(
        for asset: PhotoAsset,
        using transport: any CameraTransport,
        session: CameraSession
    ) async -> Data? {
        await derivativeData(
            for: asset,
            kind: .preview,
            using: transport,
            session: session
        )
    }

    private func derivativeData(
        for asset: PhotoAsset,
        kind: DerivativeKind,
        using transport: any CameraTransport,
        session: CameraSession
    ) async -> Data? {
        let cacheKey = Self.cacheKey(for: asset)

        if let cached = cachedDataInMemory(for: cacheKey, kind: kind) {
            return cached
        }

        if let diskCached = loadCachedData(for: asset, kind: kind) {
            storeDataInMemory(diskCached, for: cacheKey, kind: kind)
            return diskCached
        }

        if isMarkedUnavailable(cacheKey, for: kind) {
            return nil
        }

        if let existingTask = task(for: cacheKey, kind: kind) {
            return await existingTask.value
        }

        let task = Task<Data?, Never> {
            await Self.buildDerivativeData(
                for: asset,
                kind: kind,
                using: transport,
                session: session
            )
        }

        storeTask(task, for: cacheKey, kind: kind)
        let result = await task.value
        clearTask(for: cacheKey, kind: kind)

        if let result {
            storeDataInMemory(result, for: cacheKey, kind: kind)
            persistCachedData(result, for: asset, kind: kind)
        } else {
            markUnavailable(cacheKey, for: kind)
        }

        return result
    }

    private func cachedDataInMemory(for cacheKey: String, kind: DerivativeKind) -> Data? {
        switch kind {
        case .thumbnail:
            return cachedThumbnailData[cacheKey]
        case .preview:
            return cachedPreviewData[cacheKey]
        }
    }

    private func storeDataInMemory(_ data: Data, for cacheKey: String, kind: DerivativeKind) {
        switch kind {
        case .thumbnail:
            cachedThumbnailData[cacheKey] = data
        case .preview:
            cachedPreviewData[cacheKey] = data
        }
    }

    private func task(for cacheKey: String, kind: DerivativeKind) -> Task<Data?, Never>? {
        switch kind {
        case .thumbnail:
            return inFlightTasks[cacheKey]
        case .preview:
            return previewTasks[cacheKey]
        }
    }

    private func storeTask(_ task: Task<Data?, Never>, for cacheKey: String, kind: DerivativeKind) {
        switch kind {
        case .thumbnail:
            inFlightTasks[cacheKey] = task
        case .preview:
            previewTasks[cacheKey] = task
        }
    }

    private func clearTask(for cacheKey: String, kind: DerivativeKind) {
        switch kind {
        case .thumbnail:
            inFlightTasks[cacheKey] = nil
        case .preview:
            previewTasks[cacheKey] = nil
        }
    }

    private func isMarkedUnavailable(_ cacheKey: String, for kind: DerivativeKind) -> Bool {
        switch kind {
        case .thumbnail:
            return unavailableIdentifiers.contains(cacheKey)
        case .preview:
            return unavailablePreviewIdentifiers.contains(cacheKey)
        }
    }

    private func markUnavailable(_ cacheKey: String, for kind: DerivativeKind) {
        switch kind {
        case .thumbnail:
            unavailableIdentifiers.insert(cacheKey)
        case .preview:
            unavailablePreviewIdentifiers.insert(cacheKey)
        }
    }

    private func loadCachedData(for asset: PhotoAsset, kind: DerivativeKind) -> Data? {
        let url = cachedFileURL(for: asset, kind: kind)
        guard let data = try? Data(contentsOf: url) else {
            return nil
        }

        guard Self.isImageData(data) else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }

        return data
    }

    private func persistCachedData(_ data: Data, for asset: PhotoAsset, kind: DerivativeKind) {
        let url = cachedFileURL(for: asset, kind: kind)
        let directory = url.deletingLastPathComponent()

        do {
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: nil
            )
            try data.write(to: url, options: .atomic)
        } catch {
            // Cache persistence is best-effort and should not block preview rendering.
        }
    }

    private func cachedFileURL(for asset: PhotoAsset, kind: DerivativeKind) -> URL {
        cacheDirectory
            .appendingPathComponent(kind.cacheFolderName, isDirectory: true)
            .appendingPathComponent(Self.cacheKey(for: asset))
            .appendingPathExtension("jpg")
    }

    private static func cacheKey(for asset: PhotoAsset) -> String {
        let rawKey = [
            asset.remoteIdentifier,
            asset.fileName,
            String(asset.byteSize),
            asset.kind.rawValue,
            String(Int64(asset.captureDate.timeIntervalSince1970)),
            String(asset.thumbnailInfo?.byteSize ?? 0),
            String(asset.thumbnailInfo?.pixelWidth ?? 0),
            String(asset.thumbnailInfo?.pixelHeight ?? 0)
        ].joined(separator: "|")
        let digest = SHA256.hash(data: Data(rawKey.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func buildDerivativeData(
        for asset: PhotoAsset,
        kind: DerivativeKind,
        using transport: any CameraTransport,
        session: CameraSession
    ) async -> Data? {
        if kind == .thumbnail,
           let cameraThumbnail = await cameraThumbnailData(
               for: asset,
               kind: kind,
               using: transport,
               session: session
           ) {
            return cameraThumbnail
        }

        switch asset.kind {
        case .raw:
            if let rendered = await fileBackedDerivativeData(
                for: asset,
                kind: kind,
                using: transport,
                session: session
            ) {
                return rendered
            }

        case .movie:
            if let rendered = await fileBackedDerivativeData(
                for: asset,
                kind: kind,
                using: transport,
                session: session
            ) {
                return rendered
            }

        case .jpeg, .png:
            break
        }

        if let originalData = try? await transport.downloadAsset(asset, from: session),
           let preview = makeDerivativeData(
               from: originalData,
               fileName: asset.fileName,
               kind: asset.kind,
               derivative: kind
           ) {
            return preview
        }

        return await cameraThumbnailData(
            for: asset,
            kind: kind,
            using: transport,
            session: session
        )
    }

    private static func cameraThumbnailData(
        for asset: PhotoAsset,
        kind: DerivativeKind,
        using transport: any CameraTransport,
        session: CameraSession
    ) async -> Data? {
        guard let previewData = try? await transport.downloadThumbnail(asset, from: session) else {
            return nil
        }

        return normalizePreviewData(
            previewData,
            maxPixelSize: kind.fallbackThumbnailPixelSize
        )
    }

    private static func fileBackedDerivativeData(
        for asset: PhotoAsset,
        kind: DerivativeKind,
        using transport: any CameraTransport,
        session: CameraSession
    ) async -> Data? {
        guard let temporaryURL = try? await transport.downloadAssetToTemporaryFile(asset, from: session) else {
            return nil
        }

        defer {
            try? FileManager.default.removeItem(at: temporaryURL)
        }

        return makeDerivativeData(
            fromFileAt: temporaryURL,
            fileName: asset.fileName,
            kind: asset.kind,
            derivative: kind
        )
    }

    private static func normalizePreviewData(_ data: Data, maxPixelSize: Int) -> Data? {
        if let normalized = generateImageThumbnailData(
            from: data,
            maxPixelSize: maxPixelSize,
            cropBlackBorders: true,
            compressionQuality: 0.82
        ) {
            return normalized
        }

        return isImageData(data) ? data : nil
    }

    private static func makeDerivativeData(
        from originalData: Data,
        fileName: String,
        kind: PhotoAssetKind,
        derivative: DerivativeKind
    ) -> Data? {
        switch kind {
        case .movie:
            return generateMovieThumbnailData(
                from: originalData,
                fileName: fileName,
                maxPixelSize: derivative.maxPixelSize,
                cropBlackBorders: derivative.cropBlackBorders,
                compressionQuality: derivative.compressionQuality
            )

        case .jpeg, .png, .raw:
            return generateImageThumbnailData(
                from: originalData,
                maxPixelSize: derivative.maxPixelSize,
                cropBlackBorders: derivative.cropBlackBorders,
                compressionQuality: derivative.compressionQuality
            )
        }
    }

    private static func makeDerivativeData(
        fromFileAt fileURL: URL,
        fileName: String,
        kind: PhotoAssetKind,
        derivative: DerivativeKind
    ) -> Data? {
        switch kind {
        case .movie:
            return generateMovieThumbnailData(
                fromFileAt: fileURL,
                fileName: fileName,
                maxPixelSize: derivative.maxPixelSize,
                cropBlackBorders: derivative.cropBlackBorders,
                compressionQuality: derivative.compressionQuality
            )

        case .jpeg, .png, .raw:
            return generateImageThumbnailData(
                fromFileAt: fileURL,
                maxPixelSize: derivative.maxPixelSize,
                cropBlackBorders: derivative.cropBlackBorders,
                compressionQuality: derivative.compressionQuality
            )
        }
    }

    private static func generateImageThumbnailData(
        from originalData: Data,
        maxPixelSize: Int,
        cropBlackBorders: Bool,
        compressionQuality: CGFloat
    ) -> Data? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]

        guard
            let source = CGImageSourceCreateWithData(originalData as CFData, nil),
            let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else {
            return nil
        }

        return jpegData(
            from: cgImage,
            compressionQuality: compressionQuality,
            cropBlackBorders: cropBlackBorders
        )
    }

    private static func generateImageThumbnailData(
        fromFileAt fileURL: URL,
        maxPixelSize: Int,
        cropBlackBorders: Bool,
        compressionQuality: CGFloat
    ) -> Data? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]

        guard
            let source = CGImageSourceCreateWithURL(fileURL as CFURL, nil),
            let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else {
            return nil
        }

        return jpegData(
            from: cgImage,
            compressionQuality: compressionQuality,
            cropBlackBorders: cropBlackBorders
        )
    }

    private static func generateMovieThumbnailData(
        from originalData: Data,
        fileName: String,
        maxPixelSize: Int,
        cropBlackBorders: Bool,
        compressionQuality: CGFloat
    ) -> Data? {
        let fileExtension = URL(fileURLWithPath: fileName).pathExtension
        let temporaryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(fileExtension.isEmpty ? "mov" : fileExtension)

        do {
            try originalData.write(to: temporaryURL, options: .atomic)
        } catch {
            return nil
        }

        defer {
            try? FileManager.default.removeItem(at: temporaryURL)
        }

        let asset = AVURLAsset(url: temporaryURL)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxPixelSize, height: maxPixelSize)

        guard
            let cgImage = try? generator.copyCGImage(
                at: CMTime(seconds: 0.0, preferredTimescale: 600),
                actualTime: nil
            )
        else {
            return nil
        }

        return jpegData(
            from: cgImage,
            compressionQuality: compressionQuality,
            cropBlackBorders: cropBlackBorders
        )
    }

    private static func generateMovieThumbnailData(
        fromFileAt fileURL: URL,
        fileName: String,
        maxPixelSize: Int,
        cropBlackBorders: Bool,
        compressionQuality: CGFloat
    ) -> Data? {
        let resolvedURL: URL
        let pathExtension = fileURL.pathExtension

        if pathExtension.isEmpty {
            let fallbackExtension = URL(fileURLWithPath: fileName).pathExtension
            resolvedURL = fileURL.appendingPathExtension(fallbackExtension.isEmpty ? "mov" : fallbackExtension)
        } else {
            resolvedURL = fileURL
        }

        let asset = AVURLAsset(url: resolvedURL)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxPixelSize, height: maxPixelSize)

        guard
            let cgImage = try? generator.copyCGImage(
                at: CMTime(seconds: 0.0, preferredTimescale: 600),
                actualTime: nil
            )
        else {
            return nil
        }

        return jpegData(
            from: cgImage,
            compressionQuality: compressionQuality,
            cropBlackBorders: cropBlackBorders
        )
    }

    private static func jpegData(
        from cgImage: CGImage,
        compressionQuality: CGFloat,
        cropBlackBorders: Bool
    ) -> Data? {
        let renderedImage = cropBlackBorders ? (cropBlackBordersIfNeeded(from: cgImage) ?? cgImage) : cgImage
        let destinationData = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            destinationData,
            "public.jpeg" as CFString,
            1,
            nil
        ) else {
            return nil
        }

        let options = [
            kCGImageDestinationLossyCompressionQuality: compressionQuality
        ] as CFDictionary

        CGImageDestinationAddImage(destination, renderedImage, options)
        guard CGImageDestinationFinalize(destination) else {
            return nil
        }

        return destinationData as Data
    }

    private static func isImageData(_ data: Data) -> Bool {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return false
        }

        return CGImageSourceGetCount(source) > 0
    }

    private static func cropBlackBordersIfNeeded(from cgImage: CGImage) -> CGImage? {
        let width = cgImage.width
        let height = cgImage.height

        guard width >= 40, height >= 40 else {
            return nil
        }

        let bytesPerPixel = 4
        let bytesPerRow = width * bytesPerPixel
        var rgba = [UInt8](repeating: 0, count: bytesPerRow * height)

        guard
            let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
            let context = CGContext(
                data: &rgba,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
        else {
            return nil
        }

        context.interpolationQuality = .none
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        let rowStep = max(1, width / 48)
        let columnStep = max(1, height / 48)
        let darkThreshold: UInt8 = 20
        let requiredDarkRatio = 0.98

        func pixelIsDark(x: Int, y: Int) -> Bool {
            let index = (y * bytesPerRow) + (x * bytesPerPixel)
            let red = rgba[index]
            let green = rgba[index + 1]
            let blue = rgba[index + 2]
            return red <= darkThreshold && green <= darkThreshold && blue <= darkThreshold
        }

        func rowIsBlack(_ y: Int) -> Bool {
            var samples = 0
            var darkSamples = 0

            for x in stride(from: 0, to: width, by: rowStep) {
                samples += 1
                if pixelIsDark(x: x, y: y) {
                    darkSamples += 1
                }
            }

            return samples > 0 && Double(darkSamples) / Double(samples) >= requiredDarkRatio
        }

        func columnIsBlack(_ x: Int) -> Bool {
            var samples = 0
            var darkSamples = 0

            for y in stride(from: 0, to: height, by: columnStep) {
                samples += 1
                if pixelIsDark(x: x, y: y) {
                    darkSamples += 1
                }
            }

            return samples > 0 && Double(darkSamples) / Double(samples) >= requiredDarkRatio
        }

        let maxVerticalCrop = height / 3
        let maxHorizontalCrop = width / 6

        var top = 0
        while top < maxVerticalCrop, rowIsBlack(top) {
            top += 1
        }

        var bottom = 0
        while bottom < maxVerticalCrop, rowIsBlack(height - 1 - bottom) {
            bottom += 1
        }

        var left = 0
        while left < maxHorizontalCrop, columnIsBlack(left) {
            left += 1
        }

        var right = 0
        while right < maxHorizontalCrop, columnIsBlack(width - 1 - right) {
            right += 1
        }

        guard top > 4 || bottom > 4 || left > 4 || right > 4 else {
            return nil
        }

        let croppedWidth = width - left - right
        let croppedHeight = height - top - bottom

        guard croppedWidth > width / 2, croppedHeight > height / 2 else {
            return nil
        }

        return cgImage.cropping(to: CGRect(x: left, y: top, width: croppedWidth, height: croppedHeight))
    }
}
