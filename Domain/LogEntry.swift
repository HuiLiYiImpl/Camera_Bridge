import Foundation

struct LogEntry: Identifiable, Equatable, Sendable {
    let id = UUID()
    let timestamp: Date
    let message: String
}
