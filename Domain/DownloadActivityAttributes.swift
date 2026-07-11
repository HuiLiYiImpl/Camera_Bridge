import Foundation

#if os(iOS) && canImport(ActivityKit)
import ActivityKit

@available(iOS 16.1, *)
struct DownloadActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        let currentFileName: String
        let currentItemNumber: Int
        let totalItemCount: Int
        let bytesTransferred: Int64
        let totalBytes: Int64
        let fractionCompleted: Double
        let status: String
        let message: String

        var percentageText: String {
            "\(Int((fractionCompleted * 100).rounded()))%"
        }
    }

    let queueID: UUID
    let totalItemCount: Int
}
#endif
