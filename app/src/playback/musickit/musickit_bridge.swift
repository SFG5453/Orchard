/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

// Apple Music playback through MusicKit's ApplicationMusicPlayer, the only
// player that gets Apple's lossless streams. Exposed to C++ through
// musickit_bridge.h; all state lives on the main actor.

import Foundation
import MusicKit

public typealias OrchardMusicKitDone = @convention(c) (UnsafeMutableRawPointer?, Int32, UnsafePointer<CChar>?) -> Void

private struct Reply: @unchecked Sendable {
    let context: UnsafeMutableRawPointer?
    let done: OrchardMusicKitDone

    func send(_ error: String?) {
        guard let error else {
            done(context, 1, nil)
            return
        }
        error.withCString { done(context, 0, $0) }
    }
}

// Hands MusicKit Orchard's own developer token; the automatic one is rejected
// for some App IDs even with the MusicKit service enabled.
private final class OrchardTokenProvider: MusicUserTokenProvider, MusicDeveloperTokenProvider, @unchecked Sendable {
    private let token: String

    init(token: String) {
        self.token = token
        super.init()
    }

    func developerToken(options: MusicTokenRequestOptions) async throws -> String {
        token
    }
}

@MainActor private var providedToken = ""
@MainActor private var loadSerial: UInt64 = 0
@MainActor private var songDuration: Double = 0

private struct Superseded: Error {}

@MainActor private func current(_ serial: UInt64) throws {
    if serial != loadSerial {
        throw Superseded()
    }
}

@MainActor private func load(_ id: String, _ start: Double, _ paused: Bool, _ serial: UInt64) async -> String? {
    var status = MusicAuthorization.currentStatus
    if status == .notDetermined {
        status = await MusicAuthorization.request()
    }
    guard status == .authorized else {
        return "Orchard is not allowed to use Apple Music. Allow it in System Settings, Privacy & Security, Media & Apple Music."
    }
    do {
        guard try await MusicSubscription.current.canPlayCatalogContent else {
            return "The Apple Account signed in to Music on this Mac cannot play Apple Music catalog songs."
        }
        let request = MusicCatalogResourceRequest<Song>(matching: \.id, equalTo: MusicItemID(id))
        guard let song = try await request.response().items.first else {
            return "Apple Music has no catalog song \(id) in this storefront."
        }
        try current(serial)
        let player = ApplicationMusicPlayer.shared
        player.queue = ApplicationMusicPlayer.Queue(for: [song])
        player.state.repeatMode = MusicPlayer.RepeatMode.none
        songDuration = song.duration ?? 0
        try await player.prepareToPlay()
        try current(serial)
        if start > 0 {
            player.playbackTime = start
        }
        if !paused {
            try await player.play()
        }
        return nil
    } catch is Superseded {
        return ""
    } catch {
        return error.localizedDescription
    }
}

@_cdecl("orchard_musickit_available")
public func orchardMusicKitAvailable() -> Int32 {
    Bundle.main.bundleIdentifier == nil ? 0 : 1
}

@_cdecl("orchard_musickit_set_developer_token")
public func orchardMusicKitSetDeveloperToken(_ token: UnsafePointer<CChar>) {
    let value = String(cString: token)
    MainActor.assumeIsolated {
        guard !value.isEmpty, value != providedToken else { return }
        providedToken = value
        MusicDataRequest.tokenProvider = OrchardTokenProvider(token: value)
    }
}

@_cdecl("orchard_musickit_play")
public func orchardMusicKitPlay(_ songId: UnsafePointer<CChar>, _ start: Double, _ paused: Int32,
                                _ context: UnsafeMutableRawPointer?, _ done: OrchardMusicKitDone) {
    let id = String(cString: songId)
    let reply = Reply(context: context, done: done)
    MainActor.assumeIsolated {
        loadSerial &+= 1
        let serial = loadSerial
        Task { @MainActor in
            reply.send(await load(id, start, paused != 0, serial))
        }
    }
}

@_cdecl("orchard_musickit_resume")
public func orchardMusicKitResume() {
    MainActor.assumeIsolated {
        let serial = loadSerial
        Task { @MainActor in
            guard serial == loadSerial else { return }
            try? await ApplicationMusicPlayer.shared.play()
        }
    }
}

@_cdecl("orchard_musickit_pause")
public func orchardMusicKitPause() {
    MainActor.assumeIsolated {
        ApplicationMusicPlayer.shared.pause()
    }
}

@_cdecl("orchard_musickit_stop")
public func orchardMusicKitStop() {
    MainActor.assumeIsolated {
        loadSerial &+= 1
        songDuration = 0
        ApplicationMusicPlayer.shared.stop()
    }
}

@_cdecl("orchard_musickit_seek")
public func orchardMusicKitSeek(_ seconds: Double) {
    MainActor.assumeIsolated {
        ApplicationMusicPlayer.shared.playbackTime = max(0, seconds)
    }
}

@_cdecl("orchard_musickit_status")
public func orchardMusicKitStatus() -> Int32 {
    MainActor.assumeIsolated {
        switch ApplicationMusicPlayer.shared.state.playbackStatus {
        case .playing, .seekingForward, .seekingBackward: return 1
        case .paused: return 2
        case .interrupted: return 3
        default: return 0
        }
    }
}

@_cdecl("orchard_musickit_position")
public func orchardMusicKitPosition() -> Double {
    MainActor.assumeIsolated {
        ApplicationMusicPlayer.shared.playbackTime
    }
}

@_cdecl("orchard_musickit_duration")
public func orchardMusicKitDuration() -> Double {
    MainActor.assumeIsolated {
        songDuration
    }
}

@_cdecl("orchard_musickit_has_entry")
public func orchardMusicKitHasEntry() -> Int32 {
    MainActor.assumeIsolated {
        ApplicationMusicPlayer.shared.queue.currentEntry == nil ? 0 : 1
    }
}

@_cdecl("orchard_musickit_variant")
public func orchardMusicKitVariant() -> Int32 {
    MainActor.assumeIsolated {
        guard let variant = ApplicationMusicPlayer.shared.state.audioVariant else { return 0 }
        switch variant {
        case .lossyStereo: return 1
        case .lossless: return 2
        case .highResolutionLossless: return 3
        case .dolbyAtmos: return 4
        case .dolbyAudio: return 5
        case .spatialAudio: return 6
        default: return 0
        }
    }
}
