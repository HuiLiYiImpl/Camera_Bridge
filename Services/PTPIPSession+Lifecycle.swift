import Foundation

extension PTPIPSession {
    func closeSessionTransport() async {
        stopBackgroundTasks()
        if isOpen {
            do {
                _ = try await requestResponseOnly(
                    operation: .closeSession,
                    transactionID: nextTransactionID,
                    parameters: []
                )
            } catch {
                AppLogger.transport.warning("Failed to close PTP session cleanly: \(error.localizedDescription, privacy: .public)")
            }
        }

        await commandConnection.close()
        await eventConnection.close()
        resetSessionState()
    }

    func resetSessionState() {
        assetTraversalState = nil
        thumbnailOperationSupport = .unknown
        deviceInfo = nil
        downloadStrategy = .fullObjectOnly
        latestSentProbeSequence = 0
        latestAcknowledgedProbeSequence = 0
        connectionNumber = nil
        responderFriendlyName = "Nikon 相机"
        nextTransactionID = 1
        isOpen = false
    }

    func openSessionSequence(reason: String) async throws -> PTPIPDeviceInfo {
        stopBackgroundTasks()
        await commandConnection.close()
        await eventConnection.close()
        resetSessionState()

        do {
            try await openConnectionWithRetry(commandConnection, label: "命令通道")
            recordDiagnostic("命令通道已连接。")
            try await commandConnection.send(
                PTPIPBinary.encodeInitCommandRequest(
                    guid: initiatorGUID,
                    friendlyName: PTPIPBinary.defaultFriendlyName
                )
            )

            let commandAck = try await commandConnection.receivePacket()
            switch commandAck.type {
            case .initCommandAck:
                let ack = try parseInitCommandAck(commandAck.payload)
                connectionNumber = ack.connectionNumber
                responderFriendlyName = ack.responderFriendlyName.isEmpty ? responderFriendlyName : ack.responderFriendlyName
                recordDiagnostic(
                    "InitCommandAck: connection=\(ack.connectionNumber) name=\(responderFriendlyName)"
                )
                guard ack.protocolVersion >> 16 == 0x0001 else {
                    throw PTPIPError.invalidProtocolVersion(ack.protocolVersion)
                }
            case .initFail:
                throw PTPIPError.malformedPayload("相机拒绝了 PTP/IP 连接请求。")
            default:
                throw PTPIPError.unexpectedPacket(expected: [.initCommandAck, .initFail], actual: commandAck.type)
            }

            guard let connectionNumber else {
                throw PTPIPError.sessionUnavailable
            }

            try await openConnectionWithRetry(eventConnection, label: "事件通道")
            recordDiagnostic("事件通道已连接。")
            try await eventConnection.send(PTPIPBinary.encodeInitEventRequest(connectionNumber: connectionNumber))

            let eventAck = try await eventConnection.receivePacket()
            switch eventAck.type {
            case .initEventAck:
                recordDiagnostic("InitEventAck: PTP/IP 双通道握手完成。")
            case .initFail:
                throw PTPIPError.malformedPayload("相机拒绝了 Event 通道连接。")
            default:
                throw PTPIPError.unexpectedPacket(expected: [.initEventAck, .initFail], actual: eventAck.type)
            }

            let rawDeviceInfo = try await requestDataInDirect(
                operation: .getDeviceInfo,
                transactionID: 0,
                parameters: []
            )
            let deviceInfo = try parseDeviceInfo(rawDeviceInfo)
            self.deviceInfo = deviceInfo
            configureDownloadStrategy(using: deviceInfo)

            let deviceName = [deviceInfo.manufacturer, deviceInfo.model]
                .compactMap { $0 }
                .joined(separator: " ")
            if !deviceName.isEmpty {
                recordDiagnostic("设备信息: \(deviceName)")
            }

            _ = try await requestResponseOnlyDirect(
                operation: .openSession,
                transactionID: 0,
                parameters: [1]
            )
            recordDiagnostic("OpenSession 成功（\(reason)）。")

            nextTransactionID = 1
            isOpen = true
            startBackgroundTasksIfNeeded()
            return deviceInfo
        } catch {
            await commandConnection.close()
            await eventConnection.close()
            resetSessionState()
            throw error
        }
    }

    func openConnectionWithRetry(_ connection: PTPIPTCPConnection, label: String) async throws {
        var lastError: Error?
        for attempt in 1 ... Self.connectionRetryCount {
            do {
                try await connection.open()
                if attempt > 1 {
                    recordDiagnostic("\(label) 在第 \(attempt) 次尝试后连接成功。")
                }
                return
            } catch {
                lastError = error
                if attempt == Self.connectionRetryCount {
                    break
                }
                recordDiagnostic("\(label) 连接失败，准备重试 \(attempt)/\(Self.connectionRetryCount - 1)：\(error.localizedDescription)")
                try? await Task.sleep(nanoseconds: Self.connectionRetryDelayNanoseconds)
            }
        }

        throw lastError ?? PTPIPError.connectionClosed
    }

    func requestResponseOnly(
        operation: PTPOperationCode,
        transactionID: UInt32,
        parameters: [UInt32]
    ) async throws -> PTPIPResponse {
        try await withExclusiveCommandChannelAccess {
            try await requestResponseOnlyDirect(
                operation: operation,
                transactionID: transactionID,
                parameters: parameters
            )
        }
    }

    func requestDataIn(
        operation: PTPOperationCode,
        transactionID: UInt32,
        parameters: [UInt32]
    ) async throws -> Data {
        try await withExclusiveCommandChannelAccess {
            try await requestDataInDirect(
                operation: operation,
                transactionID: transactionID,
                parameters: parameters
            )
        }
    }

    func requestDataInToTemporaryFile(
        operation: PTPOperationCode,
        transactionID: UInt32,
        parameters: [UInt32],
        suggestedFileName: String,
        expectedTotalBytes: Int64? = nil,
        onProgress: (@Sendable (DownloadTransferProgress) async -> Void)? = nil
    ) async throws -> URL {
        try await withExclusiveCommandChannelAccess {
            let temporaryURL = Self.makeTemporaryDownloadURL(suggestedFileName: suggestedFileName)
            guard let outputStream = OutputStream(url: temporaryURL, append: false) else {
                throw CameraAppError.fileSystemFailure("无法创建临时下载文件。")
            }

            outputStream.open()
            defer { outputStream.close() }

            do {
                _ = try await requestDataInStreamingDirect(
                    operation: operation,
                    transactionID: transactionID,
                    parameters: parameters,
                    onChunk: { chunk in
                        try Self.write(chunk, to: outputStream)
                    },
                    onProgress: { bytesTransferred, totalBytes, chunkSize in
                        if let onProgress {
                            await onProgress(
                                Self.makeStreamingDownloadProgress(
                                    bytesTransferred: bytesTransferred,
                                    reportedTotalBytes: totalBytes,
                                    expectedTotalBytes: expectedTotalBytes,
                                    chunkSize: chunkSize
                                )
                            )
                        }
                    }
                )
                return temporaryURL
            } catch {
                try? FileManager.default.removeItem(at: temporaryURL)
                throw error
            }
        }
    }

    func requestResponseOnlyDirect(
        operation: PTPOperationCode,
        transactionID: UInt32,
        parameters: [UInt32]
    ) async throws -> PTPIPResponse {
        try await commandConnection.send(
            PTPIPBinary.encodeOperationRequest(
                operation: operation,
                transactionID: transactionID,
                parameters: parameters,
                dataPhase: .noDataOrDataIn
            )
        )

        let packet = try await commandConnection.receivePacket()
        let response = try PTPIPBinary.parseResponsePacket(packet)
        try Self.validateResponse(response, expectedTransactionID: transactionID)
        return response
    }

    func requestDataInDirect(
        operation: PTPOperationCode,
        transactionID: UInt32,
        parameters: [UInt32]
    ) async throws -> Data {
        var collected = Data()
        _ = try await requestDataInStreamingDirect(
            operation: operation,
            transactionID: transactionID,
            parameters: parameters
        ) { chunk in
            collected.append(chunk)
        }
        return collected
    }

    func requestDataInStreamingDirect(
        operation: PTPOperationCode,
        transactionID: UInt32,
        parameters: [UInt32],
        onChunk: (Data) throws -> Void,
        onProgress: (@Sendable (_ bytesTransferred: UInt64, _ totalBytes: UInt64?, _ chunkSize: Int) async -> Void)? = nil
    ) async throws -> UInt64 {
        try await commandConnection.send(
            PTPIPBinary.encodeOperationRequest(
                operation: operation,
                transactionID: transactionID,
                parameters: parameters,
                dataPhase: .noDataOrDataIn
            )
        )

        let firstPacket = try await commandConnection.receivePacket()
        switch firstPacket.type {
        case .operationResponse:
            let response = try PTPIPBinary.parseResponsePacket(firstPacket)
            try Self.validateResponse(response, expectedTransactionID: transactionID)
            return 0

        case .startData:
            let start = try PTPIPBinary.parseStartDataPayload(firstPacket.payload)
            guard start.transactionID == transactionID else {
                throw PTPIPError.invalidTransaction(expected: transactionID, actual: start.transactionID)
            }

            let reportedTotalBytes = start.totalLength == UInt64.max ? nil : start.totalLength
            var bytesTransferred: UInt64 = 0
            while true {
                let packet = try await commandConnection.receivePacket(timeoutSeconds: 30)
                try Task.checkCancellation()
                switch packet.type {
                case .data:
                    let part = try PTPIPBinary.parseDataPayload(packet.payload)
                    guard part.transactionID == transactionID else {
                        throw PTPIPError.invalidTransaction(expected: transactionID, actual: part.transactionID)
                    }
                    try Task.checkCancellation()
                    try onChunk(part.bytes)
                    bytesTransferred += UInt64(part.bytes.count)
                    if let onProgress {
                        await onProgress(bytesTransferred, reportedTotalBytes, part.bytes.count)
                    }

                case .endData:
                    let part = try PTPIPBinary.parseDataPayload(packet.payload)
                    guard part.transactionID == transactionID else {
                        throw PTPIPError.invalidTransaction(expected: transactionID, actual: part.transactionID)
                    }
                    try Task.checkCancellation()
                    try onChunk(part.bytes)
                    bytesTransferred += UInt64(part.bytes.count)
                    if let onProgress {
                        await onProgress(bytesTransferred, reportedTotalBytes, part.bytes.count)
                    }

                    let responsePacket = try await commandConnection.receivePacket(timeoutSeconds: 30)
                    let response = try PTPIPBinary.parseResponsePacket(responsePacket)
                    try Self.validateResponse(response, expectedTransactionID: transactionID)

                    if start.totalLength != UInt64.max, start.totalLength != bytesTransferred {
                        AppLogger.transport.warning(
                            "PTP object length mismatch. expected=\(start.totalLength) actual=\(bytesTransferred)"
                        )
                    }

                    return bytesTransferred

                case .operationResponse:
                    let response = try PTPIPBinary.parseResponsePacket(packet)
                    try Self.validateResponse(response, expectedTransactionID: transactionID)
                    return bytesTransferred

                default:
                    throw PTPIPError.unexpectedPacket(
                        expected: [.data, .endData, .operationResponse],
                        actual: packet.type
                    )
                }
            }

        default:
            throw PTPIPError.unexpectedPacket(expected: [.operationResponse, .startData], actual: firstPacket.type)
        }
    }

    static func validateResponse(_ response: PTPIPResponse, expectedTransactionID: UInt32) throws {
        guard response.transactionID == expectedTransactionID else {
            throw PTPIPError.invalidTransaction(expected: expectedTransactionID, actual: response.transactionID)
        }

        guard response.code == PTPResponseCode.ok.rawValue else {
            throw PTPIPError.unexpectedResponse(code: response.code)
        }
    }

    func parseInitCommandAck(_ payload: Data) throws -> (
        connectionNumber: UInt32,
        responderGUID: Data,
        responderFriendlyName: String,
        protocolVersion: UInt32
    ) {
        var reader = PTPDataReader(data: payload)
        let connectionNumber = try reader.readUInt32()
        let responderGUID = try reader.readData(count: 16)
        let responderFriendlyName = try reader.readUTF16NullTerminatedString()
        let protocolVersion = try reader.readUInt32()
        return (connectionNumber, responderGUID, responderFriendlyName, protocolVersion)
    }

    func parseDeviceInfo(_ data: Data) throws -> PTPIPDeviceInfo {
        var reader = PTPDataReader(data: data)
        _ = try reader.readUInt16()
        _ = try reader.readUInt32()
        _ = try reader.readUInt16()
        _ = try reader.readPTPString()
        _ = try reader.readUInt16()
        let operationsSupported = try reader.readPTPArrayUInt16()
        _ = try reader.readPTPArrayUInt16()
        _ = try reader.readPTPArrayUInt16()
        _ = try reader.readPTPArrayUInt16()
        _ = try reader.readPTPArrayUInt16()
        let manufacturer = try reader.readPTPString()
        let model = try reader.readPTPString()
        _ = try reader.readPTPString()
        _ = try reader.readPTPString()
        return PTPIPDeviceInfo(
            model: model.isEmpty ? nil : model,
            manufacturer: manufacturer.isEmpty ? nil : manufacturer,
            operationsSupported: Set(operationsSupported)
        )
    }

    func startBackgroundTasksIfNeeded() {
        stopBackgroundTasks()

        eventMonitorTask = Task { [weak self] in
            await self?.runEventMonitorLoop()
        }
    }

    func stopBackgroundTasks() {
        eventMonitorTask?.cancel()
        eventMonitorTask = nil
        probeTask?.cancel()
        probeTask = nil
    }

    func runEventMonitorLoop() async {
        while !Task.isCancelled {
            do {
                let packet = try await eventConnection.receivePacket(timeoutSeconds: 30)
                await handleEventPacket(packet)
            } catch let error as PTPIPError {
                if case .timeout = error {
                    continue
                }
                if Task.isCancelled {
                    return
                }
                recordDiagnostic("事件通道已停止：\(error.localizedDescription)")
                return
            } catch {
                if Task.isCancelled {
                    return
                }
                recordDiagnostic("事件通道异常：\(error.localizedDescription)")
                return
            }
        }
    }

    func handleEventPacket(_ packet: PTPIPRawPacket) async {
        switch packet.type {
        case .probeRequest:
            do {
                try await eventConnection.send(PTPIPBinary.encodeProbeResponse())
            } catch {
                recordDiagnostic("回复 ProbeResponse 失败：\(error.localizedDescription)")
            }

        case .probeResponse:
            latestAcknowledgedProbeSequence = latestSentProbeSequence

        case .event:
            break

        default:
            recordDiagnostic("事件通道收到未处理包：\(packet.type)")
        }
    }

    func backgroundTaskState() -> BackgroundTaskState {
        BackgroundTaskState(
            hasEventMonitorTask: eventMonitorTask != nil,
            hasProbeTask: probeTask != nil
        )
    }

    func withExclusiveCommandChannelAccess<T>(
        _ operation: () async throws -> T
    ) async throws -> T {
        await acquireCommandChannelAccess()
        defer { releaseCommandChannelAccess() }
        return try await operation()
    }

    func acquireCommandChannelAccess() async {
        guard isCommandChannelBusy else {
            isCommandChannelBusy = true
            return
        }

        await withCheckedContinuation { continuation in
            commandChannelWaiters.append(continuation)
        }
    }

    func releaseCommandChannelAccess() {
        guard !commandChannelWaiters.isEmpty else {
            isCommandChannelBusy = false
            return
        }

        let next = commandChannelWaiters.removeFirst()
        next.resume()
    }

    func consumeTransactionID() -> UInt32 {
        let current = nextTransactionID
        nextTransactionID += 1
        return current
    }

    func ensureOpen() throws {
        guard isOpen else { throw PTPIPError.sessionUnavailable }
    }

    func recordDiagnostic(_ message: String) {
        diagnostics.append(message)
        AppLogger.transport.info("\(message, privacy: .public)")
    }

    static func loadOrCreateInitiatorGUID() -> Data {
        let defaults = UserDefaults.standard
        let key = "ptpipInitiatorGUID"
        if let saved = defaults.data(forKey: key), saved.count == 16 {
            return saved
        }

        let uuid = UUID().uuid
        let data = withUnsafeBytes(of: uuid) { Data($0) }
        defaults.set(data, forKey: key)
        return data
    }
}
