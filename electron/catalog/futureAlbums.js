// Merges scheduled releases with live catalog metadata while preserving release timing.
import {
  releaseDaysFromToday,
  releaseTimingForDate,
  releaseTimingLabel
} from './releaseTiming.js';

const itunesApiOrigin = 'https://itunes.apple.com';
const artworkApiOrigin = 'https://artwork.boidu.dev';

export function createFutureAlbums({
  dedupeMediaItems,
  formatMillisDuration,
  futureTrackPlayableMatches,
  normalizedLooseText,
  textMatchesArtist,
  textMatchesTitle
}) {
  const artistFutureAlbumMetadataCache = new Map();
  const futureAlbumDetailsCache = new Map();
  const releaseRadarCache = new Map();

  async function fetchItunesJson(pathname, params = {}) {
    const url = new URL(pathname, itunesApiOrigin);
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, value);
      }
    }

    const response = await fetch(url);
    if (!response.ok) throw new Error(`iTunes API returned ${response.status}`);
    return response.json();
  }

  async function fetchEnhancedArtwork(title, artist) {
    if (!title || !artist) return '';

    try {
      const url = new URL('/', artworkApiOrigin);
      url.searchParams.set('s', title);
      url.searchParams.set('a', artist);
      const response = await fetch(url);
      if (!response.ok) throw new Error(`artwork API returned ${response.status}`);

      const data = await response.json();
      const result = Array.isArray(data) ? data[0] : data;
      if (
        result?.static &&
        textMatchesTitle(result.name || result.title, title) &&
        textMatchesArtist(result.artist || result.artistName, artist)
      ) {
        return result.static;
      }
    } catch (error) {
      console.warn(`Could not fetch enhanced artwork for ${artist} - ${title}: ${error.message}`);
    }

    return '';
  }

  function upscaleItunesArtworkUrl(url = '') {
    return String(url || '').replace(/\/\d+x\d+bb\.(jpg|png|webp)(?:\?.*)?$/i, '/1200x1200bb.$1'); // I hate writing regex.
  }

  async function futureAlbumArtwork(album) {
    const enhanced = await fetchEnhancedArtwork(album.collectionName, album.artistName);
    return enhanced || upscaleItunesArtworkUrl(album.artworkUrl100) || album.artworkUrl100 || '';
  }

  async function itunesArtistId(artistName) {
    if (!artistName) return null;

    const data = await fetchItunesJson('/search', {
      term: artistName,
      entity: 'musicArtist',
      country: 'US',
      limit: 10
    });

    const artists = Array.isArray(data.results) ? data.results : [];
    const exactMatch = artists.find((artist) => textMatchesTitle(artist.artistName, artistName));
    return exactMatch?.artistId || artists.find((artist) => textMatchesArtist(artist.artistName, artistName))?.artistId || null;
  }

  function dedupeItunesAlbums(albums = []) {
    const seen = new Set();

    return albums.filter((album) => {
      const key = normalizedLooseText(`${album.collectionName} ${album.artistName} ${album.trackCount || ''}`);
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  async function itunesAlbumsForArtist(artistName) {
    const artistId = await itunesArtistId(artistName);
    if (!artistId) return [];

    const data = await fetchItunesJson('/lookup', {
      id: artistId,
      entity: 'album',
      country: 'US',
      limit: 200,
      sort: 'recent'
    });

    return dedupeItunesAlbums((Array.isArray(data.results) ? data.results : [])
      .filter((item) => item.wrapperType === 'collection')
      .filter((album) => textMatchesArtist(album.artistName, artistName))
      .sort((left, right) => Date.parse(right.releaseDate || '') - Date.parse(left.releaseDate || '')));
  }

  async function futureItunesAlbums(artistName) {
    const now = Date.now();
    return (await itunesAlbumsForArtist(artistName))
      .filter((album) => Date.parse(album.releaseDate || '') > now)
      .sort((left, right) => Date.parse(left.releaseDate || '') - Date.parse(right.releaseDate || ''));
  }

  function futureAlbumCard(album, artwork) {
    const releaseDate = album.releaseDate || '';
    const year = releaseDate.match(/\b\d{4}\b/)?.[0] || '';

    return {
      id: null,
      type: 'future_album',
      browseId: `itunes:${album.collectionId}`,
      title: album.collectionName || 'Upcoming Album',
      subtitle: 'Upcoming Album',
      artist: album.artistName || '',
      artists: [album.artistName].filter(Boolean),
      album: '',
      albumId: null,
      duration: '',
      durationSeconds: 0,
      explicit: album.collectionExplicitness === 'explicit',
      year,
      views: '',
      itemCount: album.trackCount ? `${album.trackCount} tracks` : '',
      thumbnail: artwork,
      externalUrl: album.collectionViewUrl || '',
      futureAlbumId: album.collectionId || null,
      futureAlbumReleaseDate: releaseDate
    };
  }

  function isPlaceholderFutureAlbumTrack(track) {
    const title = track?.trackName || '';
    return !title || /^track\s+\d+$/i.test(title);
  }

  function normalizeFutureAlbumTrack(track, album, artwork, index = 0) {
    const trackNumber = Number(track.trackNumber || index + 1);
    const placeholder = isPlaceholderFutureAlbumTrack(track);
    const title = track.trackName || `Track ${trackNumber}`;
    const durationSeconds = Math.round(Number(track.trackTimeMillis || 0) / 1000) || 0;

    return {
      id: null,
      type: 'future_track',
      title,
      subtitle: album.artistName || '',
      artists: [track.artistName || album.artistName].filter(Boolean),
      artist: track.artistName || album.artistName || '',
      album: album.collectionName || track.collectionName || '',
      albumId: null,
      futureAlbumId: album.collectionId || null,
      futureAlbumUrl: album.collectionViewUrl || '',
      futureAlbumReleaseDate: album.releaseDate || '',
      albumThumbnail: artwork,
      duration: placeholder ? '' : formatMillisDuration(track.trackTimeMillis),
      durationSeconds: placeholder ? 0 : durationSeconds,
      explicit: track.trackExplicitness === 'explicit',
      year: '',
      views: '',
      itemCount: '',
      thumbnail: '',
      index: String(trackNumber),
      futurePlaceholder: placeholder,
      unplayable: true
    };
  }

  async function futureAlbumLookup(collectionId) {
    const data = await fetchItunesJson('/lookup', {
      id: collectionId,
      entity: 'song',
      country: 'US'
    });

    const results = Array.isArray(data.results) ? data.results : [];
    return {
      album: results.find((item) => item.wrapperType === 'collection') || null,
      tracks: results
        .filter((item) => item.wrapperType === 'track')
        .sort((left, right) => Number(left.trackNumber || 0) - Number(right.trackNumber || 0))
    };
  }

  async function resolveItunesAlbum(collectionId) {
    const id = String(collectionId || '').trim();
    if (!id) return null;

    const data = await fetchItunesJson('/lookup', {
      id,
      country: 'US'
    });
    return (Array.isArray(data.results) ? data.results : [])
      .find((item) => item.wrapperType === 'collection') || null;
  }

  function formatReleaseDate(value = '') {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' }).format(date);
  }

  function releaseRadarCard(album, artwork, followedArtistName) {
    const releaseDate = album.releaseDate || '';
    const timing = releaseTimingForDate(releaseDate);
    const days = releaseDaysFromToday(releaseDate);
    const future = days > 0;

    return {
      ...futureAlbumCard(album, artwork),
      type: future ? 'future_album' : 'release_album',
      subtitle: future ? 'Upcoming Album' : 'Album',
      album: album.collectionName || '',
      artist: album.artistName || followedArtistName || '',
      artists: [album.artistName || followedArtistName].filter(Boolean),
      followedArtistName,
      releaseDate,
      releaseDateText: formatReleaseDate(releaseDate),
      releaseTiming: timing,
      releaseTimingLabel: releaseTimingLabel(timing, days),
      releaseDaysFromToday: days,
      releaseResolved: false
    };
  }

  function totalDurationText(tracks = []) {
    const totalSeconds = tracks.reduce((total, track) => total + Number(track.durationSeconds || 0), 0);
    if (!totalSeconds) return '';

    const minutes = Math.round(totalSeconds / 60);
    return `${minutes} minute${minutes === 1 ? '' : 's'}`;
  }

  function futureAlbumDetailFromTracks(album, artwork, tracks) {
    const normalizedTracks = tracks.map((track, index) => normalizeFutureAlbumTrack(track, album, artwork, index));
    const trackCount = Number(album.trackCount || normalizedTracks.length || 0);
    const releaseDate = album.releaseDate || '';

    return {
      kind: 'album',
      browseId: `itunes:${album.collectionId}`,
      futureAlbumId: album.collectionId || null,
      futureAlbumUrl: album.collectionViewUrl || '',
      title: album.collectionName || 'Upcoming Album',
      subtitle: 'Upcoming Album',
      artist: album.artistName || '',
      explicit: album.collectionExplicitness === 'explicit',
      year: releaseDate.match(/\b\d{4}\b/)?.[0] || '',
      itemCount: trackCount ? `${trackCount} tracks` : '',
      totalDuration: totalDurationText(normalizedTracks),
      views: '',
      description: '',
      thumbnail: artwork,
      releaseDate,
      releaseDateText: formatReleaseDate(releaseDate),
      copyright: album.copyright || '',
      tracks: normalizedTracks,
      sections: []
    };
  }

  async function artistFutureAlbumMetadata(artistName, maxAlbums = 6) {
    const cacheKey = normalizedLooseText(artistName);
    if (!cacheKey) return { albums: [], details: [] };
    if (artistFutureAlbumMetadataCache.has(cacheKey)) return artistFutureAlbumMetadataCache.get(cacheKey);

    const metadataPromise = (async () => {
      try {
        const albums = (await futureItunesAlbums(artistName)).slice(0, maxAlbums);
        const withArtwork = await Promise.all(albums.map(async (album) => ({
          album,
          artwork: await futureAlbumArtwork(album),
          lookup: await futureAlbumLookup(album.collectionId)
        })));
        const details = withArtwork.map(({ album, artwork, lookup }) => futureAlbumDetailFromTracks(lookup.album || album, artwork, lookup.tracks));

        return {
          albums: withArtwork.map(({ album, artwork }) => futureAlbumCard(album, artwork)),
          details
        };
      } catch (error) {
        console.warn(`Could not hydrate future iTunes albums for ${artistName}: ${error.message}`);
        return { albums: [], details: [] };
      }
    })();

    artistFutureAlbumMetadataCache.set(cacheKey, metadataPromise);
    return metadataPromise;
  }

  async function releaseRadarForArtists(artists = [], options = {}) {
    const pastDays = Number(options.pastDays) || 45;
    const futureDays = Number(options.futureDays) || 120;
    const maxAlbumsPerArtist = Number(options.maxAlbumsPerArtist) || 8;
    const artistNames = [...new Set(
      artists
        .map((artist) => String(artist?.name || artist?.title || artist || '').trim())
        .filter(Boolean)
    )].slice(0, 24);
    const cacheKey = `${artistNames.map(normalizedLooseText).join('|')}:${pastDays}:${futureDays}:${maxAlbumsPerArtist}`;
    if (!artistNames.length) return [];
    if (releaseRadarCache.has(cacheKey)) return releaseRadarCache.get(cacheKey);

    const radarPromise = (async () => {
      const now = Date.now();
      const minDate = now - pastDays * 86400000;
      const maxDate = now + futureDays * 86400000;
      const groups = await Promise.all(artistNames.map(async (artistName) => {
        try {
          const albums = (await itunesAlbumsForArtist(artistName))
            .filter((album) => {
              const releaseTime = Date.parse(album.releaseDate || '');
              return Number.isFinite(releaseTime) && releaseTime >= minDate && releaseTime <= maxDate;
            })
            .slice(0, maxAlbumsPerArtist);

          return Promise.all(albums.map(async (album) =>
            releaseRadarCard(album, await futureAlbumArtwork(album), artistName)
          ));
        } catch (error) {
          console.warn(`Could not load release radar for ${artistName}: ${error.message}`);
          return [];
        }
      }));
      const seen = new Set();

      return groups.flat()
        .filter((release) => {
          const key = normalizedLooseText(`${release.title} ${release.artist} ${release.releaseDate}`);
          if (!key || seen.has(key)) return false;
          seen.add(key);
          return true;
        })
        .sort((left, right) =>
          left.releaseDaysFromToday - right.releaseDaysFromToday ||
          left.artist.localeCompare(right.artist) ||
          left.title.localeCompare(right.title)
        );
    })();

    releaseRadarCache.set(cacheKey, radarPromise);
    return radarPromise;
  }

  function releaseAlbumMatches(release, album) {
    if (!release || !album) return false;

    const cleanReleaseTitle = normalizedLooseText(release.title)
      .replace(/\b(single|ep|album)\b/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    const cleanAlbumTitle = normalizedLooseText(album.title || album.collectionName)
      .replace(/\b(single|ep|album)\b/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();

    return Boolean(cleanReleaseTitle && cleanReleaseTitle === cleanAlbumTitle) &&
      textMatchesArtist(album.artist || album.subtitle || album.artistName, release.artist);
  }

  function mergeFutureAlbumsIntoSections(sections, futureAlbums = []) {
    if (!futureAlbums.length) return sections;

    const albumSectionIndex = sections.findIndex((section) => /albums?/i.test(section.title));
    const existingTitles = new Set(
      (albumSectionIndex >= 0 ? sections[albumSectionIndex].items || [] : [])
        .map((item) => normalizedLooseText(item.title))
        .filter(Boolean)
    );
    const albums = futureAlbums.filter((album) => {
      const title = normalizedLooseText(album.title);
      if (!title || existingTitles.has(title)) return false;
      existingTitles.add(title);
      return true;
    });
    if (!albums.length) return sections;

    if (albumSectionIndex < 0) {
      return [
        ...sections,
        {
          key: 'future-itunes-albums',
          title: 'Albums',
          items: albums
        }
      ];
    }

    return sections.map((section, index) => {
      if (index !== albumSectionIndex) return section;
      return {
        ...section,
        items: dedupeMediaItems([...albums, ...section.items])
      };
    });
  }

  function futureAlbumPlayableCandidates(sections = []) {
    return dedupeMediaItems(
      sections
        .flatMap((section) => section.items || [])
        .filter((item) => item.id && item.title)
    );
  }

  function hydrateFutureTrackPlayable(track, candidates = []) {
    if (track.futurePlaceholder || track.id) return track;

    const match = candidates.find((candidate) => futureTrackPlayableMatches(track, candidate));
    if (!match) return track;

    return {
      ...track,
      id: match.id,
      browseId: match.browseId || null,
      browsePayload: match.browsePayload || null,
      type: match.type || 'track',
      musicVideoType: match.musicVideoType || '',
      isAudioOnly: Boolean(match.isAudioOnly),
      artists: match.artists?.length ? match.artists : track.artists,
      artistBrowseIds: match.artistBrowseIds || [],
      thumbnail: match.thumbnail || '',
      explicit: match.explicit ?? track.explicit,
      duration: track.duration || match.duration || '',
      durationSeconds: track.durationSeconds || match.durationSeconds || 0,
      unplayable: false
    };
  }

  function hydrateFutureAlbumDetails(details = [], sections = []) {
    const candidates = futureAlbumPlayableCandidates(sections);

    return details.map((detail) => ({
      ...detail,
      tracks: detail.tracks.map((track) => hydrateFutureTrackPlayable(track, candidates)),
      totalDuration: detail.totalDuration || totalDurationText(detail.tracks)
    }));
  }

  function cacheFutureAlbumDetails(details = []) {
    for (const detail of details) {
      const collectionId = String(detail.futureAlbumId || '').trim();
      if (!collectionId) continue;
      futureAlbumDetailsCache.set(collectionId, detail);
      futureAlbumDetailsCache.set(`itunes:${collectionId}`, detail);
    }
  }

  function itunesCollectionIdFromPayload(payload = {}) {
    const browseId = String(payload.browseId || payload.futureAlbumId || '').trim();
    const match = browseId.match(/(?:itunes:)?(\d+)/i);
    return match?.[1] || '';
  }

  async function resolveFutureAlbum(payload = {}) {
    const collectionId = itunesCollectionIdFromPayload(payload);
    if (!collectionId) throw new Error('Missing future album collection id.');

    const cached = futureAlbumDetailsCache.get(collectionId) || futureAlbumDetailsCache.get(`itunes:${collectionId}`);
    if (cached) return cached;

    const { album, tracks } = await futureAlbumLookup(collectionId);
    if (!album) throw new Error('Future album was not found in iTunes.');

    const artwork = await futureAlbumArtwork(album);
    const detail = futureAlbumDetailFromTracks(album, artwork, tracks);
    cacheFutureAlbumDetails([detail]);
    return detail;
  }

  return {
    artistFutureAlbumMetadata,
    cacheFutureAlbumDetails,
    hydrateFutureAlbumDetails,
    mergeFutureAlbumsIntoSections,
    releaseAlbumMatches,
    releaseRadarForArtists,
    resolveFutureAlbum,
    resolveItunesAlbum
  };
}
