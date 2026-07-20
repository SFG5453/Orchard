export const ORCHARD_RELEASES = [
  {
    version: '3.0.3',
    codename: 'Boulevard Heiresses',
    date: 'July 20, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Julian Ramierez joins Orchard as a tester and the new mobile app developer. (coming soon!)',
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Replaced the fake Youtube Shuffle All button with a real shuffle button in the Library songs menu.'
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';
