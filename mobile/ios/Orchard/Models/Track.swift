import Foundation

public struct Track: Identifiable, Codable, Equatable, Hashable {
    public let id: String
    public let title: String
    public let artist: String
    public let album: String?
    public let durationText: String?
    public let durationSeconds: Double?
    public let artworkUrl: URL?
    public var streamUrl: URL?

    public init(
        id: String,
        title: String,
        artist: String,
        album: String? = nil,
        durationText: String? = nil,
        durationSeconds: Double? = nil,
        artworkUrl: URL? = nil,
        streamUrl: URL? = nil
    ) {
        self.id = id
        self.title = title
        self.artist = artist
        self.album = album
        self.durationText = durationText
        self.durationSeconds = durationSeconds
        self.artworkUrl = artworkUrl
        self.streamUrl = streamUrl
    }

    public static let sample = Track(
        id: "sample_1",
        title: "Yellow",
        artist: "Coldplay",
        album: "Parachutes",
        durationText: "4:29",
        durationSeconds: 269.0,
        artworkUrl: URL(string: "https://lh3.googleusercontent.com/9fXJ2jT1QvH_v6J64L1qj3F7A6A=w512-h512-l90-rj")
    )
}

public struct ShelfSection: Identifiable {
    public let id = UUID()
    public let title: String
    public let tracks: [Track]

    public init(title: String, tracks: [Track]) {
        self.title = title
        self.tracks = tracks
    }
}
