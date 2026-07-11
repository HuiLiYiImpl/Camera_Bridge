protocol CameraTransportFactoryProtocol {
    func makeTransport() -> any CameraTransport
}

extension CameraTransportFactory: CameraTransportFactoryProtocol {}
