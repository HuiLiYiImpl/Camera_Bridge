import Foundation

struct ActiveDownloadProgress: Equatable, Sendable {
    let fileName: String
    let currentItemNumber: Int
    let totalItemCount: Int
    let bytesTransferred: Int64
    let totalBytes: Int64
    let resumedCount: Int
    let currentOffset: Int64
    let chunkSize: Int64

    var fractionCompleted: Double {
        guard totalBytes > 0 else { return 0 }
        let progress = Double(bytesTransferred) / Double(totalBytes)
        return min(max(progress, 0), 1)
    }

    var percentageText: String {
        "\(Int((fractionCompleted * 100).rounded()))%"
    }
}
