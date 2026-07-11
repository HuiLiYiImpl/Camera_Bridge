import Foundation

struct AlertContext: Identifiable, Sendable {
    let id = UUID()
    let title: String
    let message: String
}
