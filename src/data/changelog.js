export const ORCHARD_RELEASES = [
  {
    version: '3.0.0',
    codename: 'Copper Canopy',
    date: 'July 18, 2026',
    sections: [
      {
        title: 'Added',
        items: [
          'Smart Crossfade now uses cached native musical analysis for beat grids, downbeats, phrases, key, intro and outro timing, loudness, energy, and vocal density, then builds DJ-like transitions with eight-bar intro pre-rolls, audible incoming beds, phrase-long same-beat blends, confidence-aware matching, staged EQ handoffs, and track changes timed to musical dominance.',
          'Typing while viewing an album or playlist now opens a track finder that jumps to the closest match after a short pause and stays open for navigation.',
          'Queue items can now be removed individually from the right panel and fullscreen player.',
          'Release Radar now automatically tracks Official Artist Channels subscribed to on YouTube instead of requiring a separate manual artist list.',
          'Last.fm accounts can now be connected from Settings to send now-playing updates and scrobble eligible tracks through a credential-protected Worker.',
          'Universal macOS builds are now available as unsigned zip downloads for Apple Silicon and Intel Macs.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Immersive backgrounds now use GPU-rendered ambient artwork, artwork-derived color motion, smooth track crossfades, and native animated artwork playback with HLS fallback.',
          'Artists now shows Official Artist Channel subscriptions, Songs and Albums load saved YouTube Music library items, and Recently Played opens listening history instead of the Queue.',
          'The Library shelf now appears first on Home, replacing the separate Top Picks for You row.',
          'Electron, renderer, and native audio source now use explicit process and feature boundaries, with documented packaged-resource and IPC contracts.',
          'Orchard is now available under the MIT License.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'YouTube history now obtains playback tracking anonymously and submits it with the browser account, avoiding rejected authenticated player requests.',
          'Playback now keeps signed-in sessions intact and retries with the guest player client when YouTube rejects an authenticated player request with a credential-shaped 401 or 403 response.',
          'Songs and Albums now load when YouTube Music returns its newer library layout.',
          'Playback no longer remains locked in buffering after a stream stops, and replaying a failed stream now requests a fresh source.',
          'YouTube Music sign-in now persists reliably when Orchard is closed and reopened.',
          'Support reports can now be connected to GitHub so their public issues show the reporter as the author instead of an unknown Orchard user.',
          'Best mix now orders songs from measured audio compatibility instead of grouping matching artists or albums, and leaves unanalyzed songs in place.',
          'Smart Crossfade now recognizes strong later-song silence breaks as musical mix-out points, and its mix indicator reaches the end of the progress bar.',
          'Previous now stays within the active playlist, walks backward through its tracks, and restarts the first song at the playlist boundary.'
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';
