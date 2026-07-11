import SwiftUI

struct GalleryContainerView: View {
    @ObservedObject var galleryViewModel: GalleryViewModel
    @ObservedObject var downloadViewModel: DownloadManagerViewModel
    @ObservedObject var connectionViewModel: ConnectionViewModel

    var body: some View {
        PhotoBrowserView(
            activeSession: connectionViewModel.activeSession,
            assets: galleryViewModel.photoAssets,
            hasMorePhotos: galleryViewModel.hasMorePhotos,
            selectedAssetIDs: galleryViewModel.selectedAssetIDs,
            autoExportToPhotoLibrary: connectionViewModel.autoExportToPhotoLibrary,
            canRefreshPhotos: galleryViewModel.canRefreshPhotos(for: connectionViewModel.activeSession),
            canLoadMorePhotos: galleryViewModel.canLoadMorePhotos(for: connectionViewModel.activeSession),
            canDownloadSelection: downloadViewModel.canDownload(galleryViewModel.selectedAssets, session: connectionViewModel.activeSession),
            activeDownloadProgress: downloadViewModel.activeDownloadProgress,
            canPauseDownloads: downloadViewModel.canPauseQueue,
            canResumeDownloads: downloadViewModel.canResumeQueue,
            queuedJobCount: downloadViewModel.queuedJobs.count,
            onRefreshPhotos: {
                Task {
                    await galleryViewModel.refreshPhotos()
                }
            },
            onLoadMorePhotos: {
                Task {
                    await galleryViewModel.loadMorePhotos()
                }
            },
            onToggleSelection: { asset in
                galleryViewModel.toggleSelection(for: asset)
            },
            onSelectAllAssets: {
                galleryViewModel.selectAllAssets()
            },
            onClearSelection: {
                galleryViewModel.clearSelection()
            },
            onDownloadSelectedAssets: {
                Task {
                    let didDownload = await downloadViewModel.downloadAssets(
                        galleryViewModel.selectedAssets,
                        autoExportToPhotoLibrary: connectionViewModel.autoExportToPhotoLibrary,
                        prioritizeJPEGDownloads: connectionViewModel.prioritizeJPEGDownloads
                    )
                    if didDownload {
                        galleryViewModel.clearSelection()
                    }
                }
            },
            onPauseDownloads: {
                downloadViewModel.pauseQueue()
            },
            onResumeDownloads: {
                Task {
                    await downloadViewModel.resumeInterruptedDownloads()
                }
            },
            loadThumbnail: { asset in
                await galleryViewModel.thumbnailData(for: asset)
            },
            loadPreview: { asset in
                await galleryViewModel.previewData(for: asset)
            }
        )
    }
}
