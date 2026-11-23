import SwiftUI

struct PlayerMiniTrackHeader: View {
    @ObservedObject var player = AudioPlayerService.shared
    @ObservedObject var favorites = FavoritesService.shared
    @Binding var isQualitySheetPresented: Bool

    var body: some View {
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
}

struct PlayerQueueView: View {
    @ObservedObject var player = AudioPlayerService.shared
    @ObservedObject var favorites = FavoritesService.shared
    @Binding var isQualitySheetPresented: Bool
    @Binding var isShuffleEnabled: Bool
    @Binding var isRepeatEnabled: Bool
    @Binding var isAutoplayEnabled: Bool

    var body: some View {
        VStack(spacing: 0) {
            PlayerMiniTrackHeader(
                player: player,
                favorites: favorites,
                isQualitySheetPresented: $isQualitySheetPresented
            )

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
}
