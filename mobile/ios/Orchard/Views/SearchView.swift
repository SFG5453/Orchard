import SwiftUI

public struct SearchView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @FocusState private var isSearchFocused: Bool
    @State private var searchText: String = ""
    @State private var searchResults: [Track] = []
    @State private var isSearching: Bool = false
    @State private var errorMessage: String? = nil
    @State private var searchTask: Task<Void, Never>?

    private let suggestions = [
        "Top Hits", "Coldplay", "Arijit Singh", "The Weeknd",
        "Taylor Swift", "Dua Lipa", "Lofi Beats", "Hip Hop"
    ]

    public init() {}

    public var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 0) {
                    // Search Bar
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.purple)

                        TextField("Search songs, artists, albums", text: $searchText)
                            .focused($isSearchFocused)
                            .foregroundColor(.white)
                            .font(.system(size: 15))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .submitLabel(.search)
                            .onSubmit {
                                performSearch(query: searchText)
                            }

                        if !searchText.isEmpty {
                            Button {
                                searchText = ""
                                searchResults = []
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 16))
                                    .foregroundColor(.white.opacity(0.5))
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(
                        RoundedRectangle(cornerRadius: 14)
                            .fill(Color(white: 0.14))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14)
                                    .stroke(Color.white.opacity(0.08), lineWidth: 1)
                            )
                    )
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 12)

                    // Quick Search Chips
                    if searchResults.isEmpty && !isSearching {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(suggestions, id: \.self) { tag in
                                    Button {
                                        searchText = tag
                                        performSearch(query: tag)
                                    } label: {
                                        Text(tag)
                                            .font(.system(size: 13, weight: .semibold))
                                            .foregroundColor(.white.opacity(0.85))
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 7)
                                            .background(
                                                Capsule()
                                                    .fill(Color(white: 0.16))
                                            )
                                    }
                                }
                            }
                            .padding(.horizontal, 16)
                        }
                        .padding(.bottom, 8)
                    }

                    // Results or Shimmer
                    if isSearching {
                        ScrollView(showsIndicators: false) {
                            LazyVStack(spacing: 12) {
                                ForEach(0..<6, id: \.self) { _ in
                                    HStack(spacing: 14) {
                                        RoundedRectangle(cornerRadius: 10)
                                            .fill(Color(white: 0.14))
                                            .frame(width: 52, height: 52)
                                        VStack(alignment: .leading, spacing: 6) {
                                            RoundedRectangle(cornerRadius: 4)
                                                .fill(Color(white: 0.14))
                                                .frame(width: 180, height: 16)
                                            RoundedRectangle(cornerRadius: 4)
                                                .fill(Color(white: 0.10))
                                                .frame(width: 110, height: 12)
                                        }
                                        Spacer()
                                    }
                                    .padding(.horizontal, 16)
                                    .shimmering()
                                }
                            }
                            .padding(.top, 12)
                        }
                    } else if searchResults.isEmpty && !searchText.isEmpty {
                        Spacer()
                        VStack(spacing: 8) {
                            Image(systemName: "questionmark.folder")
                                .font(.system(size: 40))
                                .foregroundColor(.gray.opacity(0.5))
                            Text("No results found")
                                .font(.headline)
                                .foregroundColor(.white)
                            Text("Check the spelling or try searching for another artist or song.")
                                .font(.subheadline)
                                .foregroundColor(.gray)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 32)
                        }
                        Spacer()
                    } else if searchResults.isEmpty {
                        Spacer()
                        VStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(Color.purple.opacity(0.15))
                                    .frame(width: 80, height: 80)
                                Image(systemName: "sparkles")
                                    .font(.system(size: 36))
                                    .foregroundColor(.purple)
                            }
                            Text("Explore Millions of Tracks")
                                .font(.system(size: 18, weight: .bold, design: .rounded))
                                .foregroundColor(.white)
                            Text("Search songs, artists, playlists, or pick a tag above to discover music.")
                                .font(.system(size: 14))
                                .foregroundColor(.white.opacity(0.5))
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 40)
                        }
                        Spacer()
                    } else {
                        List {
                            ForEach(Array(searchResults.enumerated()), id: \.element.id) { index, track in
                                TrackRowView(track: track) {
                                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                    player.playQueue(tracks: searchResults, startIndex: index)
                                }
                                .listRowBackground(Color.black)
                                .listRowSeparatorTint(Color.white.opacity(0.08))
                            }
                        }
                        .listStyle(.plain)
                        .safeAreaInset(edge: .bottom) {
                            Color.clear.frame(height: 140)
                        }
                    }
                }
            }
            .navigationTitle("Search")
            .toolbarBackground(Color.black, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .onReceive(NotificationCenter.default.publisher(for: .focusSearchField)) { _ in
                isSearchFocused = true
            }
        }
    }

    private func performSearch(query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        searchTask?.cancel()
        isSearching = true
        errorMessage = nil

        searchTask = Task {
            do {
                let results = try await YouTubeMusicService.shared.search(query: trimmed)
                guard !Task.isCancelled else { return }
                await MainActor.run {
                    self.searchResults = results
                    withAnimation { self.isSearching = false }
                }
            } catch {
                guard !Task.isCancelled else { return }
                await MainActor.run {
                    self.errorMessage = error.localizedDescription
                    withAnimation { self.isSearching = false }
                }
            }
        }
    }
}

struct TrackRowView: View {
    let track: Track
    let action: () -> Void
    @ObservedObject var player = AudioPlayerService.shared

    var isCurrentTrack: Bool {
        player.currentTrack?.id == track.id
    }

    var body: some View {
        HStack(spacing: 12) {
            // Main Song Tap Area -> Instant 1-Tap Playback
            Button(action: action) {
                HStack(spacing: 14) {
                    ZStack {
                        AsyncImage(url: track.artworkUrl) { phase in
                            switch phase {
                            case .empty:
                                Rectangle()
                                    .fill(Color(white: 0.16))
                                    .shimmering()
                            case .success(let image):
                                image
                                    .resizable()
                                    .aspectRatio(contentMode: .fill)
                            case .failure:
                                Rectangle()
                                    .fill(Color(white: 0.16))
                                    .overlay(
                                        Image(systemName: "music.note")
                                            .foregroundColor(.white.opacity(0.4))
                                    )
                            @unknown default:
                                EmptyView()
                            }
                        }
                        .frame(width: 50, height: 50)
                        .cornerRadius(10)
                        .clipped()

                        if isCurrentTrack {
                            RoundedRectangle(cornerRadius: 10)
                                .fill(Color.black.opacity(0.45))
                                .frame(width: 50, height: 50)
                                .overlay(
                                    Image(systemName: player.isPlaying ? "waveform" : "pause.fill")
                                        .font(.system(size: 16, weight: .bold))
                                        .foregroundColor(.purple)
                                )
                        }
                    }

                    VStack(alignment: .leading, spacing: 3) {
                        Text(track.title)
                            .font(.system(size: 15, weight: isCurrentTrack ? .bold : .semibold))
                            .foregroundColor(isCurrentTrack ? .purple : .white)
                            .lineLimit(1)

                        HStack(spacing: 4) {
                            Text(track.artist)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(.white.opacity(0.6))
                                .lineLimit(1)

                            if let dur = track.durationText {
                                Text("•")
                                    .font(.system(size: 10))
                                    .foregroundColor(.white.opacity(0.3))
                                Text(dur)
                                    .font(.system(size: 12))
                                    .foregroundColor(.white.opacity(0.5))
                            }
                        }
                    }

                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            // Dedicated Context Menu Button
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
                    .font(.system(size: 16))
                    .foregroundColor(.white.opacity(0.6))
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }

            // Quick Play Button indicator
            Button(action: action) {
                Image(systemName: isCurrentTrack ? (player.isPlaying ? "waveform" : "pause.circle.fill") : "play.circle.fill")
                    .font(.system(size: 22))
                    .foregroundColor(isCurrentTrack ? .purple : .white.opacity(0.6))
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 4)
        .trackActions(for: track)
    }
}
