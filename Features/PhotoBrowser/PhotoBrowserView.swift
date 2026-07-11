import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

#if canImport(UIKit)
private typealias PlatformImage = UIImage
#elseif canImport(AppKit)
private typealias PlatformImage = NSImage
#endif

struct PhotoBrowserView: View {
    let activeSession: CameraSession?
    let assets: [PhotoAsset]
    let hasMorePhotos: Bool
    let selectedAssetIDs: Set<UUID>
    let autoExportToPhotoLibrary: Bool
    let canRefreshPhotos: Bool
    let canLoadMorePhotos: Bool
    let canDownloadSelection: Bool
    let activeDownloadProgress: ActiveDownloadProgress?
    let canPauseDownloads: Bool
    let canResumeDownloads: Bool
    let queuedJobCount: Int
    let onRefreshPhotos: () -> Void
    let onLoadMorePhotos: () -> Void
    let onToggleSelection: (PhotoAsset) -> Void
    let onSelectAllAssets: () -> Void
    let onClearSelection: () -> Void
    let onDownloadSelectedAssets: () -> Void
    let onPauseDownloads: () -> Void
    let onResumeDownloads: () -> Void
    let loadThumbnail: (PhotoAsset) async -> Data?
    let loadPreview: (PhotoAsset) async -> Data?

    @State private var activeFilter: PreviewFilter = .all
    @State private var gridColumnCount = 3
    @State private var previewSeed: PhotoPreviewSeed?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                overviewSection
                contentSection
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 20)
        }
        .background(AppTheme.canvas.ignoresSafeArea())
        .navigationTitle("照片")
        .navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(item: $previewSeed) { seed in
            PhotoAssetPreviewScreen(
                assets: filteredAssets,
                selectedAssetIDs: selectedAssetIDs,
                initialAssetID: seed.assetID,
                onToggleSelection: onToggleSelection,
                loadThumbnail: loadThumbnail,
                loadPreview: loadPreview
            )
        }
        .toolbar {
            ToolbarItem(placement: leadingToolbarPlacement) {
                Button(action: onRefreshPhotos) {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(!canRefreshPhotos)
            }

            ToolbarItem(placement: trailingToolbarPlacement) {
                Menu {
                    Button {
                        gridColumnCount = 3
                    } label: {
                        menuRow(title: "标准网格", isSelected: gridColumnCount == 3)
                    }

                    Button {
                        gridColumnCount = 4
                    } label: {
                        menuRow(title: "紧凑网格", isSelected: gridColumnCount == 4)
                    }

                    Divider()

                    Button("全选全部照片", action: onSelectAllAssets)
                    Button("清空选择", action: onClearSelection)
                } label: {
                    Image(systemName: "slider.horizontal.3")
                        .font(.body.weight(.semibold))
                }
                .disabled(assets.isEmpty)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if showBottomOverlay {
                VStack(spacing: 8) {
                    if let activeDownloadProgress {
                        downloadProgressCard(activeDownloadProgress)
                    }

                    if selectedAssetsCount > 0 || queuedJobCount > 0 {
                        bottomActionBar
                    }
                }
            }
        }
    }

    private var filteredAssets: [PhotoAsset] {
        assets.filter { activeFilter.matches($0) }
    }

    private var selectedAssetsCount: Int {
        selectedAssetIDs.count
    }

    private var showBottomOverlay: Bool {
        activeDownloadProgress != nil || selectedAssetsCount > 0 || queuedJobCount > 0
    }

    private var gridItems: [GridItem] {
        Array(
            repeating: GridItem(.flexible(), spacing: 6),
            count: gridColumnCount
        )
    }

    @ViewBuilder
    private var contentSection: some View {
        if assets.isEmpty {
            emptyStateView
        } else if filteredAssets.isEmpty {
            filteredEmptyStateView
        } else {
            assetGrid
        }
    }

    private var overviewSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 12) {
                Text(overviewTitle)
                    .font(.system(.title2, design: .rounded).weight(.bold))
                    .foregroundStyle(AppTheme.ink)

                Spacer(minLength: 12)

                if hasMorePhotos {
                    Text("分批读取")
                        .font(.system(.caption2, design: .rounded).weight(.bold))
                        .foregroundStyle(AppTheme.info)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .background(AppTheme.info.opacity(0.09), in: Capsule())
                }
            }

            HStack(spacing: 12) {
                MetricTile(
                    label: "已加载",
                    value: "\(assets.count)",
                    systemImage: "photo.on.rectangle",
                    accent: AppTheme.info
                )

                MetricTile(
                    label: "已选择",
                    value: "\(selectedAssetsCount)",
                    systemImage: "checkmark.circle",
                    accent: AppTheme.accentStrong
                )
            }

            if activeSession != nil || !assets.isEmpty {
                filterBar
            }
        }
    }

    private var filterBar: some View {
        HStack(spacing: 8) {
            ForEach(PreviewFilter.allCases) { filter in
                let isSelected = activeFilter == filter
                Button {
                    activeFilter = filter
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: filter.systemImageName)
                            .font(.system(.footnote, design: .rounded).weight(.semibold))
                        Text(filter.title)
                            .font(.system(.footnote, design: .rounded).weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                            .allowsTightening(true)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .foregroundStyle(isSelected ? AppTheme.surface : AppTheme.inkMuted)
                    .background(isSelected ? AppTheme.ink : AppTheme.surfaceMuted, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .animation(.spring(response: 0.25, dampingFraction: 0.75), value: activeFilter)
    }

    private var assetGrid: some View {
        VStack(alignment: .leading, spacing: 14) {
            CustomCard {
                LazyVGrid(columns: gridItems, spacing: 10) {
                    ForEach(filteredAssets) { asset in
                        PhotoAssetGridTile(
                            asset: asset,
                            isSelected: selectedAssetIDs.contains(asset.id),
                            loadThumbnail: loadThumbnail,
                            onToggleSelection: {
                                onToggleSelection(asset)
                            }
                        )
                        .onTapGesture {
                            previewSeed = PhotoPreviewSeed(assetID: asset.id)
                        }
                    }
                }
            }

            if hasMorePhotos {
                SecondaryActionButton(
                    title: "继续读取更多",
                    systemImage: "rectangle.stack.badge.plus",
                    isEnabled: canLoadMorePhotos,
                    action: onLoadMorePhotos
                )
            }
        }
    }

    private var emptyStateView: some View {
        CustomCard {
            VStack(alignment: .center, spacing: 16) {
                Image(systemName: "photo.on.rectangle.angled")
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(AppTheme.inkMuted.opacity(0.7))
                    .frame(width: 68, height: 68)
                    .background(AppTheme.surfaceMuted, in: Circle())

                Text("还没有照片内容")
                    .font(.system(.headline, design: .rounded).weight(.bold))
                    .foregroundStyle(AppTheme.ink)

                Text(canRefreshPhotos ? "连接已经完成，现在可以读取相机中的首批内容。" : "先在“相机”页完成连接，然后再回来浏览照片。")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(AppTheme.inkMuted)
                    .multilineTextAlignment(.center)

                if canRefreshPhotos {
                    PrimaryActionButton(
                        title: "读取首批照片",
                        systemImage: "arrow.clockwise",
                        action: onRefreshPhotos
                    )
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var filteredEmptyStateView: some View {
        CustomCard {
            VStack(alignment: .center, spacing: 16) {
                Image(systemName: "line.3.horizontal.decrease.circle")
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(AppTheme.inkMuted.opacity(0.7))
                    .frame(width: 68, height: 68)
                    .background(AppTheme.surfaceMuted, in: Circle())

                Text("当前筛选没有结果")
                    .font(.system(.headline, design: .rounded).weight(.bold))
                    .foregroundStyle(AppTheme.ink)

                Text("相机里可能没有这类文件。你可以切回全部，或者继续读取更多内容。")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(AppTheme.inkMuted)
                    .multilineTextAlignment(.center)

                VStack(spacing: 10) {
                    SecondaryActionButton(
                        title: "显示全部",
                        systemImage: "square.grid.2x2",
                        action: {
                            activeFilter = .all
                        }
                    )

                    if hasMorePhotos {
                        PrimaryActionButton(
                            title: "继续读取更多",
                            systemImage: "plus.circle.fill",
                            isEnabled: canLoadMorePhotos,
                            action: onLoadMorePhotos
                        )
                    }
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var bottomActionBar: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(selectedAssetsCount > 0 ? "已选择 \(selectedAssetsCount) 项" : "队列中还有 \(queuedJobCount) 项")
                    .font(.system(.subheadline, design: .rounded).weight(.bold))
                    .foregroundStyle(AppTheme.ink)

                Text(autoExportToPhotoLibrary ? "下载后会继续写入系统相册" : "下载后仅保留在应用本地")
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(AppTheme.inkMuted)
                    .lineLimit(1)
            }

            Spacer()

            HStack(spacing: 8) {
                if canPauseDownloads {
                    Button(action: onPauseDownloads) {
                        HStack(spacing: 6) {
                            Image(systemName: "pause.fill")
                                .font(.system(size: 15, weight: .bold))
                            Text("暂停")
                                .font(.system(.subheadline, design: .rounded).weight(.bold))
                        }
                        .foregroundStyle(AppTheme.ink)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(AppTheme.surfaceMuted, in: Capsule())
                    }
                    .buttonStyle(.plain)
                } else if canResumeDownloads {
                    Button(action: onResumeDownloads) {
                        HStack(spacing: 6) {
                            Image(systemName: "play.fill")
                                .font(.system(size: 15, weight: .bold))
                            Text("继续")
                                .font(.system(.subheadline, design: .rounded).weight(.bold))
                        }
                        .foregroundStyle(AppTheme.ink)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(AppTheme.surfaceMuted, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }

                if selectedAssetsCount > 0 {
                    Button(action: onDownloadSelectedAssets) {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.down.circle.fill")
                                .font(.system(size: 18))
                            Text(queuedJobCount > 0 ? "加入队列" : "开始下载")
                                .font(.system(.subheadline, design: .rounded).weight(.bold))
                        }
                        .foregroundStyle(canDownloadSelection ? AppTheme.surface : AppTheme.inkMuted)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(
                            canDownloadSelection
                            ? AppTheme.ink
                            : AppTheme.surfaceMuted,
                            in: Capsule()
                        )
                    }
                    .disabled(!canDownloadSelection)
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .background(AppTheme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
        .shadow(color: AppTheme.shadow, radius: 16, x: 0, y: 8)
    }

    private func downloadProgressCard(_ progress: ActiveDownloadProgress) -> some View {
        DownloadProgressDetails(progress: progress)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(AppTheme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .padding(.horizontal, 16)
            .shadow(color: AppTheme.shadow, radius: 16, x: 0, y: 8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private var overviewTitle: String {
        if let activeSession {
            return assets.isEmpty ? "准备读取 \(activeSession.cameraName)" : activeSession.cameraName
        }

        return "等待相机连接"
    }


    @ViewBuilder
    private func menuRow(title: String, isSelected: Bool) -> some View {
        HStack {
            Text(title)
            if isSelected {
                Spacer()
                Image(systemName: "checkmark")
            }
        }
    }

    private var leadingToolbarPlacement: ToolbarItemPlacement {
        #if os(iOS)
        .navigationBarLeading
        #else
        .automatic
        #endif
    }

    private var trailingToolbarPlacement: ToolbarItemPlacement {
        #if os(iOS)
        .navigationBarTrailing
        #else
        .primaryAction
        #endif
    }
}

private struct PhotoPreviewSeed: Identifiable {
    let assetID: UUID
    let id = UUID()
}

private enum PreviewFilter: CaseIterable, Identifiable {
    case all
    case jpeg
    case raw
    case movie

    var id: Self { self }

    var title: String {
        switch self {
        case .all:
            return "全部"
        case .jpeg:
            return "JPEG"
        case .raw:
            return "RAW"
        case .movie:
            return "视频"
        }
    }

    var systemImageName: String {
        switch self {
        case .all:
            return "square.grid.2x2"
        case .jpeg:
            return "photo"
        case .raw:
            return "camera.aperture"
        case .movie:
            return "video"
        }
    }

    func matches(_ asset: PhotoAsset) -> Bool {
        switch self {
        case .all:
            return true
        case .jpeg:
            return asset.kind == .jpeg || asset.kind == .png
        case .raw:
            return asset.kind == .raw
        case .movie:
            return asset.kind == .movie
        }
    }
}

private struct PhotoAssetGridTile: View {
    private static let photoAspectRatio = 6016.0 / 4016.0

    let asset: PhotoAsset
    let isSelected: Bool
    let loadThumbnail: (PhotoAsset) async -> Data?
    let onToggleSelection: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .topTrailing) {
                PhotoAssetThumbnailPreview(asset: asset, loadThumbnail: loadThumbnail)
                    .aspectRatio(Self.photoAspectRatio, contentMode: .fill)
                    .frame(minWidth: 0, maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                HStack {
                    Text(asset.kind.badgeTitle)
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(asset.kind == .jpeg || asset.kind == .png ? AppTheme.ink : Color.white)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 4)
                        .background(kindBadgeColor.opacity(0.88))
                        .clipShape(Capsule())

                    Spacer(minLength: 6)
                }
                .padding(6)

                Button(action: {
                    Haptics.impact(.light)
                    onToggleSelection()
                }) {
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(isSelected ? AppTheme.accentStrong : Color.white.opacity(0.9))
                        .shadow(color: .black.opacity(0.2), radius: 2, x: 0, y: 1)
                }
                .buttonStyle(.plain)
                .padding(6)
            }
            .background(AppTheme.controlBackground)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(
                        isSelected ? AppTheme.accentStrong : Color.clear,
                        lineWidth: isSelected ? 1.5 : 0
                    )
            )

            Text(asset.fileName)
                .font(.system(.footnote, design: .rounded).weight(.medium))
                .foregroundStyle(AppTheme.ink)
                .lineLimit(1)
                .padding(.horizontal, 2)
        }
        .contentShape(Rectangle())
    }

    private var kindBadgeColor: Color {
        switch asset.kind {
        case .raw:
            return AppTheme.accentStrong
        case .movie:
            return AppTheme.danger
        default:
            return AppTheme.accentSoft
        }
    }
}

private struct PhotoAssetThumbnailPreview: View {
    let asset: PhotoAsset
    let loadThumbnail: (PhotoAsset) async -> Data?

    @State private var thumbnailData: Data?
    @State private var isLoading = false
    @State private var isImageLoaded = false

    var body: some View {
        ZStack {
            if let image = thumbnailImage {
                platformPreviewImage(image)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .opacity(isImageLoaded ? 1.0 : 0.0)
                    .onAppear {
                        withAnimation(.easeOut(duration: 0.35)) {
                            isImageLoaded = true
                        }
                    }
            } else {
                ShimmerView()
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .task(id: asset.remoteIdentifier) {
            guard thumbnailData == nil, !isLoading else { return }
            isLoading = true
            thumbnailData = await loadThumbnail(asset)
            isLoading = false
        }
    }

    private var thumbnailImage: PlatformImage? {
        guard let thumbnailData else {
            return nil
        }

        return PlatformImage(data: thumbnailData)
    }
}

private struct PhotoAssetPreviewScreen: View {
    @Environment(\.dismiss) private var dismiss

    let assets: [PhotoAsset]
    let initialAssetID: UUID
    let onToggleSelection: (PhotoAsset) -> Void
    let loadThumbnail: (PhotoAsset) async -> Data?
    let loadPreview: (PhotoAsset) async -> Data?

    @State private var currentIndex: Int
    @State private var localSelectedAssetIDs: Set<UUID>

    init(
        assets: [PhotoAsset],
        selectedAssetIDs: Set<UUID>,
        initialAssetID: UUID,
        onToggleSelection: @escaping (PhotoAsset) -> Void,
        loadThumbnail: @escaping (PhotoAsset) async -> Data?,
        loadPreview: @escaping (PhotoAsset) async -> Data?
    ) {
        self.assets = assets
        self.initialAssetID = initialAssetID
        self.onToggleSelection = onToggleSelection
        self.loadThumbnail = loadThumbnail
        self.loadPreview = loadPreview

        let resolvedIndex = assets.firstIndex(where: { $0.id == initialAssetID }) ?? 0
        _currentIndex = State(initialValue: resolvedIndex)
        _localSelectedAssetIDs = State(initialValue: selectedAssetIDs)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                headerBar

                previewContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                bottomBar
            }
        }
    }

    private var currentAsset: PhotoAsset {
        let clampedIndex = min(max(currentIndex, 0), max(assets.count - 1, 0))
        return assets[clampedIndex]
    }

    private var canGoToPrevious: Bool {
        currentIndex > 0
    }

    private var canGoToNext: Bool {
        currentIndex < assets.count - 1
    }

    private var isSelected: Bool {
        localSelectedAssetIDs.contains(currentAsset.id)
    }

    private var headerBar: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(.headline, design: .rounded).weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(.ultraThinMaterial)
                    .clipShape(Circle())
                    .overlay(
                        Circle().strokeBorder(Color.white.opacity(0.15), lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                Text(currentAsset.fileName)
                    .font(.system(.subheadline, design: .rounded).weight(.bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)

                Text(Formatters.shortDate(currentAsset.captureDate))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.white.opacity(0.6))
                    .lineLimit(1)
            }

            Spacer(minLength: 12)

            VStack(alignment: .trailing, spacing: 4) {
                Text(currentAsset.kind.badgeTitle)
                    .font(.system(.caption2, design: .rounded).weight(.bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(AppTheme.accent)
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))

                Text("\(currentIndex + 1) / \(assets.count)")
                    .font(.system(.caption2, design: .monospaced).weight(.semibold))
                    .foregroundStyle(.white.opacity(0.6))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            LinearGradient(colors: [Color.black.opacity(0.6), Color.clear], startPoint: .top, endPoint: .bottom)
        )
    }

    private var previewContent: some View {
        ZStack {
            PhotoAssetPreviewPage(
                asset: currentAsset,
                loadThumbnail: loadThumbnail,
                loadPreview: loadPreview
            )
            .id(currentAsset.id)

            HStack {
                navigationButton(
                    systemImage: "chevron.left",
                    isEnabled: canGoToPrevious
                ) {
                    currentIndex -= 1
                }

                Spacer()

                navigationButton(
                    systemImage: "chevron.right",
                    isEnabled: canGoToNext
                ) {
                    currentIndex += 1
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(currentAsset.kind == .movie ? "视频文件" : "照片文件")
                        .font(.system(.subheadline, design: .rounded).weight(.semibold))
                        .foregroundStyle(.white)

                    Text(Formatters.fileSize(currentAsset.byteSize))
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.6))
                }

                Spacer(minLength: 12)

                Button {
                    toggleCurrentSelection()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: isSelected ? "checkmark.circle.fill" : "plus.circle")
                        Text(isSelected ? "已选择" : "选择照片")
                    }
                    .font(.system(.subheadline, design: .rounded).weight(.bold))
                    .foregroundStyle(isSelected ? .black : .white)
                    .frame(minWidth: 110)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(
                        isSelected
                        ? Color(red: 0.99, green: 0.85, blue: 0.05)
                        : Color.white.opacity(0.15)
                    )
                    .clipShape(Capsule())
                    .overlay(
                        Capsule()
                            .strokeBorder(isSelected ? Color.clear : Color.white.opacity(0.2), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 24)
            .background(
                LinearGradient(colors: [Color.clear, Color.black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
            )
        }
    }

    private func toggleCurrentSelection() {
        if localSelectedAssetIDs.contains(currentAsset.id) {
            localSelectedAssetIDs.remove(currentAsset.id)
        } else {
            localSelectedAssetIDs.insert(currentAsset.id)
        }
        onToggleSelection(currentAsset)
    }

    @ViewBuilder
    private func navigationButton(
        systemImage: String,
        isEnabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 42, height: 42)
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .opacity(isEnabled ? 1 : 0.28)
        .disabled(!isEnabled)
    }
}

private struct PhotoAssetPreviewPage: View {
    let asset: PhotoAsset
    let loadThumbnail: (PhotoAsset) async -> Data?
    let loadPreview: (PhotoAsset) async -> Data?

    @State private var previewData: Data?
    @State private var isLoading = false
    @State private var didFailToLoad = false

    var body: some View {
        Group {
            if let image = previewImage {
                ZoomablePhotoPreview(image: image)
                    .padding(.horizontal, 10)
                    .padding(.bottom, 8)
            } else if isLoading {
                VStack(spacing: 14) {
                    ProgressView()
                        .controlSize(.large)
                        .tint(.white)

                    Text("正在载入预览")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white.opacity(0.82))
                }
            } else {
                VStack(spacing: 12) {
                    Image(systemName: asset.kind.systemImageName)
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.84))

                    Text(didFailToLoad ? "暂时无法显示预览" : asset.kind.badgeTitle)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white.opacity(0.78))
                }
            }
        }
        .task(id: asset.remoteIdentifier) {
            await loadPreviewData()
        }
    }

    private var previewImage: PlatformImage? {
        guard let previewData else {
            return nil
        }

        return PlatformImage(data: previewData)
    }

    private func loadPreviewData() async {
        guard previewData == nil, !isLoading else { return }
        isLoading = true
        didFailToLoad = false

        if let thumbnail = await loadThumbnail(asset) {
            previewData = thumbnail
        }

        isLoading = previewData == nil

        if let upgradedPreview = await loadPreview(asset) {
            previewData = upgradedPreview
            isLoading = false
            return
        }

        didFailToLoad = previewData == nil
        isLoading = false
    }
}

private struct ZoomablePhotoPreview: View {
    private static let minimumScale: CGFloat = 1
    private static let maximumScale: CGFloat = 5

    let image: PlatformImage

    @State private var steadyScale: CGFloat = 1
    @State private var gestureScale: CGFloat = 1
    @State private var steadyOffset: CGSize = .zero
    @State private var dragOffset: CGSize = .zero

    var body: some View {
        GeometryReader { proxy in
            platformPreviewImage(image)
                .resizable()
                .scaledToFit()
                .frame(width: proxy.size.width, height: proxy.size.height)
                .scaleEffect(currentScale)
                .offset(currentOffset)
                .contentShape(Rectangle())
                .simultaneousGesture(magnificationGesture)
                .simultaneousGesture(dragGesture)
                .onTapGesture(count: 2) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        if currentScale > 1 {
                            resetTransform()
                        } else {
                            steadyScale = 2
                        }
                    }
                }
        }
    }

    private var currentScale: CGFloat {
        min(max(Self.minimumScale, steadyScale * gestureScale), Self.maximumScale)
    }

    private var currentOffset: CGSize {
        guard currentScale > 1 else {
            return .zero
        }

        return CGSize(
            width: steadyOffset.width + dragOffset.width,
            height: steadyOffset.height + dragOffset.height
        )
    }

    private var magnificationGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                gestureScale = value
            }
            .onEnded { value in
                steadyScale = min(max(Self.minimumScale, steadyScale * value), Self.maximumScale)
                gestureScale = 1

                if steadyScale <= 1.01 {
                    resetTransform()
                }
            }
    }

    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                guard currentScale > 1 else { return }
                dragOffset = value.translation
            }
            .onEnded { value in
                guard currentScale > 1 else {
                    resetTransform()
                    return
                }

                steadyOffset = CGSize(
                    width: steadyOffset.width + value.translation.width,
                    height: steadyOffset.height + value.translation.height
                )
                dragOffset = .zero
            }
    }

    private func resetTransform() {
        steadyScale = 1
        gestureScale = 1
        steadyOffset = .zero
        dragOffset = .zero
    }
}

private func platformPreviewImage(_ image: PlatformImage) -> Image {
    #if canImport(UIKit)
    Image(uiImage: image)
    #elseif canImport(AppKit)
    Image(nsImage: image)
    #endif
}
