import SwiftUI

public struct TrackContextMenu: ViewModifier {
    let track: Track
    @ObservedObject var player = AudioPlayerService.shared
    @ObservedObject var favorites = FavoritesService.shared

    public func body(content: Content) -> some View {
        content
            .contextMenu {
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
                    favorites.toggleLike(track: track)
                } label: {
                    let liked = favorites.isLiked(trackId: track.id)
                    Label(
                        liked ? "Remove from Liked" : "Like Song",
                        systemImage: liked ? "heart.slash" : "heart"
                    )
                }
            }
    }
}

public extension View {
    func trackActions(for track: Track) -> some View {
        self.modifier(TrackContextMenu(track: track))
    }
}
