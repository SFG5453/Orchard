import SwiftUI

public struct QueueView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @ObservedObject var favorites = FavoritesService.shared

    public init() {}

    public var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if player.queue.isEmpty {
                    VStack(spacing: 18) {
                        ZStack {
                            Circle()
                                .fill(Color.purple.opacity(0.14))
                                .frame(width: 84, height: 84)
                            Image(systemName: "music.note.list")
                                .font(.system(size: 36))
                                .foregroundColor(.purple)
                        }

                        Text("Queue is empty")
                            .font(.system(size: 20, weight: .bold, design: .rounded))
                            .foregroundColor(.white)

                        Text("Play any song from Home, Search, or Library. Tap ··· on any track to add it to your queue.")
                            .font(.system(size: 14))
                            .foregroundColor(.white.opacity(0.55))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 40)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        // Currently Playing Section
                        if let current = player.currentTrack {
                            Section {
                                HStack(spacing: 14) {
                                    AsyncImage(url: current.artworkUrl) { image in
                                        image.resizable().aspectRatio(contentMode: .fill)
                                    } placeholder: {
                                        RoundedRectangle(cornerRadius: 10)
                                            .fill(Color.white.opacity(0.12))
                                    }
                                    .frame(width: 52, height: 52)
                                    .cornerRadius(10)
                                    .clipped()

                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(current.title)
                                            .font(.system(size: 16, weight: .bold, design: .rounded))
                                            .foregroundColor(.purple)
                                            .lineLimit(1)

                                        Text(current.artist)
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundColor(.white.opacity(0.65))
                                            .lineLimit(1)
                                    }

                                    Spacer()

                                    // Animated playing indicator
                                    HStack(spacing: 3) {
                                        ForEach(0..<3) { i in
                                            RoundedRectangle(cornerRadius: 2)
                                                .fill(Color.purple)
                                                .frame(width: 3, height: player.isPlaying ? CGFloat(10 + (i * 5)) : 6)
                                                .animation(.easeInOut(duration: 0.4).repeatForever().delay(Double(i) * 0.15), value: player.isPlaying)
                                        }
                                    }
                                }
                                .padding(.vertical, 4)
                                .listRowBackground(Color.purple.opacity(0.12))
                                .listRowSeparatorTint(Color.white.opacity(0.08))
                            } header: {
                                Text("NOW PLAYING")
                                    .font(.system(size: 11, weight: .bold))
                                    .tracking(1.2)
                                    .foregroundColor(.white.opacity(0.5))
                            }
                        }

                        // Up Next Section
                        Section {
                            ForEach(Array(player.queue.enumerated()), id: \.element.id) { index, track in
                                let isCurrent = track.id == player.currentTrack?.id

                                HStack(spacing: 14) {
                                    // 1-Tap Play Area
                                    Button {
                                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                        player.playQueue(tracks: player.queue, startIndex: index)
                                    } label: {
                                        HStack(spacing: 14) {
                                            AsyncImage(url: track.artworkUrl) { image in
                                                image.resizable().aspectRatio(contentMode: .fill)
                                            } placeholder: {
                                                RoundedRectangle(cornerRadius: 8)
                                                    .fill(Color(white: 0.16))
                                            }
                                            .frame(width: 46, height: 46)
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

                                    // Context Menu Ellipsis
                                    Menu {
                                        Button {
                                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                            player.playQueue(tracks: player.queue, startIndex: index)
                                        } label: {
                                            Label("Play Now", systemImage: "play.fill")
                                        }

                                        Button {
                                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                            favorites.toggleLike(track: track)
                                        } label: {
                                            let liked = favorites.isLiked(trackId: track.id)
                                            Label(liked ? "Unlike" : "Like", systemImage: liked ? "heart.slash" : "heart")
                                        }

                                        Button(role: .destructive) {
                                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                            player.removeFromQueue(at: IndexSet(integer: index))
                                        } label: {
                                            Label("Remove from Queue", systemImage: "trash")
                                        }
                                    } label: {
                                        Image(systemName: "ellipsis")
                                            .font(.system(size: 16))
                                            .foregroundColor(.white.opacity(0.6))
                                            .frame(width: 36, height: 36)
                                            .contentShape(Rectangle())
                                    }
                                }
                                .padding(.vertical, 2)
                                .listRowBackground(Color.black)
                                .listRowSeparatorTint(Color.white.opacity(0.08))
                            }
                            .onDelete { offsets in
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                player.removeFromQueue(at: offsets)
                            }
                            .onMove { source, dest in
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                player.moveInQueue(from: source, to: dest)
                            }
                        } header: {
                            HStack {
                                Text("UP NEXT (\(player.queue.count))")
                                    .font(.system(size: 11, weight: .bold))
                                    .tracking(1.2)
                                    .foregroundColor(.white.opacity(0.5))

                                Spacer()

                                Button {
                                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                    player.clearQueue()
                                } label: {
                                    Text("Clear")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundColor(.purple)
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .safeAreaInset(edge: .bottom) {
                        Color.clear.frame(height: 140)
                    }
                }
            }
            .navigationTitle("Queue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if !player.queue.isEmpty {
                        EditButton()
                            .foregroundColor(.purple)
                    }
                }
            }
            .toolbarBackground(Color.black, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }
}
