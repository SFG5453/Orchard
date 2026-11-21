import SwiftUI

public struct MiniPlayerView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @Binding var isPlayerPresented: Bool

    public init(isPlayerPresented: Binding<Bool>) {
        self._isPlayerPresented = isPlayerPresented
    }

    public var body: some View {
        if let track = player.currentTrack {
            HStack(spacing: 12) {
                // Artwork & Titles Tap Area -> Opens Full Player
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    withAnimation(.spring(response: 0.38, dampingFraction: 0.82)) {
                        isPlayerPresented = true
                    }
                } label: {
                    HStack(spacing: 12) {
                        // Artwork with smooth corner radius
                        AsyncImage(url: track.artworkUrl) { phase in
                            switch phase {
                            case .empty:
                                Rectangle()
                                    .fill(Color(white: 0.2))
                                    .shimmering()
                            case .success(let image):
                                image
                                    .resizable()
                                    .aspectRatio(contentMode: .fill)
                            case .failure:
                                Rectangle()
                                    .fill(Color(white: 0.2))
                                    .overlay(
                                        Image(systemName: "music.note")
                                            .foregroundColor(.white.opacity(0.6))
                                    )
                            @unknown default:
                                EmptyView()
                            }
                        }
                        .frame(width: 44, height: 44)
                        .cornerRadius(10)
                        .clipped()

                        // Title & Artist
                        VStack(alignment: .leading, spacing: 2) {
                            Text(track.title)
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundColor(.white)
                                .lineLimit(1)

                            Text(track.artist)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(.white.opacity(0.6))
                                .lineLimit(1)
                        }

                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                // Play/Pause Button
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    player.togglePlayPause()
                } label: {
                    Group {
                        if player.isLoading {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                .scaleEffect(0.85)
                        } else {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                .font(.system(size: 21, weight: .bold))
                                .foregroundColor(.white)
                        }
                    }
                    .frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)

                // Forward Button
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    player.next()
                } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(width: 38, height: 38)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color(white: 0.14).opacity(0.96))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(Color.white.opacity(0.12), lineWidth: 0.8)
                    )
                    .shadow(color: .black.opacity(0.45), radius: 14, y: 6)
            )
                .overlay(
                    // Slim bottom gradient progress line
                    VStack {
                        Spacer()
                        if player.duration > 0 {
                            GeometryReader { geo in
                                let progress = min(max(player.currentTime / player.duration, 0), 1)
                                Rectangle()
                                    .fill(
                                        LinearGradient(
                                            colors: [Color.purple, Color.pink],
                                            startPoint: .leading,
                                            endPoint: .trailing
                                        )
                                    )
                                    .frame(width: geo.size.width * progress, height: 2.5)
                            }
                            .frame(height: 2.5)
                            .padding(.horizontal, 10)
                        }
                    }
                )
            .padding(.horizontal, 14)
            .padding(.bottom, 6)
        }
    }
}
