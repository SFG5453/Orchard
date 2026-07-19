export const ORCHARD_RELEASES = [
  {
    version: '3.0.1',
    date: 'July 19, 2026',
    sections: [
      {
        title: 'Fixed',
        items: [
          'Premium-only songs now recognize YouTube\'s HE-AAC streams and retry with the signed-in browser account when another player identity omits entitled playback formats.'
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';
