import SwiftUI

struct SettingsContainerView: View {
    @ObservedObject var connectionViewModel: ConnectionViewModel
    @ObservedObject var shell: AppShellViewModel

    var body: some View {
        SettingsView(
            autoExportToPhotoLibrary: connectionViewModel.autoExportToPhotoLibrary,
            prioritizeJPEGDownloads: connectionViewModel.prioritizeJPEGDownloads,
            portInput: connectionViewModel.portInput,
            hostInput: connectionViewModel.hostInput,
            activityLog: shell.activityLog,
            onSetAutoExportToPhotoLibrary: { isEnabled in
                connectionViewModel.setAutoExportToPhotoLibrary(isEnabled)
            },
            onSetPrioritizeJPEGDownloads: { isEnabled in
                connectionViewModel.setPrioritizeJPEGDownloads(isEnabled)
            }
        )
    }
}
