@MainActor
final class CameraSessionCoordinator {
    private(set) var activeSession: CameraSession?
    private(set) var activeTransport: (any CameraTransport)?

    var hasActiveSession: Bool {
        activeSession != nil && activeTransport != nil
    }

    func setActiveSession(_ session: CameraSession, transport: any CameraTransport) {
        activeSession = session
        activeTransport = transport
    }

    func clearSession() -> (any CameraTransport)? {
        let transport = activeTransport
        activeSession = nil
        activeTransport = nil
        return transport
    }
}
