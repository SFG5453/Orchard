export const ORCHARD_RELEASES = [
  {
    version: '4.0.0-beta.4',
    codename: 'Murmurer Violated',
    date: 'July 31, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'The Canopy layout design has been updated to be much more roomier.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Fixed an issue causing Orchard to crash on launch on Apple Silicon Macs by enforcing proper ad-hoc code signing.',
          'Smart Crossfade now mutes the incoming deck before play and correctly supports handoffs for music videos.',
          'Fixed a bug where YouTube searches would occasionally fail with a "Cannot read properties of undefined" error.'
        ]
      }
    ]
  },
  {
    version: '4.0.0-beta.3',
    codename: 'Mushroom Novitiates',
    date: 'July 31, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Orchard can now connect to Spotify and play the animated Canvas loop for a track in place of static album art, falling back to the usual artwork whenever a Canvas is unavailable.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Packages built against a system Electron runtime now locate the audio analyzer and both neural models, which had silently fallen back to heuristic analysis and disabled beat-matched transitions.',
          'Custom artist sound effects play again after the content security policy was widened to allow their audio data.'
        ]
      }
    ]
  },
  {
    version: '4.0.0-beta.2',
    codename: 'Resells Citibank',
    date: 'July 30, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'The new Canopy layout preset introduces a docked player and a compact header to improve vertical screen space, alongside a redesigned onboarding flow for interface selection.',
          'Smart Crossfade replaces WSOLA and heuristic metering with ONNX neural models for beat tracking and vocal separation, using Essentia to calibrate transition confidence.',
          'Smart transitions now evaluate vocal activity per frame to keep incoming vocals out of the overlap, halving transition lengths and counting them in beats.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Last.fm API calls correctly use POST for authenticated requests and forward the user-agent.',
          'Arch Linux packages now generate valid version strings, and Windows prerelease builds use GitHub-safe update manifests.',
          'The interface prioritizes channel handles in account summaries, resolves premature ellipsis on paused long lyric lines, and adds fluid transition animations for navigation and queue changes.',
          'Album metadata parsing has been patched to handle recent upstream YouTube changes.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Orchard has been relicensed to AGPL-3.0-or-later.'
        ]
      }
    ]
  },
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
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';
