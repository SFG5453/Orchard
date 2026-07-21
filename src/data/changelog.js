export const ORCHARD_RELEASES = [
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
