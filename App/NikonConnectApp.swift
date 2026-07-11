import SwiftUI

@main
@MainActor
struct NikonConnectApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var shell: AppShellViewModel
    @StateObject private var connectionViewModel: ConnectionViewModel
    @StateObject private var galleryViewModel: GalleryViewModel
    @StateObject private var downloadViewModel: DownloadManagerViewModel

    init() {
        let shell = AppShellViewModel()
        let sessionCoordinator = CameraSessionCoordinator()
        let preferencesStore = AppPreferencesStore()
        let transportFactory = CameraTransportFactory()
        let downloadStore = DownloadStore()
        let thumbnailService = AssetThumbnailService()
        let photoLibraryExportService = PhotoLibraryExportService()

        _shell = StateObject(wrappedValue: shell)
        _connectionViewModel = StateObject(
            wrappedValue: ConnectionViewModel(
                preferencesStore: preferencesStore,
                transportFactory: transportFactory,
                thumbnailService: thumbnailService,
                sessionCoordinator: sessionCoordinator,
                shell: shell
            )
        )
        _galleryViewModel = StateObject(
            wrappedValue: GalleryViewModel(
                thumbnailService: thumbnailService,
                sessionCoordinator: sessionCoordinator,
                shell: shell
            )
        )
        _downloadViewModel = StateObject(
            wrappedValue: DownloadManagerViewModel(
                downloadStore: downloadStore,
                photoLibraryExportService: photoLibraryExportService,
                sessionCoordinator: sessionCoordinator,
                shell: shell
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            RootTabView(
                shell: shell,
                connectionViewModel: connectionViewModel,
                galleryViewModel: galleryViewModel,
                downloadViewModel: downloadViewModel
            )
            .task {
                await connectionViewModel.bootstrapIfNeeded()
                await downloadViewModel.refreshDownloads()
                await downloadViewModel.loadPersistedQueue()
            }
            .onChange(of: scenePhase) { newPhase in
                downloadViewModel.handleScenePhaseChange(newPhase)
            }
        }
    }
}
