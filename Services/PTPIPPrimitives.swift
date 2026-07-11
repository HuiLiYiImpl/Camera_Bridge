import Foundation

enum PTPIPPacketType: UInt32, Sendable {
    case initCommandRequest = 0x00000001
    case initCommandAck = 0x00000002
    case initEventRequest = 0x00000003
    case initEventAck = 0x00000004
    case initFail = 0x00000005
    case operationRequest = 0x00000006
    case operationResponse = 0x00000007
    case event = 0x00000008
    case startData = 0x00000009
    case data = 0x0000000A
    case cancel = 0x0000000B
    case endData = 0x0000000C
    case probeRequest = 0x0000000D
    case probeResponse = 0x0000000E
}

enum PTPIPDataPhaseInfo: UInt32, Sendable {
    case noDataOrDataIn = 0x00000001
    case dataOut = 0x00000002
    case unknown = 0x00000003
}

enum PTPOperationCode: UInt16, Sendable {
    case getDeviceInfo = 0x1001
    case openSession = 0x1002
    case closeSession = 0x1003
    case getStorageIDs = 0x1004
    case getStorageInfo = 0x1005
    case getNumObjects = 0x1006
    case getObjectHandles = 0x1007
    case getObjectInfo = 0x1008
    case getObject = 0x1009
    case getThumb = 0x100A
    case getPartialObject = 0x101B
    case getObjectsMetaData = 0x9434
}

enum PTPResponseCode: UInt16, Sendable {
    case ok = 0x2001
    case generalError = 0x2002
    case sessionNotOpen = 0x2003
    case invalidTransactionID = 0x2004
    case operationNotSupported = 0x2005
    case parameterNotSupported = 0x2006
    case incompleteTransfer = 0x2007
    case invalidStorageID = 0x2008
    case invalidObjectHandle = 0x2009
    case devicePropNotSupported = 0x200A
    case invalidObjectFormatCode = 0x200B
    case storeFull = 0x200C
    case objectWriteProtected = 0x200D
    case storeReadOnly = 0x200E
    case accessDenied = 0x200F
    case noThumbnailPresent = 0x2010
    case selfTestFailed = 0x2011
    case partialDeletion = 0x2012
    case storeNotAvailable = 0x2013
    case specificationByFormatUnsupported = 0x2014
    case noValidObjectInfo = 0x2015
    case invalidCodeFormat = 0x2016
    case unknownVendorCode = 0x2017
    case captureAlreadyTerminated = 0x2018
    case deviceBusy = 0x2019
    case invalidParentObject = 0x201A
    case invalidDevicePropFormat = 0x201B
    case invalidDevicePropValue = 0x201C
    case invalidParameter = 0x201D
    case sessionAlreadyOpen = 0x201E
    case transactionCancelled = 0x201F
    case specificationOfDestinationUnsupported = 0x2020
}

struct PTPIPDeviceInfo: Sendable {
    let model: String?
    let manufacturer: String?
    let operationsSupported: Set<UInt16>

    func supportsOperation(_ operation: PTPOperationCode) -> Bool {
        operationsSupported.contains(operation.rawValue)
    }
}

struct PTPIPObjectInfo: Sendable {
    let handle: UInt32
    let storageID: UInt32
    let objectFormat: UInt16
    let compressedSize: UInt32
    let thumbnailInfo: PhotoAssetThumbnailInfo?
    let fileName: String
    let captureDate: Date?
    let modificationDate: Date?
}

struct PTPIPRawPacket: Sendable {
    let type: PTPIPPacketType
    let payload: Data
}

struct PTPIPResponse: Sendable {
    let code: UInt16
    let transactionID: UInt32
    let parameters: [UInt32]
}

enum PTPIPError: LocalizedError, Sendable {
    case invalidPacketLength
    case unsupportedPacketType(UInt32)
    case unexpectedPacket(expected: [PTPIPPacketType], actual: PTPIPPacketType)
    case connectionClosed
    case invalidProtocolVersion(UInt32)
    case invalidTransaction(expected: UInt32, actual: UInt32)
    case unexpectedResponse(code: UInt16)
    case malformedPayload(String)
    case sessionUnavailable
    case timeout(String)

    var errorDescription: String? {
        switch self {
        case .invalidPacketLength:
            return "PTP/IP 数据包长度无效。"
        case .unsupportedPacketType(let value):
            return "遇到未知的 PTP/IP 包类型：0x\(String(value, radix: 16))。"
        case .unexpectedPacket(let expected, let actual):
            let expectedText = expected.map { String(describing: $0) }.joined(separator: ", ")
            return "收到的 PTP/IP 包类型不符合预期。期望：\(expectedText)，实际：\(actual)。"
        case .connectionClosed:
            return "相机连接已关闭。"
        case .invalidProtocolVersion(let version):
            return "相机返回了不兼容的 PTP/IP 协议版本：0x\(String(version, radix: 16))。"
        case .invalidTransaction(let expected, let actual):
            return "PTP 事务号不匹配。期望 \(expected)，实际 \(actual)。"
        case .unexpectedResponse(let code):
            return "相机返回了错误响应：0x\(String(code, radix: 16))。"
        case .malformedPayload(let detail):
            return "相机返回的数据格式无法解析：\(detail)"
        case .sessionUnavailable:
            return "PTP/IP 会话尚未建立。"
        case .timeout(let detail):
            return "等待相机响应超时：\(detail)"
        }
    }
}

enum PTPIPBinary {
    static let protocolVersion: UInt32 = 0x0001_0000
    static let defaultFriendlyName = "NikonConnectIOS"
}

struct PTPDataReader {
    private let data: Data
    private(set) var offset: Int = 0

    init(data: Data) {
        self.data = data
    }

    var remainingCount: Int {
        data.count - offset
    }

    mutating func readUInt8() throws -> UInt8 {
        guard remainingCount >= 1 else {
            throw PTPIPError.malformedPayload("缺少 UInt8 字段。")
        }

        let value = data[offset]
        offset += 1
        return value
    }

    mutating func readUInt16() throws -> UInt16 {
        guard remainingCount >= 2 else {
            throw PTPIPError.malformedPayload("缺少 UInt16 字段。")
        }

        let value = data.withUnsafeBytes {
            $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self)
        }.littleEndian
        offset += 2
        return value
    }

    mutating func readUInt32() throws -> UInt32 {
        guard remainingCount >= 4 else {
            throw PTPIPError.malformedPayload("缺少 UInt32 字段。")
        }

        let value = data.withUnsafeBytes {
            $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)
        }.littleEndian
        offset += 4
        return value
    }

    mutating func readUInt64() throws -> UInt64 {
        guard remainingCount >= 8 else {
            throw PTPIPError.malformedPayload("缺少 UInt64 字段。")
        }

        let value = data.withUnsafeBytes {
            $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self)
        }.littleEndian
        offset += 8
        return value
    }

    mutating func readData(count: Int) throws -> Data {
        guard remainingCount >= count else {
            throw PTPIPError.malformedPayload("缺少 \(count) 字节数据。")
        }

        let slice = data[offset ..< offset + count]
        offset += count
        return Data(slice)
    }

    mutating func readPTPArrayUInt16() throws -> [UInt16] {
        let count = try Int(readUInt32())
        return try (0 ..< count).map { _ in try readUInt16() }
    }

    mutating func readPTPArrayUInt32() throws -> [UInt32] {
        let count = try Int(readUInt32())
        return try (0 ..< count).map { _ in try readUInt32() }
    }

    mutating func readPTPString() throws -> String {
        let characterCount = Int(try readUInt8())
        guard characterCount > 0 else { return "" }

        let raw = try readData(count: characterCount * 2)
        return PTPIPBinary.decodeUTF16NullTerminated(raw)
    }

    mutating func readUTF16NullTerminatedString() throws -> String {
        let startOffset = offset
        while remainingCount >= 2 {
            let codeUnit = try readUInt16()
            if codeUnit == 0 {
                let length = offset - startOffset
                let raw = data[startOffset ..< startOffset + length]
                return PTPIPBinary.decodeUTF16NullTerminated(Data(raw))
            }
        }

        throw PTPIPError.malformedPayload("UTF-16 字符串缺少终止符。")
    }
}

extension Data {
    mutating func appendUInt16LE(_ value: UInt16) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { append(contentsOf: $0) }
    }

    mutating func appendUInt32LE(_ value: UInt32) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { append(contentsOf: $0) }
    }

    mutating func appendUInt64LE(_ value: UInt64) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { append(contentsOf: $0) }
    }
}

extension PTPIPBinary {
    static func encodePacket(type: PTPIPPacketType, payload: Data) -> Data {
        var packet = Data()
        packet.appendUInt32LE(UInt32(payload.count + 8))
        packet.appendUInt32LE(type.rawValue)
        packet.append(payload)
        return packet
    }

    static func encodeInitCommandRequest(guid: Data, friendlyName: String) -> Data {
        var payload = Data()
        payload.append(guid)
        payload.append(encodeUTF16NullTerminatedString(friendlyName))
        payload.appendUInt32LE(protocolVersion)
        return encodePacket(type: .initCommandRequest, payload: payload)
    }

    static func encodeInitEventRequest(connectionNumber: UInt32) -> Data {
        var payload = Data()
        payload.appendUInt32LE(connectionNumber)
        return encodePacket(type: .initEventRequest, payload: payload)
    }

    static func encodeProbeRequest() -> Data {
        encodePacket(type: .probeRequest, payload: Data())
    }

    static func encodeProbeResponse() -> Data {
        encodePacket(type: .probeResponse, payload: Data())
    }

    static func encodeOperationRequest(
        operation: PTPOperationCode,
        transactionID: UInt32,
        parameters: [UInt32],
        dataPhase: PTPIPDataPhaseInfo
    ) -> Data {
        var payload = Data()
        payload.appendUInt32LE(dataPhase.rawValue)
        payload.appendUInt16LE(operation.rawValue)
        payload.appendUInt32LE(transactionID)
        for parameter in parameters.prefix(5) {
            payload.appendUInt32LE(parameter)
        }
        return encodePacket(type: .operationRequest, payload: payload)
    }

    static func decodePacketType(_ rawValue: UInt32) throws -> PTPIPPacketType {
        guard let type = PTPIPPacketType(rawValue: rawValue) else {
            throw PTPIPError.unsupportedPacketType(rawValue)
        }
        return type
    }

    static func encodeUTF16NullTerminatedString(_ string: String) -> Data {
        let trimmed = String(string.prefix(39))
        let codeUnits = Array(trimmed.utf16) + [0]
        var data = Data(capacity: codeUnits.count * 2)
        for codeUnit in codeUnits {
            data.appendUInt16LE(codeUnit)
        }
        return data
    }

    static func decodeUTF16NullTerminated(_ data: Data) -> String {
        var codeUnits: [UInt16] = []
        codeUnits.reserveCapacity(data.count / 2)

        var offset = 0
        while offset + 1 < data.count {
            let value = data.withUnsafeBytes {
                $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self)
            }.littleEndian
            if value == 0 { break }
            codeUnits.append(value)
            offset += 2
        }

        return String(decoding: codeUnits, as: UTF16.self)
    }

    static func parseResponsePacket(_ packet: PTPIPRawPacket) throws -> PTPIPResponse {
        guard packet.type == .operationResponse else {
            throw PTPIPError.unexpectedPacket(expected: [.operationResponse], actual: packet.type)
        }

        var reader = PTPDataReader(data: packet.payload)
        let responseCode = try reader.readUInt16()
        let transactionID = try reader.readUInt32()
        var parameters: [UInt32] = []
        while reader.remainingCount >= 4 {
            parameters.append(try reader.readUInt32())
        }

        return PTPIPResponse(code: responseCode, transactionID: transactionID, parameters: parameters)
    }

    static func parseStartDataPayload(_ payload: Data) throws -> (transactionID: UInt32, totalLength: UInt64) {
        var reader = PTPDataReader(data: payload)
        let transactionID = try reader.readUInt32()
        let totalLength = try reader.readUInt64()
        return (transactionID, totalLength)
    }

    static func parseDataPayload(_ payload: Data) throws -> (transactionID: UInt32, bytes: Data) {
        var reader = PTPDataReader(data: payload)
        let transactionID = try reader.readUInt32()
        let bytes = try reader.readData(count: reader.remainingCount)
        return (transactionID, bytes)
    }
}
