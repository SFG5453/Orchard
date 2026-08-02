export const ORCHARD_RELEASES = [
  {
    version: '4.1.0',
    codename: 'Flirty Gateau',
    date: 'August 2, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Beat-matched transitions now ride a low-pass filter down the outgoing track instead of ducking its mid band. The corner starts above hearing and sweeps toward the bass crossover across the overlap, so the outgoing song thins out and recedes rather than simply getting quieter, and the movement itself is what covers the seam. Vocal activity still steers how far the sweep travels, and the low end is left alone until the existing bass swap hands it over.'
        ]
      }
    ]
  },
  {
    version: '4.0.0',
    codename: 'Nowadays Wrinkly',
    date: 'August 2, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'The new Canopy layout preset introduces a docked player, a compact sticky header, and denser rails to give the page more vertical room. Grove remains the default, and upgrading from 3.x opens a one-time prompt to try Canopy without changing anything else about your setup.',
          'Smart Crossfade has been rebuilt on neural analysis. ONNX models handle beat tracking and vocal separation, Essentia calibrates transition confidence, and a confidence-aware policy decides when a beat-matched blend is appropriate at all.',
          'Beat-matched transitions are rendered by a native, pitch-preserving WSOLA engine. Mixes align on a shared beat grid, hold the low end flat through the overlap before swapping decks, and measure transition length in beats rather than seconds.',
          'Vocal activity is now measured per frame instead of per track, so incoming vocals are kept out of the overlap and transitions no longer talk over themselves.',
          'The optional Continuous queue combines listening history, the current track, and upcoming songs in a single layout. Entries can be jumped to or removed from the queue page, right panel, and fullscreen player, and shared history stays synchronized during listening parties.',
          'Orchard can connect to Spotify and play a track\'s animated Canvas loop in place of static album art, falling back to the usual artwork whenever a Canvas is unavailable.',
          'Graphics settings offer Automatic and Integrated GPU modes, with low-power GPU selection and a guided restart after a mode change.',
          'The welcome flow has been redesigned around interactive layout, artwork, playback, and pairing choices.',
          'Navigation, queue changes, and home shelves now animate between states, and fullscreen artwork crossfades along with the music.',
          'Search supplements broad results with dedicated song and artist matches, then ranks exact matches by relevance and catalog popularity.',
          'The queue and your last open page are persisted through the main process, so a force quit no longer loses them.',
          'YouTube Brand Account switching handles Google\'s redirects, detects account and delegated-session changes, refreshes the active profile immediately, and remembers the selected browser identity across restarts.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Gapless handoffs are now reserved for albums actually being played in order. Two tracks from the same album that meet through shuffle, a playlist, a hand-built queue, or a Best Mix reorder are treated as a mix and crossfaded.',
          'Smart Crossfade mutes the incoming deck before play, supports handoffs for music videos, prevents competing transition engines and queue edits from corrupting an active mix, avoids comb filtering at handoffs, preserves supported audio processing, and bounds long pre-rolls and overlaps.',
          'The Canopy transition overlay appears again during smart crossfades, and the bass handover point has been adjusted.',
          'Packages built against a system Electron runtime now locate the audio analyzer and both neural models, which had silently fallen back to heuristic analysis and disabled beat-matched transitions.',
          'Fixed a crash on launch on Apple Silicon Macs by enforcing proper ad-hoc code signing.',
          'Fixed YouTube searches occasionally failing with a "Cannot read properties of undefined" error, and patched album metadata parsing for recent upstream YouTube changes.',
          'Last.fm API calls use POST for authenticated requests and forward the user-agent.',
          'Immersive backgrounds stop rendering on the GPU when animation is disabled, playback is paused, or Orchard is hidden, and Linux restores the original GPU environment when returning to Automatic mode.',
          'Continuous queues keep skipped tracks out of listening history and preserve host-controlled navigation and shared history during listening parties.',
          'Custom artist sound effects play again after the content security policy was widened to allow their audio data.',
          'Account summaries prioritize channel handles and exclude subscriber counts from navigation headers, and long paused lyric lines no longer show a premature ellipsis.',
          'Arch Linux packages generate valid version strings.'
        ]
      },
      {
        title: 'Changed',
        items: [
          'Discord Rich Presence no longer offers the Orchard/YouTube Music card name option; the presence buttons already cover it.'
        ]
      },
      {
        title: 'Maintenance',
        items: [
          'Orchard is relicensed to AGPL-3.0-or-later as of 4.0.0.',
          'Updated Quasar to 2.23.2, and added a continuous integration workflow that runs the test suite and build on Node 26.'
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';
