import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

struct DownloadsView: View {
    let downloads: [DownloadRecord]
    let queuedJobs: [DownloadJob]
    let queueStatus: DownloadQueueStatus
    let activeDownloadProgress: ActiveDownloadProgress?
    let throughputReports: [DownloadThroughputReport]
    let canPauseQueue: Bool
    let canResumeQueue: Bool
    let onRefreshDownloads: () -> Void
    let onPauseQueue: () -> Void
    let onResumeQueue: () -> Void
    let onCancelJob: (DownloadJob) -> Void
    let onRetryJob: (DownloadJob) -> Void
    let onClearFinishedJobs: () -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                overviewSection

                if !queuedJobs.isEmpty {
                    queueSection
                }

                if let activeDownloadProgress {
                    activeDownloadSection(activeDownloadProgress)
                }

                if !throughputReports.isEmpty {
                    throughputDiagnosticsSection
                }

                recordsSection
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 20)
        }
        .background(AppTheme.canvas.ignoresSafeArea())
        .navigationTitle("下载")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: onRefreshDownloads) {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
    }

    private var exportedCount: Int {
        downloads.filter(\.exportedToPhotoLibrary).count
    }

    private var runningCount: Int {
        queuedJobs.filter { $0.status == .running }.count
    }

    private var resumableCount: Int {
        queuedJobs.filter { $0.status.canResume }.count
    }

    private var finishedQueueCount: Int {
        queuedJobs.filter(\.status.isTerminal).count
    }

    private var overviewSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("下载记录")
                .font(.system(.title2, design: .rounded).weight(.bold))
                .foregroundStyle(AppTheme.ink)

            HStack(spacing: 12) {
                MetricTile(
                    label: "记录数",
                    value: "\(downloads.count)",
                    systemImage: "tray.full",
                    accent: AppTheme.info
                )

                MetricTile(
                    label: "已入相册",
                    value: "\(exportedCount)",
                    systemImage: "photo.stack",
                    accent: AppTheme.success
                )
            }
        }
    }

    private var queueSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "下载队列")

            CustomCard {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 12) {
                        MetricTile(
                            label: "当前状态",
                            value: queueStatus.displayTitle,
                            systemImage: "waveform.path.ecg",
                            accent: queueStatusAccent
                        )

                        MetricTile(
                            label: "待处理",
                            value: "\(resumableCount)",
                            systemImage: "clock.arrow.circlepath",
                            accent: AppTheme.warning
                        )
                    }

                    HStack(spacing: 8) {
                        if canPauseQueue {
                            SecondaryActionButton(
                                title: "暂停队列",
                                systemImage: "pause.fill",
                                expands: false,
                                action: onPauseQueue
                            )
                        }

                        if canResumeQueue {
                            PrimaryActionButton(
                                title: runningCount > 0 ? "继续下载" : "开始队列",
                                systemImage: "play.fill",
                                isEnabled: true,
                                expands: false,
                                action: onResumeQueue
                            )
                        }

                        if finishedQueueCount > 0 {
                            SecondaryActionButton(
                                title: "清理完成项",
                                systemImage: "trash",
                                expands: false,
                                foreground: AppTheme.danger,
                                action: onClearFinishedJobs
                            )
                        }
                    }

                    VStack(spacing: 0) {
                        ForEach(queuedJobs) { job in
                            DownloadJobRow(
                                job: job,
                                onCancel: { onCancelJob(job) },
                                onRetry: { onRetryJob(job) }
                            )

                            if job.id != queuedJobs.last?.id {
                                Divider()
                                    .padding(.vertical, 10)
                            }
                        }
                    }
                }
            }
        }
    }

    private func activeDownloadSection(_ progress: ActiveDownloadProgress) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "传输进度")

            CustomCard {
                DownloadProgressDetails(progress: progress)
            }
        }
    }

    private var throughputDiagnosticsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "传输测速")

            CustomCard {
                VStack(spacing: 0) {
                    ForEach(throughputReports.prefix(5)) { report in
                        DownloadThroughputReportRow(report: report)

                        if report.id != throughputReports.prefix(5).last?.id {
                            Divider()
                                .padding(.vertical, 10)
                        }
                    }
                }
            }
        }
    }

    private var recordsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "最近完成")

            CustomCard {
                if downloads.isEmpty {
                    VStack(alignment: .center, spacing: 10) {
                        Image(systemName: "tray")
                            .font(.system(size: 28, weight: .semibold))
                            .foregroundStyle(AppTheme.inkMuted.opacity(0.4))

                        Text("暂无记录")
                            .font(.system(.subheadline, design: .rounded).weight(.semibold))
                            .foregroundStyle(AppTheme.inkMuted)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                } else {
                    VStack(spacing: 0) {
                        ForEach(downloads) { record in
                            DownloadRecordRow(record: record)

                            if record.id != downloads.last?.id {
                                Divider()
                                    .padding(.vertical, 10)
                            }
                        }
                    }
                }
            }
        }
    }

    private var queueStatusAccent: Color {
        switch queueStatus {
        case .idle:
            return AppTheme.inkMuted
        case .running:
            return AppTheme.warning
        case .paused:
            return AppTheme.info
        case .interrupted:
            return AppTheme.danger
        }
    }
}

private struct DownloadJobRow: View {
    let job: DownloadJob
    let onCancel: () -> Void
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: job.kind.systemImageName)
                    .font(.system(size: 18))
                    .foregroundStyle(iconColor)
                    .frame(width: 36, height: 36)
                    .background(iconColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(job.fileName)
                            .font(.system(.subheadline, design: .rounded).weight(.semibold))
                            .foregroundStyle(AppTheme.ink)
                            .lineLimit(1)

                        Spacer(minLength: 8)

                        Text(job.status.displayTitle)
                            .font(.system(.caption2, design: .rounded).weight(.bold))
                            .foregroundStyle(statusColor)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(statusColor.opacity(0.12), in: Capsule())
                    }

                    ProgressView(value: job.fractionCompleted)
                        .tint(statusColor)
                        .scaleEffect(y: 0.8, anchor: .center)

                    HStack(spacing: 10) {
                        Text(job.percentageText)
                            .font(.system(.caption, design: .monospaced).weight(.semibold))
                            .foregroundStyle(statusColor)

                        Text("\(Formatters.fileSize(job.bytesTransferred)) / \(Formatters.fileSize(max(job.totalBytes, job.byteSize)))")
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(AppTheme.inkMuted)
                    }

                    if let errorMessage = job.errorMessage, !errorMessage.isEmpty {
                        Text(errorMessage)
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(AppTheme.inkMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }

            HStack(spacing: 8) {
                if job.status.canResume {
                    SecondaryActionButton(
                        title: job.status == .queued ? "移除" : "取消",
                        systemImage: "xmark",
                        expands: false,
                        foreground: AppTheme.danger,
                        action: onCancel
                    )
                }

                if job.status == .failed || job.status == .interrupted {
                    PrimaryActionButton(
                        title: "重试",
                        systemImage: "arrow.clockwise",
                        isEnabled: true,
                        expands: false,
                        action: onRetry
                    )
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var iconColor: Color {
        switch job.kind {
        case .jpeg, .png:
            return AppTheme.info
        case .raw:
            return AppTheme.accentStrong
        case .movie:
            return AppTheme.danger
        }
    }

    private var statusColor: Color {
        switch job.status {
        case .queued:
            return AppTheme.info
        case .running:
            return AppTheme.warning
        case .paused:
            return AppTheme.info
        case .interrupted, .failed:
            return AppTheme.danger
        case .cancelled:
            return AppTheme.inkMuted
        case .completed:
            return AppTheme.success
        }
    }
}
