import Foundation
import SwiftUI

@MainActor
final class GalleryViewModel: ObservableObject {
    @Published var photoAssets: [PhotoAsset] = []
    @Published var hasMorePhotos = false
    @Published var selectedAssetIDs: Set<UUID> = []
    @Published var isLoading = false

    private let thumbnailService: any AssetThumbnailServing
    private let sessionCoordinator: CameraSessionCoordinator
    private let shell: AppShellViewModel
    private let initialPhotoPageSize = 30
    private let additionalPhotoPageSize = 60

    init(
        thumbnailService: any AssetThumbnailServing,
        sessionCoordinator: CameraSessionCoordinator,
        shell: AppShellViewModel
    ) {
        self.thumbnailService = thumbnailService
        self.sessionCoordinator = sessionCoordinator
        self.shell = shell
    }

    var selectedAssetsCount: Int {
        selectedAssetIDs.count
    }

    var selectedAssets: [PhotoAsset] {
        photoAssets.filter { selectedAssetIDs.contains($0.id) }
    }

    func canRefreshPhotos(for session: CameraSession?) -> Bool {
        session?.capabilities.contains(.listAssets) == true && !isLoading
    }

    func canLoadMorePhotos(for session: CameraSession?) -> Bool {
        session?.capabilities.contains(.listAssets) == true && hasMorePhotos && !isLoading
    }

    func refreshPhotos() async {
        do {
            isLoading = true
            shell.setGlobalActivityTitle(CameraWorkflowState.loadingPhotos.title)
            try await loadPhotos(resetTraversal: true)
        } catch {
            await appendActiveTransportDiagnostics()
            shell.handle(error)
        }
        isLoading = false
        shell.setGlobalActivityTitle(nil)
    }

    func loadMorePhotos() async {
        do {
            isLoading = true
            shell.setGlobalActivityTitle(CameraWorkflowState.loadingPhotos.title)
            try await loadPhotos(resetTraversal: false)
        } catch {
            await appendActiveTransportDiagnostics()
            shell.handle(error)
        }
        isLoading = false
        shell.setGlobalActivityTitle(nil)
    }

    func resetForDisconnectedSession() {
        photoAssets = []
        hasMorePhotos = false
        selectedAssetIDs = []
        Task {
            await thumbnailService.clear()
        }
    }

    func toggleSelection(for asset: PhotoAsset) {
        if selectedAssetIDs.contains(asset.id) {
            selectedAssetIDs.remove(asset.id)
        } else {
            selectedAssetIDs.insert(asset.id)
        }
    }

    func selectAllAssets() {
        selectedAssetIDs = Set(photoAssets.map(\.id))
    }

    func clearSelection() {
        selectedAssetIDs.removeAll()
    }

    func thumbnailData(for asset: PhotoAsset) async -> Data? {
        guard let session = sessionCoordinator.activeSession,
              let transport = sessionCoordinator.activeTransport else {
            return nil
        }

        return await thumbnailService.thumbnailData(
            for: asset,
            using: transport,
            session: session
        )
    }

    func previewData(for asset: PhotoAsset) async -> Data? {
        guard let session = sessionCoordinator.activeSession,
              let transport = sessionCoordinator.activeTransport else {
            return nil
        }

        return await thumbnailService.previewData(
            for: asset,
            using: transport,
            session: session
        )
    }

    private func loadPhotos(resetTraversal: Bool) async throws {
        guard let session = sessionCoordinator.activeSession,
              let transport = sessionCoordinator.activeTransport else {
            throw CameraAppError.notConnected
        }

        if resetTraversal {
            await thumbnailService.clear()
        }

        let pageSize = resetTraversal ? initialPhotoPageSize : additionalPhotoPageSize
        shell.appendLog(
            resetTraversal
                ? "正在读取首批 \(pageSize) 张照片..."
                : "正在继续读取更多照片（每次 \(pageSize) 张）..."
        )

        let page = try await transport.fetchAssetsPage(
            for: session,
            resetTraversal: resetTraversal,
            limit: pageSize
        )
        await appendTransportDiagnostics(from: transport)

        photoAssets = PhotoAssetMerge.preservingCameraOrder(
            existing: photoAssets,
            incoming: page.assets,
            resetTraversal: resetTraversal
        )
        hasMorePhotos = page.hasMore

        if resetTraversal {
            selectedAssetIDs.removeAll()
        }

        let summary: String
        if resetTraversal {
            summary = page.hasMore
                ? "已读取首批 \(photoAssets.count) 张照片，可继续读取更多。"
                : "已读取 \(photoAssets.count) 张照片。"
        } else if page.assets.isEmpty, !page.hasMore {
            summary = "已经读完，累计 \(photoAssets.count) 张照片。"
        } else {
            summary = page.hasMore
                ? "又读取了 \(page.assets.count) 张，当前已加载 \(photoAssets.count) 张。"
                : "又读取了 \(page.assets.count) 张，已全部读取，共 \(photoAssets.count) 张。"
        }
        shell.appendLog(summary)
    }

    private func appendActiveTransportDiagnostics() async {
        guard let transport = sessionCoordinator.activeTransport else { return }
        await appendTransportDiagnostics(from: transport)
    }

    private func appendTransportDiagnostics(from transport: any CameraTransport) async {
        let messages = await transport.consumeDiagnostics()
        for message in messages where !message.isEmpty {
            shell.appendLog("诊断: \(message)")
        }
    }
}
