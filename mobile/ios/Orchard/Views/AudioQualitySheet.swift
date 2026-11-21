import SwiftUI

public struct AudioQualitySheet: View {
    @ObservedObject var player = AudioPlayerService.shared
    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        ZStack {
            Color(white: 0.10).ignoresSafeArea()

            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Audio Quality")
                            .font(.system(size: 22, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                        Text("Stream resolution & bitrate")
                            .font(.system(size: 13))
                            .foregroundColor(.white.opacity(0.55))
                    }

                    Spacer()

                    Button {
                        dismiss()
                    } label: {
                        Circle()
                            .fill(Color(white: 0.18))
                            .frame(width: 32, height: 32)
                            .overlay(
                                Image(systemName: "xmark")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white.opacity(0.8))
                            )
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 24)

                // Quality Options List
                VStack(spacing: 10) {
                    ForEach(AudioQuality.allCases) { quality in
                        let isSelected = player.selectedQuality == quality
                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            player.switchQuality(to: quality)
                            dismiss()
                        } label: {
                            HStack(spacing: 14) {
                                ZStack {
                                    Circle()
                                        .stroke(isSelected ? Color.purple : Color.white.opacity(0.2), lineWidth: 2)
                                        .frame(width: 22, height: 22)

                                    if isSelected {
                                        Circle()
                                            .fill(Color.purple)
                                            .frame(width: 12, height: 12)
                                    }
                                }

                                VStack(alignment: .leading, spacing: 3) {
                                    HStack(spacing: 8) {
                                        Text(quality.rawValue)
                                            .font(.system(size: 16, weight: isSelected ? .bold : .semibold))
                                            .foregroundColor(.white)

                                        Text(quality.badge)
                                            .font(.system(size: 10, weight: .heavy))
                                            .tracking(0.8)
                                            .foregroundColor(isSelected ? .white : .purple)
                                            .padding(.horizontal, 7)
                                            .padding(.vertical, 3)
                                            .background(
                                                Capsule()
                                                    .fill(isSelected ? Color.purple : Color.purple.opacity(0.18))
                                            )
                                    }

                                    Text(quality.description)
                                        .font(.system(size: 12))
                                        .foregroundColor(.white.opacity(0.5))
                                }

                                Spacer()

                                if isSelected && player.isLoading {
                                    ProgressView()
                                        .progressViewStyle(CircularProgressViewStyle(tint: .purple))
                                        .scaleEffect(0.8)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                            .background(
                                RoundedRectangle(cornerRadius: 14)
                                    .fill(isSelected ? Color.purple.opacity(0.12) : Color(white: 0.15))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 14)
                                            .stroke(isSelected ? Color.purple.opacity(0.4) : Color.white.opacity(0.06), lineWidth: 1)
                                    )
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 24)

                Spacer()
            }
        }
        .presentationDetents([.height(380)])
        .presentationDragIndicator(.visible)
    }
}
