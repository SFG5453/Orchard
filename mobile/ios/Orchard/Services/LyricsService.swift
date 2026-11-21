import Foundation

public final class LyricsService {
    public static let shared = LyricsService()
    private let session = URLSession.shared

    private init() {}

    public func fetchLyrics(for track: Track) async -> [LyricLine] {
        var components = URLComponents(string: "https://lrclib.net/api/get")
        var queryItems: [URLQueryItem] = [
            URLQueryItem(name: "track_name", value: track.title),
            URLQueryItem(name: "artist_name", value: track.artist)
        ]
        if let duration = track.durationSeconds, duration > 0 {
            queryItems.append(URLQueryItem(name: "duration", value: String(Int(duration))))
        }
        components?.queryItems = queryItems

        guard let url = components?.url else { return [] }

        var request = URLRequest(url: url)
        request.setValue("Orchard-iOS/1.0", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 8.0

        do {
            let (data, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }

            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let syncedLyrics = json["syncedLyrics"] as? String, !syncedLyrics.isEmpty {
                    return LyricLine.parseLRC(syncedLyrics)
                }
                if let plainLyrics = json["plainLyrics"] as? String, !plainLyrics.isEmpty {
                    let lines = plainLyrics.components(separatedBy: .newlines).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                    return lines.enumerated().map { index, text in
                        LyricLine(startTime: Double(index * 4), text: text)
                    }
                }
            }
        } catch {
            print("[LyricsService] Error fetching lyrics: \(error.localizedDescription)")
        }

        return []
    }
}
