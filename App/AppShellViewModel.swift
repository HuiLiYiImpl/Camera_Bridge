import Foundation
import SwiftUI

@MainActor
final class AppShellViewModel: ObservableObject {
    @Published var activityLog: [LogEntry] = []
    @Published var alertContext: AlertContext?
    @Published var globalActivityTitle: String?

    var isShowingGlobalActivity: Bool {
        globalActivityTitle != nil
    }

    func setGlobalActivityTitle(_ title: String?) {
        globalActivityTitle = title
    }

    func appendLog(_ message: String) {
        let entry = LogEntry(timestamp: Date(), message: message)
        activityLog.insert(entry, at: 0)

        if activityLog.count > 30 {
            activityLog = Array(activityLog.prefix(30))
        }
    }

    func showAlert(title: String, message: String) {
        alertContext = AlertContext(title: title, message: message)
    }

    @discardableResult
    func handle(_ error: Error) -> String {
        let message = userFacingMessage(for: error)
        showAlert(title: "出现问题", message: message)
        appendLog(message)
        AppLogger.app.error("\(message, privacy: .public)")
        return message
    }

    func userFacingMessage(for error: Error) -> String {
        if let localError = error as? LocalizedError {
            return [localError.errorDescription, localError.recoverySuggestion]
                .compactMap { $0 }
                .joined(separator: "\n")
        }

        return error.localizedDescription
    }
}
