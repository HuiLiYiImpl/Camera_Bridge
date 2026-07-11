import SwiftUI

struct DownloadsContainerView: View {
    @ObservedObject var viewModel: DownloadManagerViewModel

    var body: some View {
        DownloadsView(
            downloads: viewModel.downloads,
            queuedJobs: viewModel.queuedJobs,
            queueStatus: viewModel.queueStatus,
            activeDownloadProgress: viewModel.activeDownloadProgress,
            throughputReports: viewModel.throughputReports,
            canPauseQueue: viewModel.canPauseQueue,
            canResumeQueue: viewModel.canResumeQueue,
            onRefreshDownloads: {
                Task {
                    await viewModel.refreshDownloads()
                }
            },
            onPauseQueue: {
                viewModel.pauseQueue()
            },
            onResumeQueue: {
                Task {
                    await viewModel.resumeInterruptedDownloads()
                }
            },
            onCancelJob: { job in
                viewModel.cancelJob(job)
            },
            onRetryJob: { job in
                Task {
                    await viewModel.retryJob(job)
                }
            },
            onClearFinishedJobs: {
                viewModel.clearFinishedJobs()
            }
        )
    }
}
