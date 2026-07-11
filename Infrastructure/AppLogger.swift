import OSLog

enum AppLogger {
    private static let subsystem = "com.yaoyihan.NikonConnectIOS"
    static let app = Logger(subsystem: subsystem, category: "app")
    static let transport = Logger(subsystem: subsystem, category: "transport")
    static let downloads = Logger(subsystem: subsystem, category: "downloads")
}
