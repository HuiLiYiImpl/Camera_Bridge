import Foundation
import Network

actor PTPIPTCPConnection {
    private let host: String
    private let port: UInt16
    private var connection: NWConnection?
    private var receiveBuffer = Data()

    init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }

    func open(timeoutSeconds: Double = 10) async throws {
        close()

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        tcpOptions.enableKeepalive = true

        let parameters = NWParameters(tls: nil, tcp: tcpOptions)
        let connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port)!,
            using: parameters
        )

        self.connection = connection
        self.receiveBuffer = Data()

        try await withTimeout(seconds: timeoutSeconds, detail: "建立 TCP 连接到 \(host):\(port)") {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let state = ConnectionOpenState()

                connection.stateUpdateHandler = { update in
                    switch update {
                    case .ready:
                        state.resumeSuccess(continuation: continuation)
                    case .failed(let error):
                        state.resumeFailure(
                            continuation: continuation,
                            error: PTPIPError.malformedPayload("TCP 连接失败：\(error.localizedDescription)")
                        )
                    case .cancelled:
                        state.resumeFailure(continuation: continuation, error: PTPIPError.connectionClosed)
                    default:
                        break
                    }
                }

                connection.start(queue: .global(qos: .userInitiated))
            }
        }
    }

    func close() {
        connection?.cancel()
        connection = nil
        receiveBuffer = Data()
    }

    func send(_ packet: Data) async throws {
        guard let connection else { throw PTPIPError.connectionClosed }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: packet, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            })
        }
    }

    func receivePacket(timeoutSeconds: Double = 10) async throws -> PTPIPRawPacket {
        let header = try await withTimeout(seconds: timeoutSeconds, detail: "等待 PTP/IP 包头") { [self] in
            try await self.receiveExact(length: 8)
        }

        var headerReader = PTPDataReader(data: header)
        let packetLength = try Int(headerReader.readUInt32())
        guard packetLength >= 8 else { throw PTPIPError.invalidPacketLength }
        let packetType = try PTPIPBinary.decodePacketType(try headerReader.readUInt32())
        let payload = try await withTimeout(seconds: timeoutSeconds, detail: "等待 \(packetType) 的负载") { [self] in
            try await self.receiveExact(length: packetLength - 8)
        }

        return PTPIPRawPacket(type: packetType, payload: payload)
    }

    private func receiveExact(length: Int) async throws -> Data {
        if length == 0 { return Data() }

        while receiveBuffer.count < length {
            let chunk = try await receiveChunk(maximumLength: max(4_096, length - receiveBuffer.count))
            receiveBuffer.append(chunk)
        }

        let result = Data(receiveBuffer.prefix(length))
        receiveBuffer.removeFirst(length)
        return result
    }

    private func receiveChunk(maximumLength: Int) async throws -> Data {
        guard let connection else { throw PTPIPError.connectionClosed }

        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: maximumLength) { data, _, isComplete, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    continuation.resume(returning: data)
                } else if isComplete {
                    continuation.resume(throwing: PTPIPError.connectionClosed)
                } else {
                    continuation.resume(throwing: PTPIPError.connectionClosed)
                }
            }
        }
    }
}

private final class ConnectionOpenState: @unchecked Sendable {
    private let lock = NSLock()
    private var didResume = false

    func resumeSuccess(continuation: CheckedContinuation<Void, Error>) {
        lock.lock()
        defer { lock.unlock() }

        guard !didResume else { return }
        didResume = true
        continuation.resume(returning: ())
    }

    func resumeFailure(
        continuation: CheckedContinuation<Void, Error>,
        error: some Error
    ) {
        lock.lock()
        defer { lock.unlock() }

        guard !didResume else { return }
        didResume = true
        continuation.resume(throwing: error)
    }
}

func withTimeout<T: Sendable>(
    seconds: Double,
    detail: String,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask(operation: operation)
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw PTPIPError.timeout(detail)
        }

        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}
