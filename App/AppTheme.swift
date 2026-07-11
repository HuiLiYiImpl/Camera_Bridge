import SwiftUI

enum AppTheme {
    static let accent = Color(red: 0.92, green: 0.82, blue: 0.61)
    static let accentStrong = Color(red: 0.83, green: 0.46, blue: 0.04)
    static let accentSoft = Color(red: 0.98, green: 0.95, blue: 0.89)

    static let ink = Color(red: 0.10, green: 0.10, blue: 0.10)
    static let inkMuted = Color(red: 0.42, green: 0.45, blue: 0.50)
    static let canvas = Color(red: 0.98, green: 0.98, blue: 0.97)
    static let surface = Color.white
    static let surfaceElevated = Color.white
    static let surfaceMuted = Color(red: 0.96, green: 0.96, blue: 0.96)
    static let controlBackground = Color(red: 0.95, green: 0.95, blue: 0.96)
    static let subtleFill = Color.black.opacity(0.03)
    static let border = Color.black.opacity(0.07)
    static let separator = Color.black.opacity(0.08)

    static let info = Color(red: 0.25, green: 0.41, blue: 0.70)
    static let success = Color(red: 0.09, green: 0.64, blue: 0.29)
    static let warning = Color(red: 0.83, green: 0.46, blue: 0.04)
    static let danger = Color(red: 0.86, green: 0.15, blue: 0.18)
    static let shadow = Color.black.opacity(0.05)

    static func workflowColor(for state: CameraWorkflowState) -> Color {
        switch state {
        case .waitingForWifi:
            return info
        case .connecting, .loadingPhotos, .downloading:
            return warning
        case .connected:
            return success
        case .error:
            return danger
        }
    }
}

struct MetricTile: View {
    let label: String
    let value: String
    let systemImage: String
    var accent: Color = AppTheme.ink

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(.footnote, design: .rounded).weight(.bold))
                .foregroundStyle(accent)
                .frame(width: 34, height: 34)
                .background(accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(value)
                    .font(.system(.title3, design: .rounded).weight(.bold))
                    .foregroundStyle(AppTheme.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)

                Text(label)
                    .font(.system(.footnote, design: .rounded).weight(.medium))
                    .foregroundStyle(AppTheme.inkMuted)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 14)
        .background(AppTheme.surfaceMuted, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
