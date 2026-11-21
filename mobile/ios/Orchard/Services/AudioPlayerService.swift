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
    private var queueIndex: Int = 0
    private var loadTask: Task<Void, Never>?
    private var lyricsTask: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    private var currentItemObserver: NSObjectProtocol?

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

    private func setupAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .moviePlayback, policy: .longFormAudio)
            try session.setActive(true)
        } catch {
            print("[AudioPlayerService] Failed to set up AVAudioSession: \(error.localizedDescription)")
        }
    }

    // MARK: - Queue & Playback

    public func play(track: Track) {
        self.queue = [track]
        self.queueIndex = 0
        loadAndPlay(track: track)
    }

    public func playQueue(tracks: [Track], startIndex: Int = 0) {
        guard !tracks.isEmpty, startIndex < tracks.count else { return }
        self.queue = tracks
        self.queueIndex = startIndex
        loadAndPlay(track: tracks[startIndex])
    }

    public func addToQueueNext(track: Track) {
        if queue.isEmpty || currentTrack == nil {
            play(track: track)
            return
        }
        let insertIndex = min(queueIndex + 1, queue.count)
        queue.insert(track, at: insertIndex)
        updateRemoteCommandStates()
        YouTubeMusicService.shared.prefetchStream(for: track.id, quality: selectedQuality)
    }

    public func addToQueue(track: Track) {
        if queue.isEmpty || currentTrack == nil {
            play(track: track)
            return
        }
        queue.append(track)
        updateRemoteCommandStates()
    }

    public func removeFromQueue(at offsets: IndexSet) {
        queue.remove(atOffsets: offsets)
        if let current = currentTrack, let newIdx = queue.firstIndex(where: { $0.id == current.id }) {
            queueIndex = newIdx
        } else if queueIndex >= queue.count {
            queueIndex = max(0, queue.count - 1)
        }
        updateRemoteCommandStates()
    }

    public func moveInQueue(from source: IndexSet, to destination: Int) {
        queue.move(fromOffsets: source, toOffset: destination)
        if let current = currentTrack, let newIdx = queue.firstIndex(where: { $0.id == current.id }) {
            queueIndex = newIdx
        }
        updateRemoteCommandStates()
    }

    public func clearQueue() {
        if let current = currentTrack {
            queue = [current]
            queueIndex = 0
        } else {
            queue = []
            queueIndex = 0
        }
        updateRemoteCommandStates()
    }

    private func loadAndPlay(track: Track, seekTo: TimeInterval = 0) {
        // Cancel prior streaming & lyrics load tasks to clean up pipeline and prevent stale track racing
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

        // Record in Recently Played
        if let idx = recentlyPlayed.firstIndex(where: { $0.id == track.id }) {
            recentlyPlayed.remove(at: idx)
        }
        recentlyPlayed.insert(track, at: 0)
        if recentlyPlayed.count > 40 {
            recentlyPlayed.removeLast()
        }

        updateRemoteCommandStates()

        // Pre-fetch next track in queue for instant transition
        if queueIndex + 1 < queue.count {
            let nextTrack = queue[queueIndex + 1]
            YouTubeMusicService.shared.prefetchStream(for: nextTrack.id, quality: selectedQuality)
        }

        // Fetch synced lyrics concurrently
        lyricsTask = Task {
            let lyrics = await LyricsService.shared.fetchLyrics(for: track)
            if !Task.isCancelled && self.currentTrack?.id == track.id {
                self.currentLyrics = lyrics
            }
        }

        // Resolve stream (Video HLS manifest or Direct Audio) and start playback
        loadTask = Task {
            do {
                let streamUrl: URL
                if let directUrl = track.streamUrl {
                    streamUrl = directUrl
                } else if self.isVideoMode {
                    streamUrl = try await YouTubeMusicService.shared.resolveVideoStreamUrl(for: track)
                } else {
                    streamUrl = try await YouTubeMusicService.shared.resolveStreamUrl(for: track.id, quality: selectedQuality)
                }

                guard !Task.isCancelled, self.currentTrack?.id == track.id else { return }
                startPlayback(with: streamUrl, for: track, seekTo: seekTo)
            } catch {
                if !Task.isCancelled {
                    print("[AudioPlayerService] Error resolving stream: \(error.localizedDescription)")
                    self.isLoading = false
                }
            }
        }
    }

    private func startPlayback(with url: URL, for track: Track, seekTo: TimeInterval = 0) {
        cleanUpTimeObserver()

        // Clean up previous item observer to prevent memory leak and duplicate notifications
        if let obs = currentItemObserver {
            NotificationCenter.default.removeObserver(obs)
            currentItemObserver = nil
        }

        let asset = AVURLAsset(url: url)
        let playerItem = AVPlayerItem(asset: asset)
        // High quality buffering buffer: 30.0s forward buffer prevents stalling without quality drop
        playerItem.preferredForwardBufferDuration = 30.0
        playerItem.canUseNetworkResourcesForLiveStreamingWhilePaused = true

        currentItemObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: playerItem,
            queue: .main
        ) { [weak self] _ in
            self?.next()
        }

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
    }

    // Toggle between Audio Mode and Music Video Mode seamlessly
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

    // Switch Audio Quality smoothly without interrupting playback
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

    public func togglePlayPause() {
        guard let player = player else {
            if let track = currentTrack {
                loadAndPlay(track: track, seekTo: currentTime)
            }
            return
        }
        if isPlaying {
            player.pause()
            isPlaying = false
            savePlaybackState()
        } else {
            player.play()
            isPlaying = true
        }
        updatePlaybackRateInNowPlaying()
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

    public func next() {
        guard !queue.isEmpty else { return }
        if queueIndex + 1 < queue.count {
            queueIndex += 1
            loadAndPlay(track: queue[queueIndex])
        } else if queue.count > 1 {
            // Loop back to start
            queueIndex = 0
            loadAndPlay(track: queue[0])
        } else if let current = currentTrack {
            // Auto-fetch related recommendations for continuous flow
            Task {
                if let related = try? await YouTubeMusicService.shared.search(query: current.artist), !related.isEmpty {
                    let nextTracks = related.filter { $0.id != current.id }
                    if let first = nextTracks.first {
                        self.queue.append(contentsOf: nextTracks)
                        self.queueIndex += 1
                        self.loadAndPlay(track: first)
                    }
                }
            }
        }
    }

    public func previous() {
        guard !queue.isEmpty else { return }
        if currentTime > 3.0 {
            seek(to: 0)
        } else if queueIndex > 0 {
            queueIndex -= 1
            loadAndPlay(track: queue[queueIndex])
        } else if queue.count > 1 {
            queueIndex = queue.count - 1
            loadAndPlay(track: queue[queueIndex])
        } else {
            seek(to: 0)
        }
    }

    @objc private func playerItemDidReachEnd() {
        next()
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
                    if dur.isFinite && dur > 0 {
                        self.duration = dur
                    }
                }
                self.updateCurrentLyricIndex(for: seconds)
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

    // MARK: - Lock Screen & Dynamic Island (MPRemoteCommandCenter)

    private func setupRemoteCommands() {
        let commandCenter = MPRemoteCommandCenter.shared()

        // Play / Pause / Toggle
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            if !self.isPlaying {
                self.togglePlayPause()
                return .success
            }
            return .commandFailed
        }

        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            if self.isPlaying {
                self.togglePlayPause()
                return .success
            }
            return .commandFailed
        }

        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }

        // Forward / Next Track
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            self.next()
            return .success
        }

        // Backward / Previous Track
        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            guard let self = self else { return .commandFailed }
            self.previous()
            return .success
        }

        // Disable Skip 15s so Lock Screen, Notification Center & Dynamic Island display Previous Track ⏮ & Next Track ⏭
        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false

        // Seek Bar (Scrubbing from Lock Screen / Dynamic Island)
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self = self,
                  let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            self.seek(to: positionEvent.positionTime)
            return .success
        }

        updateRemoteCommandStates()
    }

    private func updateRemoteCommandStates() {
        let commandCenter = MPRemoteCommandCenter.shared()
        commandCenter.playCommand.isEnabled = true
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.skipForwardCommand.isEnabled = false
        commandCenter.skipBackwardCommand.isEnabled = false
        commandCenter.changePlaybackPositionCommand.isEnabled = true
    }

    private func updateNowPlayingInfo(for track: Track) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: 0.0,
            MPMediaItemPropertyPlaybackDuration: max(duration, 1.0),
            MPNowPlayingInfoPropertyPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyPlaybackQueueCount: max(queue.count, 1),
            MPNowPlayingInfoPropertyPlaybackQueueIndex: queueIndex
        ]
        if let album = track.album {
            info[MPMediaItemPropertyAlbumTitle] = album
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info

        artworkTask?.cancel()
        if let artworkUrl = track.artworkUrl {
            artworkTask = Task {
                if let (data, _) = try? await URLSession.shared.data(from: artworkUrl),
                   let image = UIImage(data: data) {
                    guard !Task.isCancelled, self.currentTrack?.id == track.id else { return }
                    let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    var currentInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                    currentInfo[MPMediaItemPropertyArtwork] = artwork
                    MPNowPlayingInfoCenter.default().nowPlayingInfo = currentInfo
                }
            }
        }
    }

    private func updatePlaybackRateInNowPlaying() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func updateNowPlayingElapsed() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
        info[MPMediaItemPropertyPlaybackDuration] = max(duration, 1.0)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
