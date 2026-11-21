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
    @State private var dragOffset: CGFloat = 0
    @State private var isScrubbing: Bool = false
    @State private var scrubTime: Double = 0

    @State private var ambientPrimary: Color = Color(hex: "2A1B14")
    @State private var ambientSecondary: Color = Color(hex: "18100C")
    @State private var ambientDark: Color = Color(hex: "0D0806")

    public init(isPresented: Binding<Bool> = .constant(true)) {
        self._isPresented = isPresented
    }

    private func closePlayer() {
        withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) {
            dragOffset = 0
            isPresented = false
            dismiss()
        }
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
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 15)
                            .onChanged { value in
                                if value.translation.height > 0 {
                                    dragOffset = value.translation.height
                                }
                            }
                            .onEnded { value in
                                if value.translation.height > 70 || value.predictedEndTranslation.height > 150 {
                                    closePlayer()
                                } else {
                                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                                        dragOffset = 0
                                    }
                                }
                            }
                    )

                    // Middle Dynamic View: Artwork vs Lyrics vs Queue
                    Group {
                        switch centerView {
                        case .artwork:
                            artworkAndVideoSection(screenWidth: screenWidth, screenHeight: screenHeight)
                        case .lyrics:
                            lyricsSection(screenWidth: screenWidth)
                        case .queue:
                            queueSection(screenWidth: screenWidth)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                    // Docked Bottom Player Section (Scrubber, Controls, Volume, Action Bar)
                    dockedBottomControls(safeBottom: safeBottom)
                }
                .frame(width: screenWidth, height: screenHeight)
                .offset(y: max(dragOffset, 0))
                .gesture(
                    DragGesture()
                        .onChanged { value in
                            if value.translation.height > 0 {
                                dragOffset = value.translation.height
                            }
                        }
                        .onEnded { value in
                            if value.translation.height > 100 || value.predictedEndTranslation.height > 250 {
                                closePlayer()
                            } else {
                                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                                    dragOffset = 0
                                }
                            }
                        }
                )
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

    // MARK: - Center Views

    @ViewBuilder
    private func artworkAndVideoSection(screenWidth: CGFloat, screenHeight: CGFloat) -> some View {
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
                let artworkSize = min(screenWidth - 48, screenHeight * 0.40)

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

    @ViewBuilder
    private func lyricsSection(screenWidth: CGFloat) -> some View {
        VStack(spacing: 0) {
            topMiniTrackHeader(screenWidth: screenWidth)
            LyricsView()
        }
    }

    @ViewBuilder
    private func queueSection(screenWidth: CGFloat) -> some View {
        VStack(spacing: 0) {
            topMiniTrackHeader(screenWidth: screenWidth)

            // Queue Control Pills: Shuffle, Repeat, Autoplay
            HStack(spacing: 12) {
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    isShuffleEnabled.toggle()
                    if isShuffleEnabled {
                        player.queue.shuffle()
                    }
                } label: {
                    Image(systemName: "shuffle")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(isShuffleEnabled ? .white : .white.opacity(0.7))
                        .frame(width: 58, height: 38)
                        .background(
                            Capsule()
                                .fill(isShuffleEnabled ? Color.white.opacity(0.35) : Color.white.opacity(0.12))
                        )
                }

                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    isRepeatEnabled.toggle()
                } label: {
                    Image(systemName: "repeat")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(isRepeatEnabled ? .white : .white.opacity(0.7))
                        .frame(width: 58, height: 38)
                        .background(
                            Capsule()
                                .fill(isRepeatEnabled ? Color.white.opacity(0.35) : Color.white.opacity(0.12))
                        )
                }

                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    isAutoplayEnabled.toggle()
                } label: {
                    Image(systemName: "infinity")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(isAutoplayEnabled ? .white : .white.opacity(0.7))
                        .frame(width: 58, height: 38)
                        .background(
                            Capsule()
                                .fill(isAutoplayEnabled ? Color.white.opacity(0.35) : Color.white.opacity(0.12))
                        )
                }

                Spacer()

                if !player.queue.isEmpty {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        player.clearQueue()
                    } label: {
                        Text("Clear")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.purple)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(Color.purple.opacity(0.16)))
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 10)

            // Queue List
            ScrollView(showsIndicators: false) {
                LazyVStack(spacing: 8) {
                    HStack {
                        Text("Continue Playing")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white.opacity(0.7))
                        Spacer()
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 6)

                    ForEach(Array(player.queue.enumerated()), id: \.element.id) { index, track in
                        let isCurrent = track.id == player.currentTrack?.id

                        HStack(spacing: 12) {
                            Button {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                player.playQueue(tracks: player.queue, startIndex: index)
                            } label: {
                                HStack(spacing: 12) {
                                    AsyncImage(url: track.artworkUrl) { img in
                                        img.resizable().aspectRatio(contentMode: .fill)
                                    } placeholder: {
                                        RoundedRectangle(cornerRadius: 8).fill(Color.white.opacity(0.12))
                                    }
                                    .frame(width: 44, height: 44)
                                    .cornerRadius(8)
                                    .clipped()

                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(track.title)
                                            .font(.system(size: 15, weight: isCurrent ? .bold : .medium))
                                            .foregroundColor(isCurrent ? .purple : .white)
                                            .lineLimit(1)
                                        Text(track.artist)
                                            .font(.system(size: 13))
                                            .foregroundColor(.white.opacity(0.55))
                                            .lineLimit(1)
                                    }

                                    Spacer()
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)

                            Image(systemName: "line.3.horizontal")
                                .font(.system(size: 16))
                                .foregroundColor(.white.opacity(0.35))
                                .frame(width: 32, height: 32)
                        }
                        .padding(.horizontal, 24)
                        .padding(.vertical, 4)
                    }
                }
                .padding(.bottom, 20)
            }
        }
    }

    @ViewBuilder
    private func topMiniTrackHeader(screenWidth: CGFloat) -> some View {
        if let track = player.currentTrack {
            HStack(spacing: 12) {
                AsyncImage(url: track.artworkUrl) { img in
                    img.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.12))
                }
                .frame(width: 48, height: 48)
                .cornerRadius(10)
                .clipped()

                VStack(alignment: .leading, spacing: 3) {
                    Text(track.title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Text(track.artist)
                        .font(.system(size: 13))
                        .foregroundColor(.white.opacity(0.65))
                        .lineLimit(1)
                }

                Spacer()

                // Star Favorite Button
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) {
                        favorites.toggleLike(track: track)
                    }
                } label: {
                    let liked = favorites.isLiked(trackId: track.id)
                    Image(systemName: liked ? "star.fill" : "star")
                        .font(.system(size: 19, weight: .medium))
                        .foregroundColor(liked ? .yellow : .white.opacity(0.75))
                        .frame(width: 36, height: 36)
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
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.white.opacity(0.85))
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(Color.white.opacity(0.10)))
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 8)
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
                            HStack(spacing: 4) {
                                Circle()
                                    .fill(Color.green)
                                    .frame(width: 4, height: 4)
                                Text(player.selectedQuality.badge)
                                    .font(.system(size: 10, weight: .bold))
                                    .tracking(0.3)
                                    .foregroundColor(.white.opacity(0.85))
                            }
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

                AirPlayRoutePicker()
                    .frame(width: 44, height: 44)

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
            .padding(.bottom, max(safeBottom - 4, 12))
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite && seconds >= 0 else { return "0:00" }
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }
}

// MARK: - Sleek Apple Music Scrubber

struct SleekProgressBar: View {
    let currentTime: Double
    let duration: Double
    @Binding var isScrubbing: Bool
    @Binding var scrubTime: Double
    let onSeek: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let total = max(duration, 1.0)
            let active = isScrubbing ? scrubTime : currentTime
            let progress = min(max(active / total, 0.0), 1.0)
            let width = geo.size.width

            ZStack(alignment: .leading) {
                // Background Track
                Capsule()
                    .fill(Color.white.opacity(0.18))
                    .frame(height: isScrubbing ? 6 : 4)

                // Elapsed Track
                Capsule()
                    .fill(Color.white)
                    .frame(width: max(width * progress, 0), height: isScrubbing ? 6 : 4)
            }
            .contentShape(Rectangle().inset(by: -14))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        isScrubbing = true
                        let pct = min(max(value.location.x / width, 0.0), 1.0)
                        scrubTime = pct * total
                    }
                    .onEnded { value in
                        let pct = min(max(value.location.x / width, 0.0), 1.0)
                        let target = pct * total
                        isScrubbing = false
                        onSeek(target)
                    }
            )
            .animation(.spring(response: 0.2, dampingFraction: 0.8), value: isScrubbing)
        }
        .frame(height: 14)
    }
}

// MARK: - UIKit System Volume Slider

struct SystemVolumeSlider: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let volumeView = MPVolumeView(frame: .zero)
        volumeView.showsRouteButton = false
        volumeView.tintColor = .white
        for view in volumeView.subviews {
            if let slider = view as? UISlider {
                slider.tintColor = .white
                slider.maximumTrackTintColor = UIColor.white.withAlphaComponent(0.25)
                let renderer = UIGraphicsImageRenderer(size: CGSize(width: 12, height: 12))
                let thumb = renderer.image { ctx in
                    ctx.cgContext.setFillColor(UIColor.white.cgColor)
                    ctx.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: 12, height: 12))
                }
                slider.setThumbImage(thumb, for: .normal)
            }
        }
        return volumeView
    }

    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

// MARK: - Native AirPlay Audio Route Picker

struct AirPlayRoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView(frame: .zero)
        picker.tintColor = UIColor.white.withAlphaComponent(0.75)
        picker.activeTintColor = UIColor.systemPurple
        picker.prioritizesVideoDevices = false
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

// MARK: - Full Screen Video Player

struct FullScreenVideoPlayerView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @Binding var isPresented: Bool
    @State private var areControlsVisible: Bool = true
    @State private var hideControlsTask: Task<Void, Never>?
    @State private var isScrubbing: Bool = false
    @State private var scrubTime: Double = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let av = player.avPlayer {
                VideoPlayer(player: av)
                    .ignoresSafeArea()
            }

            // Tap gesture background to toggle controls
            Color.black.opacity(0.001)
                .contentShape(Rectangle())
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.25)) {
                        areControlsVisible.toggle()
                    }
                    if areControlsVisible {
                        scheduleHideControls()
                    }
                }

            // Overlay Controls
            if areControlsVisible {
                VStack {
                    // Top Bar
                    HStack(alignment: .center) {
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            isPresented = false
                        } label: {
                            Image(systemName: "chevron.down")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(Color.black.opacity(0.65)))
                        }

                        if let track = player.currentTrack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(track.title)
                                    .font(.system(size: 16, weight: .bold))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                                Text(track.artist)
                                    .font(.system(size: 13))
                                    .foregroundColor(.white.opacity(0.7))
                                    .lineLimit(1)
                            }
                            .padding(.leading, 8)
                        }

                        Spacer()

                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            isPresented = false
                        } label: {
                            Image(systemName: "arrow.down.right.and.arrow.up.left")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(Color.black.opacity(0.65)))
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 16)

                    Spacer()

                    // Center Transport Controls
                    HStack(spacing: 50) {
                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            let newTime = max(player.currentTime - 10, 0)
                            player.seek(to: newTime)
                            scheduleHideControls()
                        } label: {
                            Image(systemName: "gobackward.10")
                                .font(.system(size: 32))
                                .foregroundColor(.white)
                        }

                        Button {
                            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                            player.togglePlayPause()
                            scheduleHideControls()
                        } label: {
                            ZStack {
                                Circle()
                                    .fill(Color.white.opacity(0.9))
                                    .frame(width: 72, height: 72)
                                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                    .font(.system(size: 32, weight: .bold))
                                    .foregroundColor(.black)
                                    .offset(x: player.isPlaying ? 0 : 2)
                            }
                        }

                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            let newTime = min(player.currentTime + 10, player.duration)
                            player.seek(to: newTime)
                            scheduleHideControls()
                        } label: {
                            Image(systemName: "goforward.10")
                                .font(.system(size: 32))
                                .foregroundColor(.white)
                        }
                    }

                    Spacer()

                    // Bottom Scrubber Bar
                    VStack(spacing: 6) {
                        let activeDuration = max(player.duration, 1)
                        let activeTime = isScrubbing ? scrubTime : player.currentTime

                        Slider(
                            value: Binding(
                                get: { activeTime },
                                set: { newValue in
                                    scrubTime = newValue
                                }
                            ),
                            in: 0...activeDuration,
                            onEditingChanged: { editing in
                                isScrubbing = editing
                                if !editing {
                                    player.seek(to: scrubTime)
                                    scheduleHideControls()
                                }
                            }
                        )
                        .tint(.purple)

                        HStack {
                            Text(formatTime(activeTime))
                                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))
                            Spacer()
                            Text(formatTime(activeDuration))
                                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                .foregroundColor(.white.opacity(0.8))
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 36)
                }
                .background(
                    LinearGradient(
                        colors: [Color.black.opacity(0.7), Color.clear, Color.black.opacity(0.85)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .ignoresSafeArea()
                )
                .transition(.opacity)
            }
        }
        .onAppear {
            scheduleHideControls()
        }
    }

    private func scheduleHideControls() {
        hideControlsTask?.cancel()
        hideControlsTask = Task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeInOut(duration: 0.3)) {
                    areControlsVisible = false
                }
            }
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite && seconds >= 0 else { return "0:00" }
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }
}

// MARK: - Dynamic Artwork Color Extractor for Apple Music Glow

public final class ArtworkColorExtractor {
    public static let shared = ArtworkColorExtractor()
    private var colorCache: [URL: (primary: Color, secondary: Color, darkAccent: Color)] = [:]
    private let context = CIContext(options: [.workingColorSpace: kCFNull as Any])

    private init() {}

    public func extractColors(from url: URL?) async -> (primary: Color, secondary: Color, darkAccent: Color) {
        guard let url = url else {
            return (Color(hex: "3B1C10"), Color(hex: "1F120C"), Color(hex: "0D0805"))
        }

        if let cached = colorCache[url] {
            return cached
        }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let uiImage = UIImage(data: data),
                  let ciImage = CIImage(image: uiImage) else {
                return (Color(hex: "3B1C10"), Color(hex: "1F120C"), Color(hex: "0D0805"))
            }

            let extent = ciImage.extent
            let topRect = CGRect(x: 0, y: extent.height * 0.45, width: extent.width, height: extent.height * 0.55)
            let bottomRect = CGRect(x: 0, y: 0, width: extent.width, height: extent.height * 0.50)

            let topUIColor = sampleAverageColor(from: ciImage, in: topRect)
            let bottomUIColor = sampleAverageColor(from: ciImage, in: bottomRect)

            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            topUIColor.getRed(&r, green: &g, blue: &b, alpha: &a)
            let darkAccentUI = UIColor(red: r * 0.35, green: g * 0.35, blue: b * 0.35, alpha: 1.0)

            let result = (
                primary: Color(topUIColor),
                secondary: Color(bottomUIColor),
                darkAccent: Color(darkAccentUI)
            )

            self.colorCache[url] = result
            return result
        } catch {
            return (Color(hex: "3B1C10"), Color(hex: "1F120C"), Color(hex: "0D0805"))
        }
    }

    private func sampleAverageColor(from ciImage: CIImage, in rect: CGRect) -> UIColor {
        let filter = CIFilter(name: "CIAreaAverage", parameters: [
            kCIInputImageKey: ciImage,
            kCIInputExtentKey: CIVector(cgRect: rect)
        ])
        guard let output = filter?.outputImage else {
            return UIColor(red: 0.25, green: 0.15, blue: 0.10, alpha: 1.0)
        }

        var bitmap = [UInt8](repeating: 0, count: 4)
        context.render(
            output,
            toBitmap: &bitmap,
            rowBytes: 4,
            bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
            format: .RGBA8,
            colorSpace: nil
        )

        let red = max(CGFloat(bitmap[0]) / 255.0, 0.14)
        let green = max(CGFloat(bitmap[1]) / 255.0, 0.10)
        let blue = max(CGFloat(bitmap[2]) / 255.0, 0.08)

        return UIColor(red: red, green: green, blue: blue, alpha: 1.0)
    }
}
