import Foundation

public final class YouTubeMusicService {
    public static let shared = YouTubeMusicService()
    private let session = URLSession.shared

    private let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private let clientVersion = "1.20260213.01.00"

    private var streamCache: [String: [AudioQuality: URL]] = [:]
    private var videoStreamCache: [String: URL] = [:]
    private var officialVideoCache: [String: String] = [:]
    private var durationCache: [String: Double] = [:]
    private let cacheLock = NSLock()

    private let categoryLabels: Set<String> = ["Song", "Video", "Album", "Single", "EP", "Episode", "Podcast", "Show", "•"]

    private init() {}

    public func search(query: String) async throws -> [Track] {
        guard let url = URL(string: "https://music.youtube.com/youtubei/v1/search") else { return [] }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://music.youtube.com", forHTTPHeaderField: "Origin")
        request.setValue("67", forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(clientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")

        let payload: [String: Any] = [
            "context": ["client": ["clientName": "WEB_REMIX", "clientVersion": clientVersion, "hl": "en", "gl": "US"]],
            "query": query
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        return parseTracks(from: json)
    }

    public func getHomeFeed() async throws -> [ShelfSection] {
        async let hits = search(query: "Top Global Hits 2026")
        async let trending = search(query: "Trending Songs 2026")
        async let chill = search(query: "Chill Acoustic Hits")
        async let featured = search(query: "New Music Friday")

        var sections: [ShelfSection] = []
        if let tracks = try? await hits, !tracks.isEmpty { sections.append(ShelfSection(title: "Today's Top Hits", tracks: tracks)) }
        if let tracks = try? await trending, !tracks.isEmpty { sections.append(ShelfSection(title: "Trending & Viral", tracks: tracks)) }
        if let tracks = try? await chill, !tracks.isEmpty { sections.append(ShelfSection(title: "Chill & Acoustic", tracks: tracks)) }
        if let tracks = try? await featured, !tracks.isEmpty { sections.append(ShelfSection(title: "Featured Tracks", tracks: tracks)) }
        return sections
    }

    public func getRecommendations(for track: Track) async -> [Track] {
        if let url = URL(string: "https://music.youtube.com/youtubei/v1/next") {
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue("https://music.youtube.com", forHTTPHeaderField: "Origin")
            request.setValue("67", forHTTPHeaderField: "X-YouTube-Client-Name")
            request.setValue(clientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")

            let payload: [String: Any] = [
                "context": ["client": ["clientName": "WEB_REMIX", "clientVersion": clientVersion, "hl": "en", "gl": "US"]],
                "videoId": track.id
            ]

            if let body = try? JSONSerialization.data(withJSONObject: payload) {
                request.httpBody = body
                if let (data, response) = try? await session.data(for: request),
                   (response as? HTTPURLResponse)?.statusCode == 200,
                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    let tracks = parseTracks(from: json).filter { $0.id != track.id }
                    if !tracks.isEmpty { return tracks }
                }
            }
        }

        if let tracks = try? await search(query: "\(track.artist) songs"), !tracks.isEmpty {
            return tracks.filter { $0.id != track.id }
        }
        return []
    }

    public func resolveImportedTrack(title: String, artist: String) async -> Track? {
        let query = "\(title) \(artist)"
        if let results = try? await search(query: query), let best = results.first { return best }
        return nil
    }

    // MARK: - Direct Stream Extraction

    private func fetchStreamingData(for videoId: String) async throws -> ([String: Any], Double?) {
        guard let url = URL(string: "https://www.youtube.com/youtubei/v1/player") else { throw URLError(.badURL) }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("com.google.ios.youtubeproducer/0.147.945 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)", forHTTPHeaderField: "User-Agent")
        request.setValue("100", forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue("0.147.945", forHTTPHeaderField: "X-YouTube-Client-Version")

        let payload: [String: Any] = [
            "context": ["client": ["clientName": "IOS_PRODUCER", "clientVersion": "0.147.945", "deviceMake": "Apple", "deviceModel": "iPhone16,2", "osName": "iPhone", "osVersion": "18.3.2.22D82", "hl": "en", "gl": "US"]],
            "videoId": videoId
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let streamingData = json["streamingData"] as? [String: Any] else { throw URLError(.cannotParseResponse) }

        var duration: Double? = nil
        if let videoDetails = json["videoDetails"] as? [String: Any],
           let lengthStr = videoDetails["lengthSeconds"] as? String,
           let length = Double(lengthStr), length > 0 {
            duration = length
        }
        return (streamingData, duration)
    }

    public struct StreamResult {
        public let url: URL
        public let duration: Double?
    }

    public func resolveStream(for videoId: String, quality: AudioQuality = .high) async throws -> StreamResult {
        cacheLock.lock()
        if let cached = streamCache[videoId]?[quality] {
            let dur = durationCache[videoId]
            cacheLock.unlock()
            return StreamResult(url: cached, duration: dur)
        }
        cacheLock.unlock()

        let (streamingData, parsedDuration) = try await fetchStreamingData(for: videoId)
        if let dur = parsedDuration {
            cacheLock.lock()
            durationCache[videoId] = dur
            cacheLock.unlock()
        }

        let adaptiveFormats = streamingData["adaptiveFormats"] as? [[String: Any]] ?? []
        let formats = streamingData["formats"] as? [[String: Any]] ?? []
        let allFormats = adaptiveFormats + formats

        var formatMap: [AudioQuality: URL] = [:]
        for q in AudioQuality.allCases {
            if let matchedFormat = findBestFormat(in: allFormats, for: q),
               let urlStr = matchedFormat["url"] as? String,
               let streamUrl = URL(string: urlStr) {
                formatMap[q] = streamUrl
            }
        }

        var fallbackHls: URL? = nil
        if let hlsStr = streamingData["hlsManifestUrl"] as? String, let hlsUrl = URL(string: hlsStr) {
            fallbackHls = hlsUrl
            cacheLock.lock()
            videoStreamCache[videoId] = hlsUrl
            cacheLock.unlock()
        }

        cacheLock.lock()
        if streamCache[videoId] == nil { streamCache[videoId] = [:] }
        for (q, u) in formatMap { streamCache[videoId]?[q] = u }
        cacheLock.unlock()

        let selectedUrl = formatMap[quality] ?? formatMap.values.first ?? fallbackHls
        guard let url = selectedUrl else { throw URLError(.resourceUnavailable) }
        return StreamResult(url: url, duration: parsedDuration)
    }

    public func resolveStreamUrl(for videoId: String, quality: AudioQuality = .high) async throws -> URL {
        return try await resolveStream(for: videoId, quality: quality).url
    }

    public func getCachedDuration(for videoId: String) -> Double? {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        return durationCache[videoId]
    }

    public func findOfficialVideoId(for track: Track) async -> String {
        cacheLock.lock()
        if let cached = officialVideoCache[track.id] {
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()

        guard let url = URL(string: "https://www.youtube.com/youtubei/v1/search") else { return track.id }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X)", forHTTPHeaderField: "User-Agent")

        let cleanTitle = track.title.replacingOccurrences(of: "(Official Video)", with: "", options: .caseInsensitive).trimmingCharacters(in: .whitespacesAndNewlines)
        let query = "\(cleanTitle) \(track.artist) official music video"
        let payload: [String: Any] = [
            "context": ["client": ["clientName": "WEB", "clientVersion": "2.20240101.01.00", "hl": "en", "gl": "US"]],
            "query": query
        ]

        if let body = try? JSONSerialization.data(withJSONObject: payload) {
            request.httpBody = body
            if let (data, resp) = try? await session.data(for: request),
               (resp as? HTTPURLResponse)?.statusCode == 200,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {

                var candidates: [(id: String, title: String)] = []
                func findVideoRenderers(_ node: Any) {
                    if let dict = node as? [String: Any] {
                        if let vr = dict["videoRenderer"] as? [String: Any],
                           let vid = vr["videoId"] as? String,
                           let titleDict = vr["title"] as? [String: Any],
                           let runs = titleDict["runs"] as? [[String: Any]],
                           let vtitle = runs.first?["text"] as? String {
                            candidates.append((id: vid, title: vtitle))
                        }
                        for val in dict.values { findVideoRenderers(val) }
                    } else if let arr = node as? [Any] {
                        for item in arr { findVideoRenderers(item) }
                    }
                }
                findVideoRenderers(json)

                for c in candidates {
                    let low = c.title.lowercased()
                    if low.contains("official") || low.contains("music video") || low.contains("video song") {
                        cacheLock.lock()
                        officialVideoCache[track.id] = c.id
                        cacheLock.unlock()
                        return c.id
                    }
                }
                if let first = candidates.first {
                    cacheLock.lock()
                    officialVideoCache[track.id] = first.id
                    cacheLock.unlock()
                    return first.id
                }
            }
        }
        return track.id
    }

    public func resolveVideoStreamUrl(for track: Track) async throws -> URL {
        let videoId = await findOfficialVideoId(for: track)
        return try await resolveVideoStreamUrl(forVideoId: videoId)
    }

    public func resolveVideoStreamUrl(forVideoId videoId: String) async throws -> URL {
        cacheLock.lock()
        if let cached = videoStreamCache[videoId] {
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()

        let (streamingData, _) = try await fetchStreamingData(for: videoId)
        if let hlsStr = streamingData["hlsManifestUrl"] as? String, let hlsUrl = URL(string: hlsStr) {
            cacheLock.lock()
            videoStreamCache[videoId] = hlsUrl
            cacheLock.unlock()
            return hlsUrl
        }

        let formats = streamingData["formats"] as? [[String: Any]] ?? []
        if let firstWithUrl = formats.first(where: { ($0["url"] as? String) != nil }),
           let urlStr = firstWithUrl["url"] as? String,
           let url = URL(string: urlStr) {
            cacheLock.lock()
            videoStreamCache[videoId] = url
            cacheLock.unlock()
            return url
        }

        return try await resolveStreamUrl(for: videoId, quality: .high)
    }

    private func findBestFormat(in formats: [[String: Any]], for quality: AudioQuality) -> [String: Any]? {
        let audioOnly = formats.filter {
            let mime = ($0["mimeType"] as? String ?? "")
            return (mime.contains("audio") || mime.contains("mp4a") || mime.contains("opus")) && $0["url"] != nil
        }

        for itag in quality.itags {
            if let found = audioOnly.first(where: { ($0["itag"] as? Int) == itag }) {
                return found
            }
        }

        let sorted = audioOnly.sorted { (a, b) -> Bool in
            let bitA = a["bitrate"] as? Int ?? 0
            let bitB = b["bitrate"] as? Int ?? 0
            return bitA < bitB
        }

        switch quality {
        case .lossless, .high: return sorted.last
        case .medium:
            let midIndex = sorted.count / 2
            return sorted.indices.contains(midIndex) ? sorted[midIndex] : sorted.first
        case .low: return sorted.first
        }
    }

    public func prefetchStream(for videoId: String, quality: AudioQuality) {
        Task(priority: .background) {
            _ = try? await self.resolveStreamUrl(for: videoId, quality: quality)
        }
    }

    // MARK: - Parsing Helpers

    private func parseDuration(from text: String?) -> Double? {
        guard let text = text else { return nil }
        let parts = text.split(separator: ":").compactMap { Double($0) }
        if parts.count == 2 { return parts[0] * 60 + parts[1] }
        if parts.count == 3 { return parts[0] * 3600 + parts[1] * 60 + parts[2] }
        return nil
    }

    private func extractThumbnailUrl(from dict: [String: Any]?) -> URL? {
        guard let dict = dict else { return nil }
        let thumbObj = (dict["musicThumbnailRenderer"] as? [String: Any])?["thumbnail"] as? [String: Any] ?? dict
        let thumbnails = thumbObj["thumbnails"] as? [[String: Any]]
        if let urlStr = thumbnails?.last?["url"] as? String { return URL(string: urlStr) }
        return nil
    }

    private func parseTracks(from json: [String: Any]) -> [Track] {
        var results: [Track] = []

        func traverse(node: Any) {
            if let dict = node as? [String: Any] {
                if let renderer = dict["musicResponsiveListItemRenderer"] as? [String: Any] {
                    if let track = extractTrack(from: renderer) { results.append(track) }
                } else if let renderer = dict["playlistPanelVideoRenderer"] as? [String: Any] {
                    if let track = extractTrackFromPlaylistPanel(renderer) { results.append(track) }
                } else {
                    for value in dict.values { traverse(node: value) }
                }
            } else if let array = node as? [Any] {
                for item in array { traverse(node: item) }
            }
        }

        traverse(node: json)
        return results
    }

    private func extractTrackFromPlaylistPanel(_ renderer: [String: Any]) -> Track? {
        guard let videoId = renderer["videoId"] as? String, !videoId.isEmpty else { return nil }
        let titleRuns = (renderer["title"] as? [String: Any])?["runs"] as? [[String: Any]]
        let title = titleRuns?.first?["text"] as? String ?? "Unknown Title"
        let artistRuns = (renderer["shortBylineText"] as? [String: Any])?["runs"] as? [[String: Any]]
        let artist = artistRuns?.first?["text"] as? String ?? "Featured Artist"
        let lengthRuns = (renderer["lengthText"] as? [String: Any])?["runs"] as? [[String: Any]]
        let durationText = lengthRuns?.first?["text"] as? String

        return Track(
            id: videoId,
            title: title,
            artist: artist,
            durationText: durationText,
            durationSeconds: parseDuration(from: durationText),
            artworkUrl: extractThumbnailUrl(from: renderer["thumbnail"] as? [String: Any])
        )
    }

    private func extractTrack(from renderer: [String: Any]) -> Track? {
        let videoId = (renderer["playlistItemData"] as? [String: Any])?["videoId"] as? String
            ?? extractVideoIdFromPlayButton(renderer)

        guard let id = videoId, !id.isEmpty else { return nil }
        let flexColumns = renderer["flexColumns"] as? [[String: Any]] ?? []
        guard !flexColumns.isEmpty else { return nil }

        let titleCol = flexColumns[0]["musicResponsiveListItemFlexColumnRenderer"] as? [String: Any]
        let titleRuns = (titleCol?["text"] as? [String: Any])?["runs"] as? [[String: Any]]
        let title = titleRuns?.first?["text"] as? String ?? "Unknown Title"

        var artist = "Featured Artist"
        var durationText: String? = nil

        if flexColumns.count > 1 {
            let artistCol = flexColumns[1]["musicResponsiveListItemFlexColumnRenderer"] as? [String: Any]
            let artistRuns = (artistCol?["text"] as? [String: Any])?["runs"] as? [[String: Any]] ?? []
            let stringRuns = artistRuns.compactMap { $0["text"] as? String }

            let meaningfulRuns = stringRuns.filter { run in
                let trimmed = run.trimmingCharacters(in: .whitespacesAndNewlines)
                return !categoryLabels.contains(trimmed) && !trimmed.contains(":") && !trimmed.contains("views") && !trimmed.isEmpty
            }

            if let firstArtist = meaningfulRuns.first { artist = firstArtist }
            if let lastRun = stringRuns.last, lastRun.contains(":") { durationText = lastRun }
        }

        return Track(
            id: id,
            title: title,
            artist: artist,
            durationText: durationText,
            durationSeconds: parseDuration(from: durationText),
            artworkUrl: extractThumbnailUrl(from: renderer["thumbnail"] as? [String: Any])
        )
    }

    private func extractVideoIdFromPlayButton(_ renderer: [String: Any]) -> String? {
        if let overlay = renderer["overlay"] as? [String: Any],
           let playBtn = overlay["musicItemThumbnailOverlayRenderer"] as? [String: Any],
           let content = playBtn["content"] as? [String: Any],
           let playBtnRenderer = content["musicPlayButtonRenderer"] as? [String: Any],
           let navEndpoint = playBtnRenderer["playNavigationEndpoint"] as? [String: Any],
           let watchEndpoint = navEndpoint["watchEndpoint"] as? [String: Any] {
            return watchEndpoint["videoId"] as? String
        }
        return nil
    }
}
