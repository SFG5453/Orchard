import Foundation
import MediaPlayer

public final class PlaylistService: ObservableObject {
    public static let shared = PlaylistService()

    @Published public private(set) var playlists: [Playlist] = []
    private let storageKey = "orchard_saved_playlists"
    private let session = URLSession.shared

    private init() {
        loadPlaylists()
    }

    private func loadPlaylists() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([Playlist].self, from: data) else {
            return
        }
        self.playlists = decoded
    }

    private func persistPlaylists() {
        if let encoded = try? JSONEncoder().encode(playlists) {
            UserDefaults.standard.set(encoded, forKey: storageKey)
        }
    }

    public func deletePlaylist(id: String) {
        playlists.removeAll(where: { $0.id == id })
        persistPlaylists()
    }

    public func addTrack(_ track: Track, to playlistId: String) {
        guard let index = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
        playlists[index].tracks.append(track)
        persistPlaylists()
    }

    public func removeTrack(at index: Int, from playlistId: String) {
        guard let pIndex = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
        guard index >= 0 && index < playlists[pIndex].tracks.count else { return }
        playlists[pIndex].tracks.remove(at: index)
        persistPlaylists()
    }

    // MARK: - Spotify Playlist Importer

    public func importFromSpotify(url: String) async throws -> Playlist {
        var playlistId = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if playlistId.contains("playlist/") {
            let components = playlistId.components(separatedBy: "playlist/")
            if let last = components.last {
                playlistId = last.components(separatedBy: "?").first ?? last
            }
        } else if playlistId.hasPrefix("spotify:playlist:") {
            playlistId = playlistId.replacingOccurrences(of: "spotify:playlist:", with: "")
        }

        guard !playlistId.isEmpty,
              let embedUrl = URL(string: "https://open.spotify.com/embed/playlist/\(playlistId)") else {
            throw URLError(.badURL)
        }

        var request = URLRequest(url: embedUrl)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let html = String(data: data, encoding: .utf8) else {
            throw URLError(.cannotParseResponse)
        }

        // Extract __NEXT_DATA__ JSON script
        guard let scriptStart = html.range(of: "<script id=\"__NEXT_DATA__\" type=\"application/json\">"),
              let scriptEnd = html.range(of: "</script>", range: scriptStart.upperBound..<html.endIndex) else {
            throw URLError(.cannotParseResponse)
        }

        let jsonString = String(html[scriptStart.upperBound..<scriptEnd.lowerBound])
        guard let jsonData = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
              let props = json["props"] as? [String: Any],
              let pageProps = props["pageProps"] as? [String: Any],
              let state = pageProps["state"] as? [String: Any],
              let dataDict = state["data"] as? [String: Any],
              let entity = dataDict["entity"] as? [String: Any] else {
            throw URLError(.cannotParseResponse)
        }

        let title = entity["name"] as? String ?? "Imported Spotify Playlist"
        var artworkUrl: URL? = nil
        if let visualId = entity["visualIdentity"] as? [String: Any],
           let images = visualId["image"] as? [[String: Any]],
           let best = images.last?["url"] as? String {
            artworkUrl = URL(string: best)
        }

        var tracks: [Track] = []
        if let trackList = entity["trackList"] as? [[String: Any]] {
            for item in trackList {
                guard let songTitle = item["title"] as? String else { continue }
                let artist = item["subtitle"] as? String ?? "Unknown Artist"
                let durationMs = item["duration"] as? Double ?? 0
                let durationSec = durationMs / 1000.0
                let mins = Int(durationSec) / 60
                let secs = Int(durationSec) % 60
                let durText = String(format: "%d:%02d", mins, secs)

                let track = Track(
                    id: "imported_\(UUID().uuidString)",
                    title: songTitle,
                    artist: artist,
                    durationText: durText,
                    durationSeconds: durationSec,
                    artworkUrl: artworkUrl
                )
                tracks.append(track)
            }
        }

        let playlist = Playlist(
            title: title,
            description: "Imported from Spotify • \(tracks.count) tracks",
            source: .spotify,
            tracks: tracks,
            artworkUrl: artworkUrl
        )

        await MainActor.run {
            self.playlists.insert(playlist, at: 0)
            self.persistPlaylists()
        }
        return playlist
    }

    // MARK: - Apple Music Web Playlist Importer

    public func importFromAppleMusicLink(url: String) async throws -> Playlist {
        guard let webUrl = URL(string: url),
              webUrl.host?.contains("music.apple.com") == true else {
            throw URLError(.badURL)
        }

        var request = URLRequest(url: webUrl)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let html = String(data: data, encoding: .utf8) else {
            throw URLError(.cannotParseResponse)
        }

        guard let scriptStart = html.range(of: "<script id=schema:music-playlist type=\"application/ld+json\">"),
              let scriptEnd = html.range(of: "</script>", range: scriptStart.upperBound..<html.endIndex) else {
            throw URLError(.cannotParseResponse)
        }

        let jsonString = String(html[scriptStart.upperBound..<scriptEnd.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let jsonData = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            throw URLError(.cannotParseResponse)
        }

        let title = json["name"] as? String ?? "Apple Music Playlist"
        let author = (json["author"] as? [String: Any])?["name"] as? String ?? "Apple Music"

        var tracks: [Track] = []
        var playlistArtwork: URL? = nil

        if let rawTracks = json["track"] as? [[String: Any]] {
            for item in rawTracks {
                guard let songName = item["name"] as? String else { continue }
                var artUrl: URL? = nil
                if let audio = item["audio"] as? [String: Any],
                   let thumbStr = audio["thumbnailUrl"] as? String {
                    artUrl = URL(string: thumbStr)
                    if playlistArtwork == nil {
                        playlistArtwork = artUrl
                    }
                }

                var durationSec: Double? = nil
                var durationText: String? = nil
                if let isoDur = item["duration"] as? String {
                    // Example: PT3M29S
                    let cleaned = isoDur.replacingOccurrences(of: "PT", with: "")
                    var mins = 0
                    var secs = 0
                    if cleaned.contains("M") {
                        let parts = cleaned.components(separatedBy: "M")
                        mins = Int(parts[0]) ?? 0
                        let secPart = parts[1].replacingOccurrences(of: "S", with: "")
                        secs = Int(secPart) ?? 0
                    } else if cleaned.contains("S") {
                        secs = Int(cleaned.replacingOccurrences(of: "S", with: "")) ?? 0
                    }
                    let total = Double(mins * 60 + secs)
                    durationSec = total
                    durationText = String(format: "%d:%02d", mins, secs)
                }

                let track = Track(
                    id: "imported_\(UUID().uuidString)",
                    title: songName,
                    artist: author,
                    durationText: durationText,
                    durationSeconds: durationSec,
                    artworkUrl: artUrl
                )
                tracks.append(track)
            }
        }

        let playlist = Playlist(
            title: title,
            description: "Imported from Apple Music • \(tracks.count) tracks",
            source: .appleMusic,
            tracks: tracks,
            artworkUrl: playlistArtwork
        )

        await MainActor.run {
            self.playlists.insert(playlist, at: 0)
            self.persistPlaylists()
        }
        return playlist
    }

    // MARK: - On-Device Apple Music Library Importer

    public func importFromDeviceAppleMusic() async throws -> [Playlist] {
        let status = await withCheckedContinuation { continuation in
            MPMediaLibrary.requestAuthorization { auth in
                continuation.resume(returning: auth)
            }
        }

        guard status == .authorized else {
            throw NSError(domain: "AppleMusic", code: 403, userInfo: [NSLocalizedDescriptionKey: "Apple Music library access not granted."])
        }

        let query = MPMediaQuery.playlists()
        guard let collections = query.collections else { return [] }

        var imported: [Playlist] = []

        for collection in collections {
            guard let name = collection.value(forProperty: MPMediaPlaylistPropertyName) as? String,
                  !name.isEmpty else { continue }

            var tracks: [Track] = []
            var coverArtUrl: URL? = nil

            for item in collection.items {
                guard let songTitle = item.title, !songTitle.isEmpty else { continue }
                let artist = item.artist ?? "Unknown Artist"
                let duration = item.playbackDuration
                let mins = Int(duration) / 60
                let secs = Int(duration) % 60
                let durText = String(format: "%d:%02d", mins, secs)

                let track = Track(
                    id: "imported_\(UUID().uuidString)",
                    title: songTitle,
                    artist: artist,
                    durationText: durText,
                    durationSeconds: duration,
                    artworkUrl: nil
                )
                tracks.append(track)
            }

            if !tracks.isEmpty {
                let playlist = Playlist(
                    title: name,
                    description: "Imported from iPhone Apple Music • \(tracks.count) tracks",
                    source: .appleMusic,
                    tracks: tracks,
                    artworkUrl: coverArtUrl
                )
                imported.append(playlist)
            }
        }

        await MainActor.run {
            for pl in imported {
                if !self.playlists.contains(where: { $0.title == pl.title }) {
                    self.playlists.append(pl)
                }
            }
            self.persistPlaylists()
        }
        return imported
    }
}
