import Foundation

public enum PlaylistSource: String, Codable {
    case appleMusic = "Apple Music"
    case spotify = "Spotify"
    case custom = "Custom"

    public var icon: String {
        switch self {
        case .appleMusic: return "apple.logo"
        case .spotify: return "music.note"
        case .custom: return "music.note.list"
        }
    }
}

public struct Playlist: Identifiable, Codable, Equatable {
    public let id: String
    public var title: String
    public var description: String?
    public var source: PlaylistSource
    public var tracks: [Track]
    public var artworkUrl: URL?
    public var createdAt: Date

    public init(
        id: String = UUID().uuidString,
        title: String,
        description: String? = nil,
        source: PlaylistSource = .custom,
        tracks: [Track] = [],
        artworkUrl: URL? = nil,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.title = title
        self.description = description
        self.source = source
        self.tracks = tracks
        self.artworkUrl = artworkUrl
        self.createdAt = createdAt
    }
}
