import SwiftUI
import AVKit

struct PlayerArtworkView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @ObservedObject var favorites = FavoritesService.shared
    let screenWidth: CGFloat
    let screenHeight: CGFloat
    let ambientPrimary: Color
    @Binding var isQualitySheetPresented: Bool
    @Binding var isFullScreenVideo: Bool

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 8)

            if player.isVideoMode {
                // Video Mode with 16:9 Aspect Ratio and Full Screen Trigger
                if let av = player.avPlayer {
                    ZStack(alignment: .topTrailing) {
                        VideoPlayer(player: av)
                            .aspectRatio(16/9, contentMode: .fit)
                            .frame(maxWidth: screenWidth - 36)
                            .cornerRadius(18)
                            .overlay(
                                RoundedRectangle(cornerRadius: 18)
                                    .stroke(Color.white.opacity(0.15), lineWidth: 1)
                            )
                            .shadow(color: .purple.opacity(0.4), radius: 20, y: 6)

                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            isFullScreenVideo = true
                        } label: {
                            HStack(spacing: 5) {
                                Image(systemName: "arrow.up.left.and.arrow.down.right")
                                    .font(.system(size: 12, weight: .bold))
                                Text("Full Screen")
                                    .font(.system(size: 11, weight: .bold))
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(Color.black.opacity(0.75)))
                            .overlay(Capsule().stroke(Color.white.opacity(0.2), lineWidth: 0.5))
                        }
                        .padding(12)
                    }
                } else {
                    RoundedRectangle(cornerRadius: 18)
                        .fill(Color.white.opacity(0.1))
                        .aspectRatio(16/9, contentMode: .fit)
                        .frame(maxWidth: screenWidth - 36)
                        .shimmering()
                }
            } else {
                // Large Artwork Card matching Apple Music
                let artworkSize = min(screenWidth - 56, screenHeight * 0.38)

                if let track = player.currentTrack {
                    AsyncImage(url: track.artworkUrl) { phase in
                        switch phase {
                        case .empty:
                            RoundedRectangle(cornerRadius: 18)
                                .fill(Color.white.opacity(0.12))
                                .shimmering()
                        case .success(let image):
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                                .frame(width: artworkSize, height: artworkSize)
                                .clipped()
                        case .failure:
                            RoundedRectangle(cornerRadius: 18)
                                .fill(Color.white.opacity(0.12))
                                .overlay(
                                    Image(systemName: "music.note")
                                        .font(.system(size: 54))
                                        .foregroundColor(.white.opacity(0.35))
                                )
                        @unknown default:
                            EmptyView()
                        }
                    }
                    .frame(width: artworkSize, height: artworkSize)
                    .cornerRadius(18)
                    .overlay(
                        RoundedRectangle(cornerRadius: 18)
                            .stroke(Color.white.opacity(0.12), lineWidth: 0.8)
                    )
                    .shadow(color: ambientPrimary.opacity(player.isPlaying ? 0.45 : 0.0), radius: 30, y: 12)
                    .shadow(color: .black.opacity(player.isPlaying ? 0.65 : 0.35), radius: 22, y: 10)
                    .scaleEffect(player.isPlaying ? 1.0 : 0.94)
                    .animation(.spring(response: 0.5, dampingFraction: 0.72), value: player.isPlaying)
                }
            }

            Spacer(minLength: 16)

            // Track Info Row: Title, Artist, Quality & Actions
            if let track = player.currentTrack {
                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(track.title)
                            .font(.system(size: 22, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                            .lineLimit(1)

                        Text(track.artist)
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(.white.opacity(0.65))
                            .lineLimit(1)
                    }

                    Spacer()

                    // Star Favorite Button (Apple Music style)
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) {
                            favorites.toggleLike(track: track)
                        }
                    } label: {
                        let liked = favorites.isLiked(trackId: track.id)
                        Image(systemName: liked ? "star.fill" : "star")
                            .font(.system(size: 21, weight: .medium))
                            .foregroundColor(liked ? .yellow : .white.opacity(0.75))
                            .scaleEffect(liked ? 1.15 : 1.0)
                            .frame(width: 40, height: 40)
                    }
                    .buttonStyle(.plain)

                    // More Options Ellipsis
                    Menu {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            isQualitySheetPresented = true
                        } label: {
                            Label("Audio Quality (\(player.selectedQuality.badge))", systemImage: "waveform.badge.magnifyingglass")
                        }

                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            player.addToQueueNext(track: track)
                        } label: {
                            Label("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward")
                        }

                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            player.addToQueue(track: track)
                        } label: {
                            Label("Add to Queue", systemImage: "text.badge.plus")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white.opacity(0.85))
                            .frame(width: 40, height: 40)
                            .background(Circle().fill(Color.white.opacity(0.10)))
                    }
                }
                .padding(.horizontal, 28)
                .padding(.bottom, 12)
            }
        }
    }
}
