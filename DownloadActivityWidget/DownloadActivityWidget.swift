import ActivityKit
import SwiftUI
import WidgetKit

@available(iOSApplicationExtension 16.1, *)
struct DownloadActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadActivityAttributes.self) { context in
            DownloadLiveActivityView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.92))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading, spacing: 4) {
                        Label("下载中", systemImage: "arrow.down.circle.fill")
                            .font(.system(.caption, design: .rounded).weight(.bold))
                            .foregroundStyle(Color.orange)
                        Text("第 \(context.state.currentItemNumber) / \(context.state.totalItemCount) 项")
                            .font(.system(.caption2, design: .rounded).weight(.medium))
                            .foregroundStyle(.white.opacity(0.72))
                    }
                }

                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(context.state.percentageText)
                            .font(.system(.title3, design: .rounded).weight(.bold))
                            .foregroundStyle(.white)
                        Text(context.state.status)
                            .font(.system(.caption2, design: .rounded).weight(.semibold))
                            .foregroundStyle(.white.opacity(0.72))
                    }
                }

                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(context.state.currentFileName)
                            .font(.system(.subheadline, design: .rounded).weight(.semibold))
                            .foregroundStyle(.white)
                            .lineLimit(1)

                        ProgressView(value: context.state.fractionCompleted)
                            .tint(Color.orange)

                        HStack(spacing: 10) {
                            Text("\(Self.fileSize(context.state.bytesTransferred)) / \(Self.fileSize(context.state.totalBytes))")
                                .font(.system(.caption2, design: .monospaced).weight(.medium))
                                .foregroundStyle(.white.opacity(0.78))

                            Spacer(minLength: 8)

                            Text(context.state.message)
                                .font(.system(.caption2, design: .rounded))
                                .foregroundStyle(.white.opacity(0.72))
                                .lineLimit(1)
                        }
                    }
                    .padding(.top, 4)
                }
            } compactLeading: {
                Image(systemName: "arrow.down.circle.fill")
                    .foregroundStyle(Color.orange)
            } compactTrailing: {
                Text(context.state.percentageText)
                    .font(.system(.caption, design: .rounded).weight(.bold))
                    .foregroundStyle(.white)
            } minimal: {
                ZStack {
                    Circle()
                        .stroke(Color.white.opacity(0.2), lineWidth: 3)
                    Circle()
                        .trim(from: 0, to: max(min(context.state.fractionCompleted, 1), 0.02))
                        .stroke(Color.orange, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Image(systemName: "arrow.down")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                }
            }
        }
    }

    static func fileSize(_ byteSize: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: byteSize)
    }
}

@available(iOSApplicationExtension 16.1, *)
private struct DownloadLiveActivityView: View {
    let state: DownloadActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(state.currentFileName)
                        .font(.system(.subheadline, design: .rounded).weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)

                    Text("第 \(state.currentItemNumber) / \(state.totalItemCount) 项")
                        .font(.system(.caption2, design: .rounded).weight(.medium))
                        .foregroundStyle(.white.opacity(0.72))
                }

                Spacer(minLength: 12)

                Text(state.percentageText)
                    .font(.system(.title3, design: .rounded).weight(.bold))
                    .foregroundStyle(.white)
            }

            ProgressView(value: state.fractionCompleted)
                .tint(Color.orange)

            HStack(spacing: 10) {
                Text("\(DownloadActivityWidget.fileSize(state.bytesTransferred)) / \(DownloadActivityWidget.fileSize(state.totalBytes))")
                    .font(.system(.caption2, design: .monospaced).weight(.medium))
                    .foregroundStyle(.white.opacity(0.78))

                Spacer(minLength: 8)

                Text(state.status)
                    .font(.system(.caption2, design: .rounded).weight(.semibold))
                    .foregroundStyle(.orange)
            }

            Text(state.message)
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.white.opacity(0.72))
                .lineLimit(1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(Color.black.opacity(0.88))
    }
}
