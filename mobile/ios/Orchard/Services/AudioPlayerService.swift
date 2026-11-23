import Foundation
import AVFoundation
import MediaPlayer
import UIKit

@MainActor
public final class AudioPlayerService: NSObject, ObservableObject {
    public static let shared = AudioPlayerService()

    @Published public var currentTrack: Track?
    @Published public var isPlaying: Bool = false
    @Published public var currentTime: TimeInterval = 0
    @Published public var duration: TimeInterval = 0
    @Published public var isLoading: Bool = false
    @Published public var queue: [Track] = []
    @Published public var currentLyrics: [LyricLine] = []
    @Published public var currentLyricIndex: Int? = nil
    @Published public var selectedQuality: AudioQuality = .high
    @Published public var isVideoMode: Bool = false
    @Published public private(set) var recentlyPlayed: [Track] = []

    private var player: AVPlayer?
    public var avPlayer: AVPlayer? { player }

    private var timeObserver: Any?
    var queueIndex: Int = 0
    private var loadTask: Task<Void, Never>?
    private var lyricsTask: Task<Void, Never>?
    var artworkTask: Task<Void, Never>?
    private var preloadTask: Task<Void, Never>?
    private var currentItemObserver: NSObjectProtocol?
    private var failItemObserver: NSObjectProtocol?
    private var stallObserver: NSObjectProtocol?
    private var isCompletingTrack: Bool = false
    private var preloadedNextItem: AVPlayerItem?
    private var preloadedNextTrackId: String?

    private let lastTrackKey = "orchard_last_track"
    private let lastPositionKey = "orchard_last_position"
    private let lastDurationKey = "orchard_last_duration"
    private let lastQueueKey = "orchard_last_queue"
    private let lastQueueIndexKey = "orchard_last_queue_index"

    override private init() {
        super.init()
        setupAudioSession()
        setupRemoteCommands()
        setupLifecycleObservers()
        restorePlaybackState()
    }

    private func setupLifecycleObservers() {
        NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.savePlaybackState()
        }
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.savePlaybackState()
        }
    }

    public func savePlaybackState() {
        guard let track = currentTrack else { return }
        if let encoded = try? JSONEncoder().encode(track) {
            UserDefaults.standard.set(encoded, forKey: lastTrackKey)
        }
        UserDefaults.standard.set(currentTime, forKey: lastPositionKey)
        UserDefaults.standard.set(duration, forKey: lastDurationKey)
        if let encodedQueue = try? JSONEncoder().encode(queue) {
            UserDefaults.standard.set(encodedQueue, forKey: lastQueueKey)
        }
        UserDefaults.standard.set(queueIndex, forKey: lastQueueIndexKey)
    }

    private func restorePlaybackState() {
        guard let data = UserDefaults.standard.data(forKey: lastTrackKey),
              let track = try? JSONDecoder().decode(Track.self, from: data) else {
            return
        }
        self.currentTrack = track
        self.currentTime = UserDefaults.standard.double(forKey: lastPositionKey)
        let savedDuration = UserDefaults.standard.double(forKey: lastDurationKey)
        self.duration = savedDuration > 0 ? savedDuration : (track.durationSeconds ?? 0)

        if let queueData = UserDefaults.standard.data(forKey: lastQueueKey),
           let savedQueue = try? JSONDecoder().decode([Track].self, from: queueData),
           !savedQueue.isEmpty {
            self.queue = savedQueue
        } else {
            self.queue = [track]
        }
        self.queueIndex = UserDefaults.standard.integer(forKey: lastQueueIndexKey)
        self.isPlaying = false
        self.isLoading = false

        updateNowPlayingInfo(for: track)
        updatePlaybackRateInNowPlaying()
        lyricsTask = Task {
            let lyrics = await LyricsService.shared.fetchLyrics(for: track)
            if !Task.isCancelled && self.currentTrack?.id == track.id {
                self.currentLyrics = lyrics
            }
        }
    }

    // MARK: - Playback Core

    func loadAndPlay(track: Track, seekTo: TimeInterval = 0) {
        loadTask?.cancel()
        lyricsTask?.cancel()

        self.currentTrack = track
        self.isLoading = true
        self.isPlaying = false
        self.currentTime = seekTo
        self.duration = track.durationSeconds ?? 0
        self.currentLyrics = []
        self.currentLyricIndex = nil
        savePlaybackState()

        if let idx = recentlyPlayed.firstIndex(where: { $0.id == track.id }) {
            recentlyPlayed.remove(at: idx)
        }
        recentlyPlayed.insert(track, at: 0)
        if recentlyPlayed.count > 40 {
            recentlyPlayed.removeLast()
        }

        updateRemoteCommandStates()

        lyricsTask = Task {
            let lyrics = await LyricsService.shared.fetchLyrics(for: track)
            if !Task.isCancelled && self.currentTrack?.id == track.id {
                self.currentLyrics = lyrics
            }
        }

        if let preloaded = preloadedNextItem, preloadedNextTrackId == track.id, seekTo == 0 {
            preloadedNextItem = nil
            preloadedNextTrackId = nil
            startPreloadedPlayback(with: preloaded, for: track)
            return
        }

        loadTask = Task {
            do {
                var playableTrack = track
                if track.id.hasPrefix("imported_") {
                    if let resolved = await YouTubeMusicService.shared.resolveImportedTrack(title: track.title, artist: track.artist) {
                        playableTrack = resolved
                        await MainActor.run {
                            self.currentTrack = resolved
                            if let idx = self.queue.firstIndex(where: { $0.id == track.id }) {
                                self.queue[idx] = resolved
                            }
                        }
                    }
                }

                let streamUrl: URL
                if let directUrl = playableTrack.streamUrl {
                    streamUrl = directUrl
                } else if self.isVideoMode {
                    streamUrl = try await YouTubeMusicService.shared.resolveVideoStreamUrl(for: playableTrack)
                } else {
                    streamUrl = try await YouTubeMusicService.shared.resolveStreamUrl(for: playableTrack.id, quality: selectedQuality)
                }

                guard !Task.isCancelled, self.currentTrack?.id == playableTrack.id || self.currentTrack?.id == track.id else { return }
                startPlayback(with: streamUrl, for: playableTrack, seekTo: seekTo)
            } catch {
                if !Task.isCancelled {
                    print("[AudioPlayerService] Error resolving stream: \(error.localizedDescription)")
                    self.isLoading = false
                }
            }
        }
    }

    private func startPreloadedPlayback(with playerItem: AVPlayerItem, for track: Track) {
        cleanUpTimeObserver()
        cleanUpItemObservers()
        attachItemObservers(to: playerItem)

        if player == nil {
            player = AVPlayer(playerItem: playerItem)
            player?.automaticallyWaitsToMinimizeStalling = true
        } else {
            player?.replaceCurrentItem(with: playerItem)
        }

        player?.play()
        self.isPlaying = true
        self.isLoading = false

        updateNowPlayingInfo(for: track)
        setupTimeObserver()
        preloadNextTrack()
    }

    private func cleanUpItemObservers() {
        if let obs = currentItemObserver {
            NotificationCenter.default.removeObserver(obs)
            currentItemObserver = nil
        }
        if let obs = failItemObserver {
            NotificationCenter.default.removeObserver(obs)
            failItemObserver = nil
        }
        if let obs = stallObserver {
            NotificationCenter.default.removeObserver(obs)
            stallObserver = nil
        }
    }

    private func attachItemObservers(to item: AVPlayerItem) {
        currentItemObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            self?.handleTrackCompletion()
        }

        failItemObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            self?.handleTrackCompletion()
        }

        stallObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemPlaybackStalled,
            object: item,
            queue: .main
        ) { [weak self] _ in
            self?.player?.play()
        }
    }

    private func preloadNextTrack() {
        preloadTask?.cancel()
        preloadTask = Task {
            let nextIndex = queueIndex + 1
            var candidateTrack: Track? = nil

            if nextIndex < queue.count {
                candidateTrack = queue[nextIndex]
            } else if let current = currentTrack {
                let recs = await YouTubeMusicService.shared.getRecommendations(for: current)
                if let firstRec = recs.first {
                    await MainActor.run {
                        let newTracks = recs.filter { rec in !self.queue.contains(where: { $0.id == rec.id }) }
                        if !newTracks.isEmpty {
                            self.queue.append(contentsOf: newTracks)
                        }
                    }
                    candidateTrack = firstRec
                }
            }

            guard let trackToPreload = candidateTrack, !Task.isCancelled else { return }

            var playableTrack = trackToPreload
            if trackToPreload.id.hasPrefix("imported_") {
                if let resolved = await YouTubeMusicService.shared.resolveImportedTrack(title: trackToPreload.title, artist: trackToPreload.artist) {
                    playableTrack = resolved
                }
            }

            guard let streamUrl = try? await YouTubeMusicService.shared.resolveStreamUrl(for: playableTrack.id, quality: selectedQuality),
                  !Task.isCancelled else { return }

            let asset = AVURLAsset(url: streamUrl)
            let item = AVPlayerItem(asset: asset)
            item.preferredForwardBufferDuration = 30.0
            item.canUseNetworkResourcesForLiveStreamingWhilePaused = true

            await MainActor.run {
                self.preloadedNextItem = item
                self.preloadedNextTrackId = trackToPreload.id
            }
        }
    }

    private func handleTrackCompletion() {
        guard !isCompletingTrack else { return }
        isCompletingTrack = true
        self.next()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            self?.isCompletingTrack = false
        }
    }

    private func startPlayback(with url: URL, for track: Track, seekTo: TimeInterval = 0) {
        cleanUpTimeObserver()
        cleanUpItemObservers()

        let asset = AVURLAsset(url: url)
        let playerItem = AVPlayerItem(asset: asset)
        playerItem.preferredForwardBufferDuration = 30.0
        playerItem.canUseNetworkResourcesForLiveStreamingWhilePaused = true

        attachItemObservers(to: playerItem)

        if player == nil {
            player = AVPlayer(playerItem: playerItem)
            player?.automaticallyWaitsToMinimizeStalling = true
        } else {
            player?.replaceCurrentItem(with: playerItem)
        }

        if seekTo > 0 {
            let cmTime = CMTime(seconds: seekTo, preferredTimescale: 600)
            player?.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
        }

        player?.play()
        self.isPlaying = true
        self.isLoading = false

        updateNowPlayingInfo(for: track)
        setupTimeObserver()
        preloadNextTrack()
    }

    public func toggleVideoMode() {
        self.isVideoMode.toggle()
        guard let track = currentTrack else { return }
        let currentPos = currentTime
        self.isLoading = true

        loadTask?.cancel()
        loadTask = Task {
            do {
                let streamUrl: URL
                if self.isVideoMode {
                    streamUrl = try await YouTubeMusicService.shared.resolveVideoStreamUrl(for: track)
                } else {
                    streamUrl = try await YouTubeMusicService.shared.resolveStreamUrl(for: track.id, quality: selectedQuality)
                }
                guard !Task.isCancelled, self.currentTrack?.id == track.id else { return }
                self.startPlayback(with: streamUrl, for: track, seekTo: currentPos)
            } catch {
                if !Task.isCancelled {
                    print("[AudioPlayerService] Failed to toggle video mode: \(error.localizedDescription)")
                    self.isLoading = false
                }
            }
        }
    }

    public func switchQuality(to quality: AudioQuality) {
        guard quality != selectedQuality else { return }
        self.selectedQuality = quality
        guard let track = currentTrack else { return }
        guard !isVideoMode else { return }

        let currentPos = currentTime
        self.isLoading = true

        loadTask?.cancel()
        loadTask = Task {
            do {
                let newUrl = try await YouTubeMusicService.shared.resolveStreamUrl(for: track.id, quality: quality)
                guard !Task.isCancelled, self.currentTrack?.id == track.id else { return }
                self.startPlayback(with: newUrl, for: track, seekTo: currentPos)
            } catch {
                guard !Task.isCancelled else { return }
                print("[AudioPlayerService] Failed to switch quality: \(error.localizedDescription)")
                self.isLoading = false
            }
        }
    }

    // MARK: - Play / Pause / Seek Controls

    public func playCurrent() {
        try? AVAudioSession.sharedInstance().setActive(true, options: [])
        guard let player = player else {
            if let track = currentTrack {
                loadAndPlay(track: track, seekTo: currentTime)
            }
            return
        }
        player.play()
        self.isPlaying = true
        updatePlaybackRateInNowPlaying()
        savePlaybackState()
    }

    public func pauseCurrent() {
        player?.pause()
        self.isPlaying = false
        updatePlaybackRateInNowPlaying()
        savePlaybackState()
    }

    public func togglePlayPause() {
        if isPlaying {
            pauseCurrent()
        } else {
            playCurrent()
        }
    }

    public func seek(to time: TimeInterval) {
        guard let player = player else {
            self.currentTime = time
            savePlaybackState()
            return
        }
        let cmTime = CMTime(seconds: time, preferredTimescale: 600)
        player.seek(to: cmTime) { [weak self] _ in
            self?.currentTime = time
            self?.updateNowPlayingElapsed()
            self?.updateCurrentLyricIndex(for: time)
            self?.savePlaybackState()
        }
    }

    // MARK: - Time Observation

    private func setupTimeObserver() {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserver = player?.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self = self else { return }
            let seconds = CMTimeGetSeconds(time)
            if seconds.isFinite && seconds >= 0 {
                self.currentTime = seconds
                if let item = self.player?.currentItem {
                    let dur = CMTimeGetSeconds(item.duration)
                    if dur.isFinite && dur > 0 && abs(self.duration - dur) > 0.5 {
                        self.duration = dur
                        self.updateNowPlayingDuration(dur)
                    }
                }
                self.updateCurrentLyricIndex(for: seconds)

                // Robust safety net: If track naturally reaches end and AVPlayer stalls at EOF
                if self.duration > 5.0, seconds >= self.duration - 0.35 {
                    if self.player?.timeControlStatus != .playing {
                        self.handleTrackCompletion()
                    }
                }
            }
        }
    }

    private func cleanUpTimeObserver() {
        if let observer = timeObserver {
            player?.removeTimeObserver(observer)
            timeObserver = nil
        }
    }

    private func updateCurrentLyricIndex(for time: TimeInterval) {
        guard !currentLyrics.isEmpty else {
            currentLyricIndex = nil
            return
        }

        var foundIndex: Int? = nil
        for (index, line) in currentLyrics.enumerated() {
            if line.startTime <= time {
                foundIndex = index
            } else {
                break
            }
        }
        if currentLyricIndex != foundIndex {
            currentLyricIndex = foundIndex
        }
    }
}
