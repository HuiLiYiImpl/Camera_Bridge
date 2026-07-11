import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

struct SectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.system(.footnote, design: .rounded).weight(.semibold))
            .foregroundStyle(AppTheme.inkMuted)
    }
}

struct CustomCard<Content: View>: View {
    let title: String?
    let content: Content

    init(title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                Text(title)
                    .font(.system(.footnote, design: .rounded).weight(.semibold))
                    .foregroundStyle(AppTheme.ink)
                    .padding(.horizontal, 2)
            }

            VStack(alignment: .leading, spacing: 12) {
                content
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppTheme.surfaceElevated, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: AppTheme.shadow, radius: 18, x: 0, y: 8)
        }
    }
}

struct PrimaryActionButton: View {
    let title: String
    var systemImage: String? = nil
    var isEnabled = true
    var expands = true
    let action: () -> Void

    var body: some View {
        Button(action: {
            Haptics.impact(.medium)
            action()
        }) {
            HStack(spacing: 8) {
                if expands {
                    Spacer(minLength: 0)
                }

                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(.body, design: .rounded).weight(.semibold))
                }

                Text(title)
                    .font(.system(.body, design: .rounded).weight(.bold))

                if expands {
                    Spacer(minLength: 0)
                }
            }
            .foregroundStyle(isEnabled ? AppTheme.surface : AppTheme.inkMuted)
            .padding(.horizontal, expands ? 20 : 18)
            .padding(.vertical, 14)
            .background(
                isEnabled ? AppTheme.ink : AppTheme.surfaceMuted,
                in: Capsule()
            )
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }
}

struct SecondaryActionButton: View {
    let title: String
    var systemImage: String? = nil
    var isEnabled = true
    var expands = true
    var foreground: Color = AppTheme.ink
    let action: () -> Void

    var body: some View {
        Button(action: {
            Haptics.impact(.light)
            action()
        }) {
            HStack(spacing: 8) {
                if expands {
                    Spacer(minLength: 0)
                }

                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(.body, design: .rounded).weight(.semibold))
                }

                Text(title)
                    .font(.system(.body, design: .rounded).weight(.semibold))

                if expands {
                    Spacer(minLength: 0)
                }
            }
            .foregroundStyle(isEnabled ? foreground : AppTheme.inkMuted)
            .padding(.horizontal, expands ? 20 : 18)
            .padding(.vertical, 14)
            .background(AppTheme.surface, in: Capsule())
            .overlay(
                Capsule()
                    .stroke(AppTheme.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }
}

struct GridRowItem: View {
    let label: String
    let value: String
    let systemImage: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(.caption, design: .rounded).weight(.medium))
                .foregroundStyle(AppTheme.inkMuted)
                .frame(width: 16, height: 16)
                .padding(.top, 2)

            Text(label)
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(AppTheme.inkMuted)

            Spacer(minLength: 12)

            Text(value)
                .font(.system(.footnote, design: .rounded).weight(.medium))
                .foregroundStyle(AppTheme.ink)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }
}

struct DownloadProgressDetails: View {
    let progress: ActiveDownloadProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(progress.fileName)
                        .font(.system(.subheadline, design: .rounded).weight(.semibold))
                        .foregroundStyle(AppTheme.ink)
                        .lineLimit(1)

                    Text("第 \(progress.currentItemNumber) / \(progress.totalItemCount) 项")
                        .font(.system(.caption2, design: .rounded).weight(.medium))
                        .foregroundStyle(AppTheme.inkMuted)
                }

                Spacer()

                Text(progress.percentageText)
                    .font(.system(.title3, design: .rounded).weight(.bold))
                    .foregroundStyle(AppTheme.accentStrong)
            }

            ProgressView(value: progress.fractionCompleted)
                .tint(AppTheme.accentStrong)
                .scaleEffect(y: 0.8, anchor: .center)

            Text("\(Formatters.fileSize(progress.bytesTransferred)) / \(Formatters.fileSize(progress.totalBytes))")
                .font(.system(.caption2, design: .monospaced).weight(.medium))
                .foregroundStyle(AppTheme.inkMuted)
        }
    }
}

struct LensGlowView: View {
    let state: CameraWorkflowState
    @State private var waveScale: CGFloat = 1.0
    @State private var waveOpacity: Double = 0.5

    var body: some View {
        ZStack {
            // Background breathing halo
            if isSearching {
                Circle()
                    .fill(AppTheme.workflowColor(for: state).opacity(0.15))
                    .frame(width: 140, height: 140)
                    .scaleEffect(waveScale)
                    .opacity(waveOpacity)
                    .task(id: state) {
                        // Reset and animate loop
                        waveScale = 1.0
                        waveOpacity = 0.6
                        withAnimation(
                            .easeInOut(duration: 1.8)
                            .repeatForever(autoreverses: false)
                        ) {
                            waveScale = 1.6
                            waveOpacity = 0.0
                        }
                    }
            } else {
                Circle()
                    .fill(glowColor.opacity(0.08))
                    .frame(width: 140, height: 140)
            }

            // Foreground white circle card
            Circle()
                .fill(AppTheme.surface)
                .frame(width: 110, height: 110)
                .shadow(color: AppTheme.shadow, radius: 20, x: 0, y: 10)

            // Inner camera icon
            Image(systemName: state == .connected ? "camera.fill" : "camera")
                .font(.system(size: 38, weight: .light))
                .foregroundStyle(iconColor)
        }
        .frame(width: 200, height: 200)
    }

    private var isSearching: Bool {
        state == .connecting || state == .loadingPhotos || state == .downloading
    }

    private var glowColor: Color {
        AppTheme.workflowColor(for: state)
    }

    private var iconColor: Color {
        state == .connected ? AppTheme.success : AppTheme.ink
    }
}

struct Haptics {
    enum ImpactStyle {
        case light, medium, heavy
    }
    enum NotificationType {
        case success, warning, error
    }

    @MainActor
    static func impact(_ style: ImpactStyle = .light) {
        #if os(iOS)
        let uiStyle: UIImpactFeedbackGenerator.FeedbackStyle
        switch style {
        case .light: uiStyle = .light
        case .medium: uiStyle = .medium
        case .heavy: uiStyle = .heavy
        }
        let generator = UIImpactFeedbackGenerator(style: uiStyle)
        generator.prepare()
        generator.impactOccurred()
        #endif
    }

    @MainActor
    static func notification(_ type: NotificationType) {
        #if os(iOS)
        let uiType: UINotificationFeedbackGenerator.FeedbackType
        switch type {
        case .success: uiType = .success
        case .warning: uiType = .warning
        case .error: uiType = .error
        }
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(uiType)
        #endif
    }
}

struct ShimmerView: View {
    @State private var phase: CGFloat = 0.0

    var body: some View {
        AppTheme.surfaceMuted
            .overlay(
                GeometryReader { geo in
                    let w = geo.size.width
                    LinearGradient(
                        colors: [.clear, Color.white.opacity(0.48), .clear],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: w * 1.5)
                    .offset(x: (phase - 0.5) * w * 2.5)
                }
            )
            .mask(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .task {
                withAnimation(.linear(duration: 1.5).repeatForever(autoreverses: false)) {
                    phase = 1.0
                }
            }
    }
}
