import SwiftUI

@MainActor
struct StatusBadgeView: View {
    let state: CameraWorkflowState
    @State private var isPulsing = false

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(foregroundStyle)
                .frame(width: 6, height: 6)
                .scaleEffect(isWorkingState && isPulsing ? 1.25 : 1)
                .opacity(isWorkingState && isPulsing ? 0.45 : 1)

            Label(state.title, systemImage: state.symbolName)
                .font(.system(.footnote, design: .rounded).weight(.semibold))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .foregroundStyle(foregroundStyle)
        .background(foregroundStyle.opacity(0.08), in: Capsule())
        .task(id: state) {
            await MainActor.run {
                isPulsing = false
                guard isWorkingState else { return }
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                    isPulsing = true
                }
            }
        }
    }

    private var isWorkingState: Bool {
        switch state {
        case .connecting, .loadingPhotos, .downloading:
            return true
        default:
            return false
        }
    }

    private var foregroundStyle: Color {
        AppTheme.workflowColor(for: state)
    }
}
