import Foundation

#if os(iOS) && canImport(ActivityKit)
import ActivityKit
#endif

@MainActor
final class DownloadLiveActivityController {
    private var activityID: String?

    func start(
        queueID: UUID,
        totalItemCount: Int,
        state: DownloadLiveActivityState
    ) {
        #if os(iOS) && canImport(ActivityKit)
        guard #available(iOS 16.1, *) else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        guard activityID == nil else {
            update(state: state)
            return
        }

        let attributes = DownloadActivityAttributes(
            queueID: queueID,
            totalItemCount: totalItemCount
        )
        let contentState = state.activityContentState

        do {
            let activity: Activity<DownloadActivityAttributes>
            if #available(iOS 16.2, *) {
                activity = try Activity.request(
                    attributes: attributes,
                    content: ActivityContent(state: contentState, staleDate: nil),
                    pushType: nil
                )
            } else {
                activity = try Activity.request(
                    attributes: attributes,
                    contentState: contentState,
                    pushType: nil
                )
            }
            activityID = activity.id
        } catch {
            AppLogger.downloads.warning("Failed to start download Live Activity: \(error.localizedDescription, privacy: .public)")
        }
        #endif
    }

    func update(state: DownloadLiveActivityState) {
        #if os(iOS) && canImport(ActivityKit)
        guard #available(iOS 16.1, *), let activityID else { return }
        let contentState = state.activityContentState

        Task.detached(priority: .utility) {
            await Self.updateActivity(id: activityID, contentState: contentState)
        }
        #endif
    }

    func end(state: DownloadLiveActivityState, dismissalPolicy: DownloadLiveActivityDismissalPolicy = .default) {
        #if os(iOS) && canImport(ActivityKit)
        guard #available(iOS 16.1, *), let activityID else { return }
        self.activityID = nil
        let contentState = state.activityContentState
        let resolvedPolicy = dismissalPolicy.activityUIDismissalPolicy

        Task.detached(priority: .utility) {
            await Self.endActivity(id: activityID, contentState: contentState, dismissalPolicy: resolvedPolicy)
        }
        #endif
    }

    #if os(iOS) && canImport(ActivityKit)
    @available(iOS 16.1, *)
    private func currentActivity() -> Activity<DownloadActivityAttributes>? {
        guard let activityID else { return nil }
        return Self.activity(id: activityID)
    }

    @available(iOS 16.1, *)
    nonisolated private static func activity(id: String) -> Activity<DownloadActivityAttributes>? {
        Activity<DownloadActivityAttributes>.activities.first { $0.id == id }
    }

    @available(iOS 16.1, *)
    nonisolated private static func updateActivity(
        id: String,
        contentState: DownloadActivityAttributes.ContentState
    ) async {
        guard let activity = activity(id: id) else { return }

        if #available(iOS 16.2, *) {
            await activity.update(ActivityContent(state: contentState, staleDate: nil))
        } else {
            await activity.update(using: contentState)
        }
    }

    @available(iOS 16.1, *)
    nonisolated private static func endActivity(
        id: String,
        contentState: DownloadActivityAttributes.ContentState,
        dismissalPolicy: ActivityUIDismissalPolicy
    ) async {
        guard let activity = activity(id: id) else { return }

        if #available(iOS 16.2, *) {
            await activity.end(
                ActivityContent(state: contentState, staleDate: nil),
                dismissalPolicy: dismissalPolicy
            )
        } else {
            await activity.end(using: contentState, dismissalPolicy: dismissalPolicy)
        }
    }
    #endif
}

struct DownloadLiveActivityState: Sendable {
    let currentFileName: String
    let currentItemNumber: Int
    let totalItemCount: Int
    let bytesTransferred: Int64
    let totalBytes: Int64
    let fractionCompleted: Double
    let status: String
    let message: String

    init(job: DownloadJob, itemNumber: Int, totalItemCount: Int, message: String? = nil) {
        self.currentFileName = job.fileName
        self.currentItemNumber = itemNumber
        self.totalItemCount = totalItemCount
        self.bytesTransferred = job.bytesTransferred
        self.totalBytes = max(job.totalBytes, job.byteSize)
        self.fractionCompleted = job.fractionCompleted
        self.status = job.status.displayTitle
        self.message = message ?? job.errorMessage ?? Self.defaultMessage(for: job.status)
    }

    private static func defaultMessage(for status: DownloadJobStatus) -> String {
        switch status {
        case .queued:
            return "等待开始下载"
        case .running:
            return "保持 Nikon Wi‑Fi 连接"
        case .paused:
            return "下载队列已暂停"
        case .interrupted:
            return "重新打开 App 并连接相机后可继续"
        case .cancelled:
            return "下载已取消"
        case .completed:
            return "下载已完成"
        case .failed:
            return "下载失败，可稍后重试"
        }
    }
}

enum DownloadLiveActivityDismissalPolicy: Sendable {
    case `default`
    case immediate
    case after(Date)
}

#if os(iOS) && canImport(ActivityKit)
@available(iOS 16.1, *)
private extension DownloadLiveActivityState {
    var activityContentState: DownloadActivityAttributes.ContentState {
        DownloadActivityAttributes.ContentState(
            currentFileName: currentFileName,
            currentItemNumber: currentItemNumber,
            totalItemCount: totalItemCount,
            bytesTransferred: bytesTransferred,
            totalBytes: totalBytes,
            fractionCompleted: fractionCompleted,
            status: status,
            message: message
        )
    }
}

@available(iOS 16.1, *)
private extension DownloadLiveActivityDismissalPolicy {
    var activityUIDismissalPolicy: ActivityUIDismissalPolicy {
        switch self {
        case .default:
            return .default
        case .immediate:
            return .immediate
        case .after(let date):
            return .after(date)
        }
    }
}
#endif
