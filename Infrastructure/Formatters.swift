import Foundation

enum Formatters {
    private static func makeByteFormatter() -> ByteCountFormatter {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter
    }

    private static func makeShortDateFormatter() -> DateFormatter {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }

    private static func makeLogDateFormatter() -> DateFormatter {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }

    static func fileSize(_ byteSize: Int64) -> String {
        makeByteFormatter().string(fromByteCount: byteSize)
    }

    static func shortDate(_ date: Date) -> String {
        makeShortDateFormatter().string(from: date)
    }

    static func logTime(_ date: Date) -> String {
        makeLogDateFormatter().string(from: date)
    }
}
