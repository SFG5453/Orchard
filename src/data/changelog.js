export const ORCHARD_RELEASES = [
  {
    version: '3.0.2',
    codename: 'Afterglow',
    date: 'July 19, 2026',
    sections: [
      {
        title: 'Fixed',
        items: [
          'Smart crossfade now keeps playing when a quiet section ramps back up later in the song.',
          'Unavailable tracks are removed from the queue when Orchard advances to them.',
          "Reintroduced Kawarp For Immersive Backgrounds"
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';
