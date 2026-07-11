import SwiftUI

struct SettingsView: View {
    let autoExportToPhotoLibrary: Bool
    let prioritizeJPEGDownloads: Bool
    let portInput: String
    let hostInput: String
    let activityLog: [LogEntry]
    let onSetAutoExportToPhotoLibrary: (Bool) -> Void
    let onSetPrioritizeJPEGDownloads: (Bool) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                preferencesSection
                defaultsSection
                supportSection
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 20)
        }
        .background(AppTheme.canvas.ignoresSafeArea())
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var preferencesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "下载行为")

            CustomCard {
                VStack(alignment: .leading, spacing: 14) {
                    Toggle(
                        "允许自动导出到系统相册",
                        isOn: Binding(
                            get: { autoExportToPhotoLibrary },
                            set: { onSetAutoExportToPhotoLibrary($0) }
                        )
                    )
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .tint(AppTheme.accentStrong)

                    Divider()

                    VStack(alignment: .leading, spacing: 8) {
                        Toggle(
                            "启用 JPEG 优先 / RAW 后补",
                            isOn: Binding(
                                get: { prioritizeJPEGDownloads },
                                set: { onSetPrioritizeJPEGDownloads($0) }
                            )
                        )
                        .font(.system(.body, design: .rounded).weight(.semibold))
                        .tint(AppTheme.accentStrong)

                        Text("选中混合格式时，会优先下载 JPEG / PNG，再继续下载 RAW 和视频，体感会更快。")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(AppTheme.inkMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }

    private var defaultsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "连接默认值")

            CustomCard {
                VStack(alignment: .leading, spacing: 12) {
                    GridRowItem(label: "目标地址", value: resolvedTarget, systemImage: "network")
                    GridRowItem(label: "下载后处理", value: exportSummary, systemImage: "photo.badge.arrow.down")
                    GridRowItem(label: "下载排序", value: downloadPrioritySummary, systemImage: "arrow.up.arrow.down")
                }
            }
        }
    }

    private var supportSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "支持与版本")

            CustomCard {
                VStack(alignment: .leading, spacing: 14) {
                    DisclosureGroup("关于此版本") {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Nikon Connect v0.1.0")
                                .font(.system(.subheadline, design: .rounded).weight(.bold))
                                .foregroundStyle(AppTheme.ink)
                        }
                        .padding(.top, 12)
                    }
                    .font(.system(.subheadline, design: .rounded).weight(.semibold))
                    .tint(AppTheme.ink)

                    if !activityLog.isEmpty {
                        Divider()

                        DisclosureGroup("运行记录") {
                            VStack(alignment: .leading, spacing: 12) {
                                ForEach(activityLog.prefix(12)) { entry in
                                    HStack(alignment: .top, spacing: 8) {
                                        Circle()
                                            .fill(logColor(for: entry.message))
                                            .frame(width: 6, height: 6)
                                            .padding(.top, 5)

                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(entry.message)
                                                .font(.system(.caption, design: .monospaced))
                                                .foregroundStyle(textColor(for: entry.message))
                                                .fixedSize(horizontal: false, vertical: true)

                                            Text(Formatters.logTime(entry.timestamp))
                                                .font(.system(.caption2, design: .monospaced))
                                                .foregroundStyle(AppTheme.inkMuted.opacity(0.75))
                                        }
                                    }
                                }
                            }
                            .padding(.top, 12)
                        }
                        .font(.system(.subheadline, design: .rounded).weight(.semibold))
                        .tint(AppTheme.ink)
                    }
                }
            }
        }
    }

    private var resolvedTarget: String {
        let host = hostInput.isEmpty ? (CameraTransportMode.experimentalNikon.defaultHost ?? "192.168.1.1") : hostInput
        let port = portInput.isEmpty ? String(CameraTransportMode.experimentalNikon.defaultPort) : portInput
        return "\(host):\(port)"
    }

    private var exportSummary: String {
        autoExportToPhotoLibrary ? "下载后同步到系统相册" : "仅保留在应用本地"
    }

    private var downloadPrioritySummary: String {
        prioritizeJPEGDownloads ? "JPEG / PNG 优先，RAW 后补" : "保持相机当前顺序"
    }

    private func logColor(for msg: String) -> Color {
        if msg.contains("失败") || msg.contains("错误") || msg.contains("Error") {
            return AppTheme.danger
        } else if msg.contains("诊断") {
            return AppTheme.info
        } else if msg.contains("成功") || msg.contains("已连接") {
            return AppTheme.success
        } else {
            return AppTheme.warning
        }
    }

    private func textColor(for msg: String) -> Color {
        if msg.contains("失败") || msg.contains("错误") || msg.contains("Error") {
            return AppTheme.danger
        } else if msg.contains("诊断") {
            return AppTheme.info
        } else if msg.contains("成功") || msg.contains("已连接") {
            return AppTheme.success
        } else {
            return AppTheme.ink
        }
    }
}
