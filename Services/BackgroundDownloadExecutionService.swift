import Foundation
#if os(iOS)
import UIKit
#endif

@MainActor
final class BackgroundDownloadExecutionService {
    #if os(iOS)
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    #endif

    var isActive: Bool {
        #if os(iOS)
        backgroundTaskID != .invalid
        #else
        false
        #endif
    }

    func beginIfNeeded(name: String, onExpiration: @escaping @MainActor @Sendable () -> Void) {
        #if os(iOS)
        guard backgroundTaskID == .invalid else { return }

        backgroundTaskID = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            Task { @MainActor in
                onExpiration()
                self?.end()
            }
        }
        #endif
    }

    func end() {
        #if os(iOS)
        guard backgroundTaskID != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskID)
        backgroundTaskID = .invalid
        #endif
    }
}
