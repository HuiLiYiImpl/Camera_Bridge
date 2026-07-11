import SwiftUI

struct ConnectionContainerView: View {
    @ObservedObject var connectionViewModel: ConnectionViewModel
    @ObservedObject var galleryViewModel: GalleryViewModel
    @ObservedObject var shell: AppShellViewModel

    var body: some View {
        ConnectionSetupView(
            workflowState: connectionViewModel.workflowState,
            activeSession: connectionViewModel.activeSession,
            canAttemptConnection: connectionViewModel.canAttemptConnection,
            isWorking: connectionViewModel.isWorking || galleryViewModel.isLoading,
            canRefreshPhotos: galleryViewModel.canRefreshPhotos(for: connectionViewModel.activeSession),
            photoCount: galleryViewModel.photoAssets.count,
            hasMorePhotos: galleryViewModel.hasMorePhotos,
            onConnect: {
                Task {
                    let didConnect = await connectionViewModel.connect()
                    guard didConnect,
                          connectionViewModel.activeSession?.capabilities.contains(.listAssets) == true else {
                        return
                    }
                    await galleryViewModel.refreshPhotos()
                }
            },
            onDisconnect: {
                connectionViewModel.disconnect()
                galleryViewModel.resetForDisconnectedSession()
            },
            onRefreshPhotos: {
                Task {
                    await galleryViewModel.refreshPhotos()
                }
            }
        )
    }
}

