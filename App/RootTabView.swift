import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct RootTabView: View {
    @ObservedObject var shell: AppShellViewModel
    @ObservedObject var connectionViewModel: ConnectionViewModel
    @ObservedObject var galleryViewModel: GalleryViewModel
    @ObservedObject var downloadViewModel: DownloadManagerViewModel

    init(
        shell: AppShellViewModel,
        connectionViewModel: ConnectionViewModel,
        galleryViewModel: GalleryViewModel,
        downloadViewModel: DownloadManagerViewModel
    ) {
        self.shell = shell
        self.connectionViewModel = connectionViewModel
        self.galleryViewModel = galleryViewModel
        self.downloadViewModel = downloadViewModel
        configureTabBarAppearance()
    }

    var body: some View {
        TabView {
            NavigationStack {
                ConnectionContainerView(
                    connectionViewModel: connectionViewModel,
                    galleryViewModel: galleryViewModel,
                    shell: shell
                )
            }
            .tabItem {
                Label("相机", systemImage: "camera")
            }

            NavigationStack {
                GalleryContainerView(
                    galleryViewModel: galleryViewModel,
                    downloadViewModel: downloadViewModel,
                    connectionViewModel: connectionViewModel
                )
            }
            .tabItem {
                Label("照片", systemImage: "photo.on.rectangle")
            }

            NavigationStack {
                DownloadsContainerView(viewModel: downloadViewModel)
            }
            .tabItem {
                Label("下载", systemImage: "tray.and.arrow.down")
            }

            NavigationStack {
                SettingsContainerView(
                    connectionViewModel: connectionViewModel,
                    shell: shell
                )
            }
            .tabItem {
                Label("设置", systemImage: "gearshape")
            }
        }
        .overlay(alignment: .top) {
            if let activityTitle = shell.globalActivityTitle {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                        .tint(AppTheme.accentStrong)
                    Text(activityTitle)
                        .font(.system(.footnote, design: .rounded).weight(.medium))
                        .foregroundStyle(AppTheme.ink)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(AppTheme.surface, in: Capsule())
                .shadow(color: AppTheme.shadow, radius: 14, x: 0, y: 6)
                .padding(.top, 12)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: shell.globalActivityTitle)
        .tint(AppTheme.accentStrong)
        .background(AppTheme.canvas.ignoresSafeArea())
        .applyTabBarChrome()
        .alert(item: $shell.alertContext) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("确定"))
            )
        }
    }
}

private extension View {
    @ViewBuilder
    func applyTabBarChrome() -> some View {
        #if os(iOS)
        self
            .toolbarBackground(AppTheme.surface, for: .tabBar)
            .toolbarBackground(.visible, for: .tabBar)
            .toolbarColorScheme(.light, for: .tabBar)
        #else
        self
        #endif
    }
}

@MainActor
private func configureTabBarAppearance() {
    #if canImport(UIKit)
    let appearance = UITabBarAppearance()
    appearance.configureWithOpaqueBackground()
    appearance.backgroundColor = UIColor(AppTheme.surface)
    appearance.shadowColor = UIColor(AppTheme.separator)
    UITabBar.appearance().standardAppearance = appearance
    UITabBar.appearance().scrollEdgeAppearance = appearance
    UITabBar.appearance().unselectedItemTintColor = UIColor(AppTheme.inkMuted)
    #endif
}
