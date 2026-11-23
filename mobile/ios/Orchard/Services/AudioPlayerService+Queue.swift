import Foundation

extension AudioPlayerService {
    // MARK: - Queue & Playlist Navigation

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

    public func next() {
        guard !queue.isEmpty else { return }
        if queueIndex + 1 < queue.count {
            queueIndex += 1
            loadAndPlay(track: queue[queueIndex])
        } else if let current = currentTrack {
            // Autoplay next recommendations seamlessly without repeating or stalling
            self.isLoading = true
            Task { @MainActor in
                let recs = await YouTubeMusicService.shared.getRecommendations(for: current)
                let newTracks = recs.filter { rec in !self.queue.contains(where: { $0.id == rec.id }) }
                if !newTracks.isEmpty {
                    self.queue.append(contentsOf: newTracks)
                    self.queueIndex += 1
                    self.loadAndPlay(track: self.queue[self.queueIndex])
                } else if self.queue.count > 1 {
                    self.queueIndex = 0
                    self.loadAndPlay(track: self.queue[0])
                } else {
                    self.isLoading = false
                }
            }
        } else if queue.count > 1 {
            queueIndex = 0
            loadAndPlay(track: queue[0])
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
}
