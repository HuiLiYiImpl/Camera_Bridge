import SwiftUI

struct PhotoAssetRow: View {
    let asset: PhotoAsset
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.accentColor.opacity(0.12))
                    .frame(width: 44, height: 44)

                Image(systemName: asset.kind.systemImageName)
                    .font(.headline)
                    .foregroundStyle(Color.accentColor)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(asset.fileName)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)

                HStack(spacing: 10) {
                    Text(asset.kind.badgeTitle)
                    Text(Formatters.fileSize(asset.byteSize))
                    Text(Formatters.shortDate(asset.captureDate))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(selectionStyle)
        }
        .contentShape(Rectangle())
    }

    private var selectionStyle: AnyShapeStyle {
        if isSelected {
            return AnyShapeStyle(Color.accentColor)
        }

        return AnyShapeStyle(.tertiary)
    }
}
