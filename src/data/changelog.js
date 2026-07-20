export const ORCHARD_RELEASES = [
  {
    version: '3.0.2',
    codename: 'Afterglow',
    date: 'July 19, 2026',
    sections: [
      {
        title: 'New & improved',
        items: [
          'Kawarp artwork warping is back for immersive backgrounds, with clearer Animated artwork and Artwork warp motion choices.',
          'Smart Crossfade better distinguishes real outros from quiet bridges and breakdowns that return to a chorus or drop.',
          'Animated artwork backgrounds use stronger blur and coverage to blend with the artwork-warp presentation.'
        ]
      },
      {
        title: 'Fixed',
        items: [
          'Manually uploaded YouTube Music songs now play with the signed-in account instead of reporting “This video is private.”',
          'Age-gated songs can fall back to a title-, artist-, and duration-matched music video when their audio stream is blocked.',
          'Unavailable, private, or removed tracks are removed from the queue during preload or track advance so playback continues.'
        ]
      }
    ]
  }
];

export const LATEST_CHANGELOG_VERSION = ORCHARD_RELEASES[0]?.version || '';
