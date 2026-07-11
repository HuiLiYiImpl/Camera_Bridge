import Foundation

struct CameraTransportFactory {
    func makeTransport() -> any CameraTransport {
        ExperimentalNikonTransport()
    }
}
