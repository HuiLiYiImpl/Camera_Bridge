import SwiftUI

struct DownloadRecordRow: View {
    let record: DownloadRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: fileIcon)
                .font(.system(size: 18))
                .foregroundStyle(fileColor)
                .frame(width: 36, height: 36)
                .background(fileColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(record.fileName)
                        .font(.system(.subheadline, design: .rounded).weight(.semibold))
                        .lineLimit(1)
                    
                    Spacer()
                    
                    if record.exportedToPhotoLibrary {
                        HStack(spacing: 2) {
                            Image(systemName: "checkmark.circle.fill")
                            Text("已入相册")
                        }
                        .font(.system(size: 9, design: .rounded).weight(.bold))
                        .foregroundStyle(AppTheme.success)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(AppTheme.success.opacity(0.10))
                        .clipShape(Capsule())
                    } else {
                        HStack(spacing: 2) {
                            Image(systemName: "internaldrive")
                            Text("仅沙盒")
                        }
                        .font(.system(size: 9, design: .rounded).weight(.bold))
                        .foregroundStyle(AppTheme.inkMuted)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(AppTheme.surfaceMuted)
                        .clipShape(Capsule())
                    }
                }
                
                HStack(spacing: 12) {
                    Text(Formatters.fileSize(record.byteSize))
                        .font(.system(.caption, design: .monospaced))
                    
                    Text(Formatters.shortDate(record.completedAt))
                        .font(.system(.caption, design: .rounded))
                }
                .foregroundStyle(AppTheme.inkMuted)
            }
        }
        .padding(.vertical, 4)
    }

    private var fileIcon: String {
        let ext = record.savedURL.pathExtension.lowercased()
        if ext == "mov" || ext == "mp4" {
            return "film"
        } else if ext == "nef" || ext == "nrw" || ext == "raw" {
            return "camera.aperture"
        } else {
            return "photo"
        }
    }

    private var fileColor: Color {
        let ext = record.savedURL.pathExtension.lowercased()
        if ext == "mov" || ext == "mp4" {
            return AppTheme.danger
        } else if ext == "nef" || ext == "nrw" || ext == "raw" {
            return AppTheme.accentStrong
        } else {
            return AppTheme.info
        }
    }
}
