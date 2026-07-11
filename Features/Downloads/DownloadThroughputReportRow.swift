import SwiftUI

struct DownloadThroughputReportRow: View {
    let report: DownloadThroughputReport

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Image(systemName: iconName)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(sceneColor)
                    .frame(width: 32, height: 32)
                    .background(sceneColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(report.fileName)
                        .font(.system(.subheadline, design: .rounded).weight(.semibold))
                        .foregroundStyle(AppTheme.ink)
                        .lineLimit(1)

                    Text("\(report.transferMode.displayTitle) · \(report.currentScene.displayTitle) · \(report.averageSpeedText)")
                        .font(.system(.caption, design: .rounded).weight(.medium))
                        .foregroundStyle(AppTheme.inkMuted)
                }

                Spacer(minLength: 8)

                Text(report.terminalStatus.displayTitle)
                    .font(.system(.caption2, design: .rounded).weight(.bold))
                    .foregroundStyle(statusColor)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(statusColor.opacity(0.12), in: Capsule())
            }

            HStack(spacing: 10) {
                diagnosticPill("耗时 \(Self.durationText(report.durationSeconds))")
                diagnosticPill("样本 \(report.chunkSamples.count)")
                diagnosticPill("活动 \(report.liveActivityUpdateCount)")
                diagnosticPill("持久化 \(report.queuePersistenceCount)")
            }
        }
        .padding(.vertical, 4)
    }

    private var iconName: String {
        switch report.currentScene {
        case .foreground:
            return "iphone"
        case .inactive:
            return "pause.circle"
        case .background:
            return "moon"
        }
    }

    private var sceneColor: Color {
        switch report.currentScene {
        case .foreground:
            return AppTheme.success
        case .inactive:
            return AppTheme.info
        case .background:
            return AppTheme.warning
        }
    }

    private var statusColor: Color {
        switch report.terminalStatus {
        case .completed:
            return AppTheme.success
        case .interrupted, .failed:
            return AppTheme.danger
        case .cancelled:
            return AppTheme.inkMuted
        case .queued, .running, .paused:
            return AppTheme.warning
        }
    }

    private func diagnosticPill(_ text: String) -> some View {
        Text(text)
            .font(.system(.caption2, design: .monospaced).weight(.medium))
            .foregroundStyle(AppTheme.inkMuted)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(AppTheme.surfaceMuted, in: Capsule())
    }

    private static func durationText(_ duration: TimeInterval) -> String {
        String(format: "%.1fs", duration)
    }
}
