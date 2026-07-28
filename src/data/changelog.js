export const ORCHARD_RELEASES = [
  {
    version: '4.0.0-beta.1',
    codename: 'Popularly Mpeg',
    date: 'July 27, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Smart Crossfade can now render compatible beat-matched transitions with a native, pitch-preserving WSOLA engine. Mixes align on a shared beat grid, keep bass handoffs steady, and animate artwork changes in fullscreen.',
          'The optional Continuous queue combines playback history, the current track, and upcoming songs in one layout. It supports jumping to or removing entries in the queue page, right panel, and fullscreen player, and keeps shared history synchronized in listening parties.',
          'Graphics settings now offer Automatic and Integrated GPU modes, with low-power GPU selection and guided app restarts after a mode change.',
          'You can opt into beta updates from Settings. Beta builds are delivered through GitHub Releases while the stable channel remains unchanged.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'YouTube Brand Account switching now handles expected Google redirects, detects account and delegated-session changes, refreshes the active profile immediately, and remembers the selected browser identity across restarts.',
          'Search now supplements broad results with dedicated song and artist matches, then ranks exact matches by relevance and catalog popularity.',
          'Smart Crossfade now prevents competing transition engines and queue edits from corrupting an active mix, avoids comb filtering at handoffs, preserves supported audio processing, and bounds long pre-rolls and overlaps.',
          'Immersive backgrounds stop unnecessary GPU rendering when animation is disabled, playback is paused, or Orchard is hidden, and Linux restores the original GPU environment when returning to Automatic mode.',
          'Continuous queues keep skipped tracks out of playback history and preserve host-controlled navigation and shared history during listening parties.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Updated Quasar to 2.23.2.'
        ]
      }
    ]
  },
  {
    version: '3.2.2',
    codename: 'Jambs Explosive',
    date: 'July 27, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'You can now opt into beta updates from Settings. Beta builds are delivered through GitHub Releases, while the stable update channel remains unchanged.'
        ]
      }
    ]
  },
  {
    version: '3.2.1',
    codename: 'Hounds Vicar',
    date: 'July 23, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Support is now a full-width, two-pane workspace with more room to compose reports and review existing conversations.',
          'Sanitized diagnostics can now be collected, reviewed, and refreshed before submission, so the private attachment contains exactly the snapshot shown in Orchard.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Linux media controls now publish their complete MPRIS interface and initial playback state before announcing the service, preventing Plasma and other clients from discovering an empty player.',
          'Pausing, seeking, skipping, or refreshing playback now cleanly cancels an active crossfade. In-flight transitions can no longer restart after cancellation, and volume changes remain synchronized across both decks.',
          'Resuming a persisted shuffled queue now preserves its saved order, while playing a track from history no longer enqueues the rest of listening history.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Resolved all dependency audit findings and removed the unused Node/Jimp image-processing chain from Orchard\'s browser palette extraction.'
        ]
      }
    ]
  },
  {
    version: '3.2.0',
    codename: 'Astrology Tenn',
    date: 'July 23, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Smart Crossfade analysis now prioritizes current and upcoming tracks, runs work concurrently without duplicate jobs, retries temporary network failures, and caches validated native or worker results.',
          'Best Mix now favors local audio analysis, uses confidence-aware BPM and key metadata, and automatically re-sorts when tracks are added to the queue.',
          'Beat-matched transitions now align incoming drops more accurately with finer tempo adjustments and safer Web Audio automation.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Song cache writes no longer interrupt playback when storage is slow or unavailable. Orchard also limits cache write lag, removes abandoned partial files, and prevents duplicate writes.',
          'Desktop media widgets now refresh track metadata after automatic track changes without sending redundant updates.',
          'Playback proxy retries now preserve the requested stream format instead of silently substituting a different format.',
          'The Support view\'s Current issues link now opens the public Orchard issue tracker at github.com/SFG5453/Orchard/issues.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Updated the maintainer email used by Linux packages.'
        ]
      }
    ]
  },
  {
    version: '3.1.0',
    codename: 'Overt Japes',
    date: 'July 21, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Improved automated transitions with dynamic long-preroll crossfade logic and smarter cue point detection.',
          'Restored visual mixing animations and adapted Smart Crossfade overlay UI for Orchard 3.x.'
        ]
      }
    ]
  },
  {
    version: '3.0.3',
    codename: 'Boulevard Heiresses',
    date: 'July 20, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Julian Ramierez joins Orchard as a tester and the new mobile app developer. (coming soon!)',
          'Best Mix now uses catalog BPM and key metadata from GetSongBPM to supplement local analysis, enabling Best Mix for tracks that can\'t be analyzed locally.',
          'Parallel range-based audio fetching for faster analysis performance.',
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Fixed YouTube Music API requests failing with 401 errors by adding automatic re-authentication and expanding proxy support to all /youtubei/ endpoints.',
          'Replaced the fake Youtube Shuffle All button with a real shuffle button in the Library songs menu.',
          'Fixed BPM and confidence handling across multiple Best Mix modules.',
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';
