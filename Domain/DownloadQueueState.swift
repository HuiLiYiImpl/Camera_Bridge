import Foundation

enum DownloadQueueStatus: String, Codable, CaseIterable, Sendable {
    case idle
    case running
    case paused
    case interrupted

    var displayTitle: String {
        switch self {
        case .idle:
            return "空闲"
        case .running:
            return "下载中"
        case .paused:
            return "已暂停"
        case .interrupted:
            return "已中断"
        }
    }
}

struct DownloadQueueState: Codable, Equatable, Sendable {
    var jobs: [DownloadJob]
    var activeJobID: UUID?
    var status: DownloadQueueStatus

    init(jobs: [DownloadJob] = [], activeJobID: UUID? = nil, status: DownloadQueueStatus = .idle) {
        self.jobs = jobs
        self.activeJobID = activeJobID
        self.status = status
    }

    var activeJob: DownloadJob? {
        guard let activeJobID else { return nil }
        return jobs.first(where: { $0.id == activeJobID })
    }

    var pendingJobs: [DownloadJob] {
        jobs.filter { !$0.status.isTerminal }
    }

    var completedJobs: [DownloadJob] {
        jobs.filter { $0.status == .completed }
    }

    var hasPendingWork: Bool {
        jobs.contains { !$0.status.isTerminal }
    }

    var completedItemCount: Int {
        jobs.filter { $0.status == .completed }.count
    }

    var totalItemCount: Int {
        jobs.count
    }
}
