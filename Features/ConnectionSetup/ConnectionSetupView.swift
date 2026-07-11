import SwiftUI
import Combine

struct ConnectionSetupView: View {
    let workflowState: CameraWorkflowState
    let activeSession: CameraSession?
    let canAttemptConnection: Bool
    let isWorking: Bool
    let canRefreshPhotos: Bool
    let photoCount: Int
    let hasMorePhotos: Bool
    let onConnect: () -> Void
    let onDisconnect: () -> Void
    let onRefreshPhotos: () -> Void

    @State private var brandIndex = 0
    private let brands = ["尼康", "索尼", "佳能", "富士", "任何"]
    private let timer = Timer.publish(every: 2.2, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 24) {
                LensGlowView(state: workflowState)

                VStack(spacing: 12) {
                    if workflowState == .waitingForWifi {
                        HStack(spacing: 0) {
                            Text("连接你的")
                            
                            Text(brands[brandIndex])
                                .foregroundStyle(AppTheme.accentStrong)
                                .padding(.horizontal, 4)
                                .id(brandIndex)
                                .transition(.asymmetric(
                                    insertion: .move(edge: .top).combined(with: .opacity),
                                    removal: .move(edge: .bottom).combined(with: .opacity)
                                ))
                            
                            Text("相机")
                        }
                        .font(.system(.title2, design: .rounded).weight(.bold))
                        .foregroundStyle(AppTheme.ink)
                        .onReceive(timer) { _ in
                            withAnimation(.spring(response: 0.45, dampingFraction: 0.8)) {
                                brandIndex = (brandIndex + 1) % brands.count
                            }
                        }
                    } else {
                        Text(heroTitle)
                            .font(.system(.title2, design: .rounded).weight(.bold))
                            .foregroundStyle(AppTheme.ink)
                            .transition(.opacity)
                    }

                    StatusBadgeView(state: workflowState)
                }
            }

            Spacer()

            if activeSession == nil {
                PrimaryActionButton(
                    title: primaryActionTitle,
                    systemImage: "antenna.radiowaves.left.and.right",
                    isEnabled: canAttemptConnection && !isWorking,
                    action: onConnect
                )
                .padding(.horizontal, 24)
                .padding(.bottom, 32)
            } else if let activeSession {
                VStack(spacing: 12) {
                    readySection(activeSession)

                    SecondaryActionButton(
                        title: "断开连接",
                        systemImage: "xmark",
                        isEnabled: !isWorking,
                        foreground: AppTheme.danger,
                        action: onDisconnect
                    )
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppTheme.canvas.ignoresSafeArea())
    }

    private func readySection(_ session: CameraSession) -> some View {
        CustomCard {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .center, spacing: 12) {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AppTheme.surface)
                        .frame(width: 34, height: 34)
                        .background(AppTheme.success, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                    VStack(alignment: .leading, spacing: 3) {
                        Text(session.cameraName)
                            .font(.system(.headline, design: .rounded).weight(.bold))
                            .foregroundStyle(AppTheme.ink)

                        Text("已连接，可以浏览和下载照片")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(AppTheme.inkMuted)
                    }

                    Spacer()
                }

                if photoCount > 0 {
                    HStack(spacing: 6) {
                        Image(systemName: "photo.on.rectangle")
                            .font(.system(.caption, design: .rounded).weight(.semibold))
                            .foregroundStyle(AppTheme.inkMuted)
                        Text(hasMorePhotos ? "\(photoCount)+ 张照片" : "\(photoCount) 张照片")
                            .font(.system(.subheadline, design: .rounded).weight(.medium))
                            .foregroundStyle(AppTheme.ink)
                    }
                }

                if session.capabilities.contains(.listAssets) {
                    SecondaryActionButton(
                        title: photoCount == 0 ? "读取照片" : "重新读取",
                        systemImage: "arrow.clockwise",
                        isEnabled: canRefreshPhotos && !isWorking,
                        action: onRefreshPhotos
                    )
                }
            }
        }
    }

    private var heroTitle: String {
        if let activeSession {
            return "\(activeSession.cameraName) 已连接"
        }

        switch workflowState {
        case .waitingForWifi:
            return "连接你的相机"
        case .connecting:
            return "正在建立连接"
        case .loadingPhotos:
            return "正在读取相册"
        case .downloading:
            return "正在传输文件"
        case .error:
            return "连接失败"
        case .connected:
            return "连接已建立"
        }
    }

    private var primaryActionTitle: String {
        "连接相机"
    }
}
