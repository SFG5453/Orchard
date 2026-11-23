import SwiftUI

public struct PlaylistDetailView: View {
    public let playlist: Playlist
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var player = AudioPlayerService.shared
    @ObservedObject var playlistService = PlaylistService.shared

    public init(playlist: Playlist) {
        self.playlist = playlist
    }

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(spacing: 20) {
                    // Header Artwork & Info
                    VStack(spacing: 14) {
                        if let artUrl = playlist.artworkUrl {
                            AsyncImage(url: artUrl) { img in
                                img.resizable().aspectRatio(contentMode: .fill)
                            } placeholder: {
                                RoundedRectangle(cornerRadius: 18)
                                    .fill(Color.white.opacity(0.12))
                            }
                            .frame(width: 180, height: 180)
                            .cornerRadius(18)
                            .shadow(color: .purple.opacity(0.3), radius: 16, y: 8)
                        } else {
                            ZStack {
                                RoundedRectangle(cornerRadius: 18)
                                    .fill(
                                        LinearGradient(
                                            colors: playlist.source == .spotify ? [Color.green, Color.black] : [Color.pink, Color.purple],
                                            startPoint: .topLeading,
                                            endPoint: .bottomTrailing
                                        )
                                    )
                                    .frame(width: 180, height: 180)

                                Image(systemName: playlist.source == .spotify ? "music.note" : "apple.logo")
                                    .font(.system(size: 54))
                                    .foregroundColor(.white)
                            }
                        }

                        VStack(spacing: 4) {
                            Text(playlist.title)
                                .font(.system(size: 22, weight: .bold, design: .rounded))
                                .foregroundColor(.white)
                                .multilineTextAlignment(.center)

                            HStack(spacing: 6) {
                                Image(systemName: playlist.source.icon)
                                    .font(.system(size: 11))
                                Text(playlist.source.rawValue)
                                    .font(.system(size: 12, weight: .semibold))
                                Text("•")
                                Text("\(playlist.tracks.count) songs")
                                    .font(.system(size: 12))
                            }
                            .foregroundColor(.white.opacity(0.6))
                        }
                    }
                    .padding(.top, 16)

                    // Action Controls: Play All & Shuffle
                    if !playlist.tracks.isEmpty {
                        HStack(spacing: 16) {
                            Button {
                                UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                                player.playQueue(tracks: playlist.tracks)
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "play.fill")
                                        .font(.system(size: 14, weight: .bold))
                                    Text("Play")
                                        .font(.system(size: 15, weight: .bold))
                                }
                                .foregroundColor(.black)
                                .frame(maxWidth: .infinity)
                                .frame(height: 46)
                                .background(Color.white)
                                .cornerRadius(12)
                            }

                            Button {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                let shuffled = playlist.tracks.shuffled()
                                player.playQueue(tracks: shuffled)
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "shuffle")
                                        .font(.system(size: 14, weight: .bold))
                                    Text("Shuffle")
                                        .font(.system(size: 15, weight: .bold))
                                }
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 46)
                                .background(Color.white.opacity(0.12))
                                .cornerRadius(12)
                            }
                        }
                        .padding(.horizontal, 24)
                    }

                    // Track List
                    LazyVStack(spacing: 8) {
                        ForEach(Array(playlist.tracks.enumerated()), id: \.element.id) { index, track in
                            let isPlaying = player.currentTrack?.id == track.id

                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                player.playQueue(tracks: playlist.tracks, startIndex: index)
                            } label: {
                                HStack(spacing: 12) {
                                    Text("\(index + 1)")
                                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                                        .foregroundColor(.white.opacity(0.4))
                                        .frame(width: 24, alignment: .center)

                                    if let art = track.artworkUrl {
                                        AsyncImage(url: art) { img in
                                            img.resizable().aspectRatio(contentMode: .fill)
                                        } placeholder: {
                                            RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(0.1))
                                        }
                                        .frame(width: 40, height: 40)
                                        .cornerRadius(6)
                                        .clipped()
                                    }

                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(track.title)
                                            .font(.system(size: 15, weight: isPlaying ? .bold : .medium))
                                            .foregroundColor(isPlaying ? .purple : .white)
                                            .lineLimit(1)

                                        Text(track.artist)
                                            .font(.system(size: 12))
                                            .foregroundColor(.white.opacity(0.55))
                                            .lineLimit(1)
                                    }

                                    Spacer()

                                    if let dur = track.durationText {
                                        Text(dur)
                                            .font(.system(size: 12, design: .monospaced))
                                            .foregroundColor(.white.opacity(0.4))
                                    }
                                }
                                .padding(.horizontal, 20)
                                .padding(.vertical, 8)
                                .background(isPlaying ? Color.purple.opacity(0.15) : Color.clear)
                                .cornerRadius(10)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 8)
                    .padding(.bottom, 120)
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .destructiveAction) {
                Button(role: .destructive) {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    playlistService.deletePlaylist(id: playlist.id)
                    dismiss()
                } label: {
                    Image(systemName: "trash")
                        .foregroundColor(.red.opacity(0.8))
                }
            }
        }
    }
}
