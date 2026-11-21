import Foundation
import Combine

@MainActor
public final class FavoritesService: ObservableObject {
    public static let shared = FavoritesService()

    @Published public private(set) var likedTracks: [Track] = []

    private let userDefaultsKey = "orchard_liked_tracks_v1"

    private init() {
        loadLikedTracks()
    }

    public func isLiked(trackId: String) -> Bool {
        likedTracks.contains { $0.id == trackId }
    }

    public func toggleLike(track: Track) {
        if let index = likedTracks.firstIndex(where: { $0.id == track.id }) {
            likedTracks.remove(at: index)
        } else {
            likedTracks.insert(track, at: 0)
        }
        saveLikedTracks()
    }

    public func add(track: Track) {
        guard !isLiked(trackId: track.id) else { return }
        likedTracks.insert(track, at: 0)
        saveLikedTracks()
    }

    public func remove(trackId: String) {
        likedTracks.removeAll { $0.id == trackId }
        saveLikedTracks()
    }

    private func saveLikedTracks() {
        if let encoded = try? JSONEncoder().encode(likedTracks) {
            UserDefaults.standard.set(encoded, forKey: userDefaultsKey)
        }
    }

    private func loadLikedTracks() {
        guard let data = UserDefaults.standard.data(forKey: userDefaultsKey),
              let decoded = try? JSONDecoder().decode([Track].self, from: data) else {
            likedTracks = []
            return
        }
        likedTracks = decoded
    }
}
