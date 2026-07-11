import Foundation

struct DownloadRecord: Identifiable, Codable, Equatable, Sendable {
    let id: UUID
    let sourceAssetIdentifier: String
    let fileName: String
    let savedURL: URL
    let byteSize: Int64
    let completedAt: Date
    var exportedToPhotoLibrary: Bool

    init(
        id: UUID = UUID(),
        sourceAssetIdentifier: String,
        fileName: String,
        savedURL: URL,
        byteSize: Int64,
        completedAt: Date = Date(),
        exportedToPhotoLibrary: Bool
    ) {
        self.id = id
        self.sourceAssetIdentifier = sourceAssetIdentifier
        self.fileName = fileName
        self.savedURL = savedURL
        self.byteSize = byteSize
        self.completedAt = completedAt
        self.exportedToPhotoLibrary = exportedToPhotoLibrary
    }
}
