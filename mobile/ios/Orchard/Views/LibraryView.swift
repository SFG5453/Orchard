import SwiftUI

public struct LibraryView: View {
    @ObservedObject var favorites = FavoritesService.shared
    @ObservedObject var player = AudioPlayerService.shared

    public init() {}

    public var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                ScrollView(.vertical, showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 26) {
                        // Liked Songs Hero Card
                        VStack(alignment: .leading, spacing: 14) {
                            HStack(alignment: .top) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 18)
                                        .fill(
                                            LinearGradient(
                                                colors: [Color.pink, Color.purple],
                                                startPoint: .topLeading,
                                                endPoint: .bottomTrailing
                                            )
                                        )
                                        .frame(width: 58, height: 58)
                                        .shadow(color: .pink.opacity(0.4), radius: 10, y: 4)

                                    Image(systemName: "heart.fill")
                                        .font(.system(size: 26))
                                        .foregroundColor(.white)
                                }

                                Spacer()

                                if !favorites.likedTracks.isEmpty {
                                    HStack(spacing: 12) {
                                        Button {
                                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                            let shuffled = favorites.likedTracks.shuffled()
                                            player.playQueue(tracks: shuffled)
                                        } label: {
                                            Image(systemName: "shuffle")
                                                .font(.system(size: 16, weight: .semibold))
                                                .foregroundColor(.white)
                                                .frame(width: 38, height: 38)
                                                .background(Color.white.opacity(0.12))
                                                .clipShape(Circle())
                                        }

                                        Button {
                                            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
                                            player.playQueue(tracks: favorites.likedTracks)
                                        } label: {
                                            Image(systemName: "play.fill")
                                                .font(.system(size: 18, weight: .bold))
                                                .foregroundColor(.black)
                                                .frame(width: 44, height: 44)
                                                .background(Color.white)
                                                .clipShape(Circle())
                                                .shadow(color: .white.opacity(0.3), radius: 8, y: 2)
                                        }
                                    }
                                }
                            }

                            VStack(alignment: .leading, spacing: 4) {
                                Text("Liked Songs")
                                    .font(.system(size: 22, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)

                                Text("\(favorites.likedTracks.count) \(favorites.likedTracks.count == 1 ? "song" : "songs")")
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundColor(.white.opacity(0.6))
                            }
                        }
                        .padding(20)
                        .background(
                            RoundedRectangle(cornerRadius: 24)
                                .fill(Color.white.opacity(0.06))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 24)
                                        .stroke(Color.white.opacity(0.1), lineWidth: 0.8)
                                )
                        )
                        .padding(.horizontal, 20)

                        // Liked Songs List
                        if favorites.likedTracks.isEmpty {
                            VStack(spacing: 12) {
                                Image(systemName: "heart.slash")
                                    .font(.system(size: 40))
                                    .foregroundColor(.white.opacity(0.25))
                                    .padding(.top, 20)

                                Text("No Liked Songs Yet")
                                    .font(.system(size: 17, weight: .semibold))
                                    .foregroundColor(.white.opacity(0.8))

                                Text("Tap the heart icon on any song or in the player to save your favorites here.")
                                    .font(.system(size: 13))
                                    .foregroundColor(.white.opacity(0.45))
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 40)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 24)
                        } else {
                            VStack(spacing: 8) {
                                ForEach(Array(favorites.likedTracks.enumerated()), id: \.element.id) { index, track in
                                    HStack(spacing: 14) {
                                        // Tap to play song area
                                        Button {
                                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                            player.playQueue(tracks: favorites.likedTracks, startIndex: index)
                                        } label: {
                                            HStack(spacing: 14) {
                                                AsyncImage(url: track.artworkUrl) { image in
                                                    image.resizable().aspectRatio(contentMode: .fill)
                                                } placeholder: {
                                                    RoundedRectangle(cornerRadius: 10)
                                                        .fill(Color.white.opacity(0.1))
                                                }
                                                .frame(width: 52, height: 52)
                                                .cornerRadius(10)

                                                VStack(alignment: .leading, spacing: 4) {
                                                    Text(track.title)
                                                        .font(.system(size: 15, weight: .semibold))
                                                        .foregroundColor(player.currentTrack?.id == track.id ? .purple : .white)
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

                                        // Heart Toggle Button (decoupled from row tap)
                                        Button {
                                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                            favorites.toggleLike(track: track)
                                        } label: {
                                            Image(systemName: "heart.fill")
                                                .font(.system(size: 18))
                                                .foregroundColor(.pink)
                                                .frame(width: 36, height: 36)
                                                .contentShape(Rectangle())
                                        }
                                        .buttonStyle(.plain)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .background(
                                        RoundedRectangle(cornerRadius: 14)
                                            .fill(player.currentTrack?.id == track.id ? Color.purple.opacity(0.14) : Color.white.opacity(0.04))
                                    )
                                    .trackActions(for: track)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.top, 12)
                        }

                        // Recently Played Section
                        if !player.recentlyPlayed.isEmpty {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Recently Played")
                                    .font(.system(size: 20, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 20)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 14) {
                                        ForEach(player.recentlyPlayed, id: \.id) { track in
                                            Button {
                                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                                player.play(track: track)
                                            } label: {
                                                VStack(alignment: .leading, spacing: 8) {
                                                    AsyncImage(url: track.artworkUrl) { image in
                                                        image.resizable().aspectRatio(contentMode: .fill)
                                                    } placeholder: {
                                                        RoundedRectangle(cornerRadius: 14)
                                                            .fill(Color.white.opacity(0.1))
                                                    }
                                                    .frame(width: 130, height: 130)
                                                    .cornerRadius(14)

                                                    Text(track.title)
                                                        .font(.system(size: 13, weight: .semibold))
                                                        .foregroundColor(.white)
                                                        .lineLimit(1)
                                                        .frame(width: 130, alignment: .leading)

                                                    Text(track.artist)
                                                        .font(.system(size: 11))
                                                        .foregroundColor(.white.opacity(0.5))
                                                        .lineLimit(1)
                                                        .frame(width: 130, alignment: .leading)
                                                }
                                            }
                                            .buttonStyle(.plain)
                                        }
                                    }
                                    .padding(.horizontal, 20)
                                }
                            }
                        }

                        Spacer(minLength: 180)
                    }
                    .padding(.top, 16)
                }
            }
            .navigationTitle("Library")
            .navigationBarTitleDisplayMode(.large)
            .toolbarBackground(.black, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }
}
