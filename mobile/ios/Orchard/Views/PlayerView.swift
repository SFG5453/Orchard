import SwiftUI
import AVKit
import MediaPlayer

public enum PlayerCenterView {
    case artwork
    case lyrics
    case queue
}

public struct PlayerView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @ObservedObject var favorites = FavoritesService.shared
    @Environment(\.dismiss) private var dismiss
    @Binding var isPresented: Bool

    @State private var centerView: PlayerCenterView = .artwork
    @State private var isQualitySheetPresented: Bool = false
    @State private var isFullScreenVideo: Bool = false
    @State private var isShuffleEnabled: Bool = false
    @State private var isRepeatEnabled: Bool = false
    @State private var isAutoplayEnabled: Bool = true
    @State private var isScrubbing: Bool = false
    @State private var scrubTime: Double = 0

    @State private var ambientPrimary: Color = Color(hex: "2A1B14")
    @State private var ambientSecondary: Color = Color(hex: "18100C")
    @State private var ambientDark: Color = Color(hex: "0D0806")

    public init(isPresented: Binding<Bool> = .constant(true)) {
        self._isPresented = isPresented
    }

    private func closePlayer() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        isPresented = false
        dismiss()
    }

    public var body: some View {
        GeometryReader { geometry in
            let safeTop = max(geometry.safeAreaInsets.top, 20)
            let safeBottom = max(geometry.safeAreaInsets.bottom, 20)
            let screenWidth = geometry.size.width
            let screenHeight = geometry.size.height

            ZStack {
                // Base solid ambient tone
                ambientDark
                    .ignoresSafeArea()

                // Dynamic Ambient Gradient adapted from album cover
                LinearGradient(
                    colors: [
                        ambientPrimary.opacity(0.85),
                        ambientSecondary.opacity(0.70),
                        ambientDark
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()

                // High-saturation blurred artwork for atmospheric depth
                if let track = player.currentTrack {
                    AsyncImage(url: track.artworkUrl) { image in
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: screenWidth, height: screenHeight)
                            .blur(radius: 65)
                            .saturation(1.4)
                            .opacity(0.65)
                            .overlay(
                                LinearGradient(
                                    colors: [
                                        ambientPrimary.opacity(0.25),
                                        ambientSecondary.opacity(0.45),
                                        ambientDark.opacity(0.92)
                                    ],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )
                    } placeholder: {
                        EmptyView()
                    }
                    .clipped()
                    .ignoresSafeArea()
                }

                // Foreground Content
                VStack(spacing: 0) {
                    // Top Bar with Close Chevron, Center Grabber, and Trailing Video Toggle
                    HStack(alignment: .center) {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            closePlayer()
                        } label: {
                            Image(systemName: "chevron.down")
                                .font(.system(size: 19, weight: .bold))
                                .foregroundColor(.white.opacity(0.85))
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        Spacer()

                        // Apple Music style center pull-down grabber
                        Capsule()
                            .fill(Color.white.opacity(0.35))
                            .frame(width: 38, height: 5)
                            .frame(width: 100, height: 44)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                closePlayer()
                            }

                        Spacer()

                        // Sleek Video Mode toggle button on trailing edge
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            player.toggleVideoMode()
                        } label: {
                            Image(systemName: player.isVideoMode ? "music.note" : "video.fill")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(player.isVideoMode ? .purple : .white.opacity(0.85))
                                .frame(width: 34, height: 34)
                                .background(
                                    Circle().fill(player.isVideoMode ? Color.white : Color.white.opacity(0.12))
                                )
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, safeTop - 10)
                    .padding(.bottom, 4)
                    .contentShape(Rectangle())

                    // Middle Dynamic View: Artwork vs Lyrics vs Queue
                    Group {
                        switch centerView {
                        case .artwork:
                            PlayerArtworkView(
                                player: player,
                                favorites: favorites,
                                screenWidth: screenWidth,
                                screenHeight: screenHeight,
                                ambientPrimary: ambientPrimary,
                                isQualitySheetPresented: $isQualitySheetPresented,
                                isFullScreenVideo: $isFullScreenVideo
                            )
                        case .lyrics:
                            VStack(spacing: 0) {
                                PlayerMiniTrackHeader(
                                    player: player,
                                    favorites: favorites,
                                    isQualitySheetPresented: $isQualitySheetPresented
                                )
                                LyricsView()
                            }
                        case .queue:
                            PlayerQueueView(
                                player: player,
                                favorites: favorites,
                                isQualitySheetPresented: $isQualitySheetPresented,
                                isShuffleEnabled: $isShuffleEnabled,
                                isRepeatEnabled: $isRepeatEnabled,
                                isAutoplayEnabled: $isAutoplayEnabled
                            )
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                    // Docked Bottom Player Section (Scrubber, Controls, Volume, Action Bar)
                    dockedBottomControls(safeBottom: safeBottom)
                }
                .frame(width: screenWidth, height: screenHeight)
            }
        }
        .sheet(isPresented: $isQualitySheetPresented) {
            AudioQualitySheet()
        }
        .fullScreenCover(isPresented: $isFullScreenVideo) {
            FullScreenVideoPlayerView(isPresented: $isFullScreenVideo)
        }
        .task(id: player.currentTrack?.id) {
            if let track = player.currentTrack {
                let colors = await ArtworkColorExtractor.shared.extractColors(from: track.artworkUrl)
                withAnimation(.easeInOut(duration: 0.6)) {
                    self.ambientPrimary = colors.primary
                    self.ambientSecondary = colors.secondary
                    self.ambientDark = colors.darkAccent
                }
            }
        }
    }

    // MARK: - Docked Bottom Controls (Scrubber, Transport, Volume, Action Bar)

    @ViewBuilder
    private func dockedBottomControls(safeBottom: CGFloat) -> some View {
        let activeTime = isScrubbing ? scrubTime : player.currentTime

        VStack(spacing: 12) {
            // Sleek Progress Scrubber
            VStack(spacing: 6) {
                SleekProgressBar(
                    currentTime: player.currentTime,
                    duration: max(player.duration, 1),
                    isScrubbing: $isScrubbing,
                    scrubTime: $scrubTime
                ) { newTime in
                    player.seek(to: newTime)
                }

                HStack {
                    Text(formatTime(activeTime))
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundColor(.white.opacity(0.55))

                    Spacer()

                    if !player.isVideoMode {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            isQualitySheetPresented = true
                        } label: {
                            Text(player.selectedQuality.badge)
                                .font(.system(size: 10, weight: .bold, design: .rounded))
                                .tracking(0.5)
                                .foregroundColor(.white.opacity(0.70))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(
                                    Capsule()
                                        .fill(Color.white.opacity(0.12))
                                )
                        }
                        .buttonStyle(.plain)

                        Spacer()
                    }

                    Text("-" + formatTime(max(player.duration - activeTime, 0)))
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundColor(.white.opacity(0.55))
                }
            }
            .padding(.horizontal, 28)

            // Transport Controls: [ ⏪ ]   [ ⏸ / ▶ ]   [ ⏩ ]
            HStack(spacing: 58) {
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    player.previous()
                } label: {
                    Image(systemName: "backward.fill")
                        .font(.system(size: 32))
                        .foregroundColor(.white)
                }

                Button {
                    UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                    player.togglePlayPause()
                } label: {
                    if player.isLoading {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            .scaleEffect(1.2)
                            .frame(width: 48, height: 48)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 46))
                            .foregroundColor(.white)
                            .offset(x: player.isPlaying ? 0 : 3)
                    }
                }

                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    player.next()
                } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 32))
                        .foregroundColor(.white)
                }
            }
            .padding(.vertical, 6)

            // Volume Slider Row
            HStack(spacing: 12) {
                Image(systemName: "speaker.fill")
                    .font(.system(size: 11))
                    .foregroundColor(.white.opacity(0.5))

                SystemVolumeSlider()
                    .frame(height: 24)

                Image(systemName: "speaker.wave.3.fill")
                    .font(.system(size: 11))
                    .foregroundColor(.white.opacity(0.5))
            }
            .padding(.horizontal, 32)
            .padding(.vertical, 2)

            // Bottom Action Bar: [ 💬 Lyrics ]   [ 🛜 AirPlay ]   [ ☰ Queue ]
            HStack {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                        centerView = (centerView == .lyrics) ? .artwork : .lyrics
                    }
                } label: {
                    Image(systemName: centerView == .lyrics ? "quote.bubble.fill" : "quote.bubble")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundColor(centerView == .lyrics ? .white : .white.opacity(0.60))
                        .frame(width: 44, height: 44)
                        .background(
                            Circle()
                                .fill(centerView == .lyrics ? Color.white.opacity(0.24) : Color.clear)
                        )
                }

                Spacer()

                ZStack {
                    Image(systemName: "airplayaudio")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundColor(.white.opacity(0.60))
                        .frame(width: 44, height: 44)

                    AirPlayRoutePicker()
                        .frame(width: 44, height: 44)
                        .opacity(0.015)
                }

                Spacer()

                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                        centerView = (centerView == .queue) ? .artwork : .queue
                    }
                } label: {
                    Image(systemName: "list.bullet")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundColor(centerView == .queue ? .white : .white.opacity(0.60))
                        .frame(width: 44, height: 44)
                        .background(
                            Circle()
                                .fill(centerView == .queue ? Color.white.opacity(0.24) : Color.clear)
                        )
                }
            }
            .padding(.horizontal, 36)
            .padding(.bottom, max(safeBottom + 4, 20))
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite && seconds >= 0 else { return "0:00" }
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }
}
