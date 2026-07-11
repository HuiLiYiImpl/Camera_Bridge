import Foundation
import Photos

protocol PhotoLibraryExporting: Sendable {
    func exportFile(at url: URL) async throws
}

struct PhotoLibraryExportService: PhotoLibraryExporting {
    func exportFile(at url: URL) async throws {
        let status = await requestAuthorization()

        guard status == .authorized || status == .limited else {
            throw CameraAppError.photoLibraryAccessDenied
        }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            performPhotoLibraryWrite(for: url, continuation: continuation)
        }
    }

    private func requestAuthorization() async -> PHAuthorizationStatus {
        await withCheckedContinuation { continuation in
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                continuation.resume(returning: status)
            }
        }
    }

    private func performPhotoLibraryWrite(
        for url: URL,
        continuation: CheckedContinuation<Void, Error>
    ) {
        PHPhotoLibrary.shared().performChanges({
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.shouldMoveFile = false
            request.addResource(with: resourceType(for: url), fileURL: url, options: options)
        }) { success, error in
            if let error {
                continuation.resume(
                    throwing: CameraAppError.photoLibraryExportFailed(error.localizedDescription)
                )
            } else if success {
                continuation.resume(returning: ())
            } else {
                continuation.resume(
                    throwing: CameraAppError.photoLibraryExportFailed("系统未返回成功结果。")
                )
            }
        }
    }

    private func resourceType(for url: URL) -> PHAssetResourceType {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "mov", "mp4":
            return .video
        default:
            return .photo
        }
    }
}
