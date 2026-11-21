import SwiftUI

public struct HomeView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @State private var sections: [ShelfSection] = []
    @State private var isLoading: Bool = true
    @State private var errorMessage: String? = nil

    public init() {}

    public var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if isLoading {
                    ScrollView(showsIndicators: false) {
                        HomeSkeletonView()
                            .padding(.top, 12)
                    }
                } else if let error = errorMessage {
                    VStack(spacing: 16) {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.system(size: 44))
                            .foregroundColor(.purple)
                        Text(error)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white)
                        Button {
                            loadFeed()
                        } label: {
                            Text("Retry")
                                .font(.system(size: 15, weight: .bold))
                                .padding(.horizontal, 24)
                                .padding(.vertical, 10)
                                .background(Capsule().fill(Color.purple))
                                .foregroundColor(.white)
                        }
                    }
                    .padding(32)
                } else {
                    ScrollView(showsIndicators: false) {
                        LazyVStack(alignment: .leading, spacing: 32) {
                            // Sleek Header Card
                            HStack(spacing: 12) {
                                ZStack {
                                    Circle()
                                        .fill(
                                            LinearGradient(
                                                colors: [Color.purple, Color.indigo, Color.pink],
                                                startPoint: .topLeading,
                                                endPoint: .bottomTrailing
                                            )
                                        )
                                        .frame(width: 42, height: 42)
                                    Image(systemName: "waveform")
                                        .font(.system(size: 18, weight: .bold))
                                        .foregroundColor(.white)
                                }

                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Orchard")
                                        .font(.system(size: 28, weight: .black, design: .rounded))
                                        .foregroundColor(.white)
                                    Text("High-fidelity music streaming")
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundColor(.white.opacity(0.5))
                                }

                                Spacer()
                            }
                            .padding(.horizontal, 20)
                            .padding(.top, 12)

                            // Shelves
                            ForEach(sections) { section in
                                VStack(alignment: .leading, spacing: 14) {
                                    HStack {
                                        Text(section.title)
                                            .font(.system(size: 20, weight: .bold, design: .rounded))
                                            .foregroundColor(.white)

                                        Spacer()
                                    }
                                    .padding(.horizontal, 20)

                                    ScrollView(.horizontal, showsIndicators: false) {
                                        LazyHStack(spacing: 16) {
                                            ForEach(Array(section.tracks.enumerated()), id: \.element.id) { index, track in
                                                TrackCardView(track: track) {
                                                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                                    player.playQueue(tracks: section.tracks, startIndex: index)
                                                }
                                            }
                                        }
                                        .padding(.horizontal, 20)
                                    }
                                }
                            }
                        }
                        .padding(.bottom, 180) // Ample space for floating MiniPlayer + iOS 18 TabBar
                    }
                    .refreshable {
                        await loadFeedAsync()
                    }
                }
            }
            .navigationBarHidden(true)
        }
        .onAppear {
            if sections.isEmpty {
                loadFeed()
            }
        }
    }

    private func loadFeed() {
        isLoading = true
        errorMessage = nil
        Task {
            await loadFeedAsync()
        }
    }

    private func loadFeedAsync() async {
        do {
            let fetched = try await YouTubeMusicService.shared.getHomeFeed()
            await MainActor.run {
                if fetched.isEmpty {
                    Task {
                        let searchTracks = try await YouTubeMusicService.shared.search(query: "Top Global Hits 2026")
                        self.sections = [ShelfSection(title: "Today's Hits", tracks: searchTracks)]
                        withAnimation { self.isLoading = false }
                    }
                } else {
                    self.sections = fetched
                    withAnimation { self.isLoading = false }
                }
            }
        } catch {
            await MainActor.run {
                self.errorMessage = "Unable to connect. Tap retry."
                withAnimation { self.isLoading = false }
            }
        }
    }
}

struct TrackCardView: View {
    let track: Track
    let action: () -> Void
    @ObservedObject var player = AudioPlayerService.shared

    var isCurrentTrack: Bool {
        player.currentTrack?.id == track.id
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Main Tap Area -> Instant 1-Tap Playback
            Button(action: action) {
                ZStack(alignment: .bottomTrailing) {
                    AsyncImage(url: track.artworkUrl) { phase in
                        switch phase {
                        case .empty:
                            Rectangle()
                                .fill(Color(white: 0.14))
                                .shimmering()
                        case .success(let image):
                            image
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        case .failure:
                            Rectangle()
                                .fill(Color(white: 0.14))
                                .overlay(
                                    Image(systemName: "music.note")
                                        .font(.system(size: 28))
                                        .foregroundColor(.white.opacity(0.3))
                                )
                        @unknown default:
                            EmptyView()
                        }
                    }
                    .frame(width: 148, height: 148)
                    .cornerRadius(14)
                    .clipped()
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(isCurrentTrack ? Color.purple : Color.white.opacity(0.08), lineWidth: isCurrentTrack ? 2 : 1)
                    )
                    .shadow(color: .black.opacity(0.35), radius: 8, y: 4)

                    // Active state badge
                    Circle()
                        .fill(isCurrentTrack ? Color.purple : Color.black.opacity(0.65))
                        .frame(width: 32, height: 32)
                        .overlay(
                            Group {
                                if isCurrentTrack && player.isLoading {
                                    ProgressView()
                                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                        .scaleEffect(0.7)
                                } else if isCurrentTrack && player.isPlaying {
                                    Image(systemName: "waveform")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(.white)
                                } else {
                                    Image(systemName: "play.fill")
                                        .font(.system(size: 13))
                                        .foregroundColor(.white)
                                        .offset(x: 1)
                                }
                            }
                        )
                        .padding(8)
                }
            }
            .buttonStyle(.plain)

            // Title Tap Area
            Button(action: action) {
                Text(track.title)
                    .font(.system(size: 14, weight: isCurrentTrack ? .bold : .semibold))
                    .foregroundColor(isCurrentTrack ? .purple : .white)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .frame(width: 148, height: 36, alignment: .topLeading)
            }
            .buttonStyle(.plain)

            // Artist and Context Menu Row
            HStack {
                Button(action: action) {
                    Text(track.artist)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(.white.opacity(0.6))
                        .lineLimit(1)
                        .frame(maxWidth: 110, alignment: .leading)
                }
                .buttonStyle(.plain)

                Spacer()

                Menu {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        player.addToQueueNext(track: track)
                    } label: {
                        Label("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward")
                    }

                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        player.addToQueue(track: track)
                    } label: {
                        Label("Add to Queue", systemImage: "text.badge.plus")
                    }

                    Divider()

                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        FavoritesService.shared.toggleLike(track: track)
                    } label: {
                        let liked = FavoritesService.shared.isLiked(trackId: track.id)
                        Label(liked ? "Unlike" : "Like Song", systemImage: liked ? "heart.slash" : "heart")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 13))
                        .foregroundColor(.white.opacity(0.5))
                        .frame(width: 24, height: 20)
                        .contentShape(Rectangle())
                }
            }
            .frame(width: 148)
        }
        .trackActions(for: track)
    }
}
