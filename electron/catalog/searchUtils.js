// Provides catalog search normalization, ranking, de-duplication, and metadata merging.
import { isKnownArtistItem, releaseTypeFromText } from './musicItemTypes.js';

export function createSearchUtils({
  asText,
  bestThumbnail,
  hasExplicitBadge,
  normalizedLooseText,
  shelfItems,
  textParts
}) {
  const trackPopularityCache = new Map();

  function searchCreditMatches(item, query) {
    const normalizedQuery = normalizedLookupText(query);
    if (!normalizedQuery) return false;

    const credits = [
      item.artist,
      ...(item.artists || []),
    ]
      .filter(Boolean);

    return credits.some((credit) => normalizedLookupText(credit) === normalizedQuery);
  }

  function normalizeSearchCardShelf(section) {
    const endpoint = section.endpoint || section.on_tap;
    const payload = endpoint?.payload || {};
    const pageType = payload.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType || '';
    const subtitle = asText(section.subtitle);

    return {
      id: payload.videoId || null,
      browseId: payload.browseId || null,
      browsePayload: payload.browseId ? { ...payload } : null,
      type: pageType === 'MUSIC_PAGE_TYPE_ARTIST' || /^artist\b/i.test(subtitle)
        ? 'artist'
        : pageType === 'MUSIC_PAGE_TYPE_ALBUM'
          ? 'album'
          : pageType === 'MUSIC_PAGE_TYPE_PLAYLIST'
            ? 'playlist'
            : 'media',
      musicVideoType: '',
      isAudioOnly: false,
      title: asText(section.title) || 'Untitled',
      subtitle,
      artists: [],
      artistBrowseIds: [],
      album: '',
      albumId: null,
      duration: '',
      durationSeconds: 0,
      explicit: hasExplicitBadge(section),
      year: '',
      views: '',
      itemCount: textParts(subtitle).slice(1).join(' • '),
      releaseType: pageType === 'MUSIC_PAGE_TYPE_ALBUM' ? releaseTypeFromText(subtitle) : '',
      thumbnail: bestThumbnail(section.thumbnail),
      index: ''
    };
  }

  function searchSectionKeyForItem(item, sourceTitle = '') {
    const source = normalizedLookupText(sourceTitle);
    const type = normalizedLookupText(item?.type);

    if (/artists?/.test(source) || type === 'artist' || type === 'library artist') return 'artists';
    if (/albums?|singles?|eps?/.test(source) || type === 'album') return 'albums';
    if (/playlists?/.test(source) || type === 'playlist') return 'playlists';
    if (/videos?/.test(source) || type === 'video') return 'videos';
    if (/songs?|tracks?/.test(source) || type === 'song' || type === 'track') return 'songs';
    if (type === 'non music track') return 'videos';
    return '';
  }

  function normalizeSearchContents(search) {
    const buckets = {
      songs: [],
      videos: [],
      albums: [],
      artists: [],
      playlists: []
    };

    for (const section of search.contents || []) {
      const sectionTitle = asText(section.title || section.header?.title || section.header?.strapline);

      if (section?.type === 'MusicCardShelf') {
        const card = normalizeSearchCardShelf(section);
        const cardKey = searchSectionKeyForItem(card, sectionTitle);
        if (cardKey) buckets[cardKey].push(card);

        for (const item of shelfItems(section)) {
          const key = searchSectionKeyForItem(item, sectionTitle);
          if (key) buckets[key].push(item);
        }

        continue;
      }

      for (const item of shelfItems(section)) {
        const key = searchSectionKeyForItem(item, sectionTitle);
        if (key) buckets[key].push(item);
      }
    }

    return buckets;
  }

  function normalizeSearch(search, query = '') {
    const sections = [
      ['songs', 'Songs'],
      ['videos', 'Videos'],
      ['albums', 'Albums'],
      ['artists', 'Artists'],
      ['playlists', 'Playlists']
    ];
    const contentBuckets = search.contents?.length ? normalizeSearchContents(search) : null;
    const normalizedSections = sections
      .map(([key, title]) => ({
        key,
        title,
        items: dedupeMediaItems(contentBuckets ? contentBuckets[key] : shelfItems(search[key]))
          .filter((item) => key !== 'artists' || isKnownArtistItem(item))
      }))
      .filter((section) => section.items.length > 0);
    const artistSection = normalizedSections.find((section) => section.key === 'artists');
    const hasExactArtist = artistSection?.items.some((item) =>
      normalizedLookupText(item.title) === normalizedLookupText(query)
    );

    return {
      filters: search.filters || [],
      didYouMean: asText(search.did_you_mean),
      showingResultsFor: asText(search.showing_results_for),
      message: asText(search.message),
      sections: normalizedSections
        .map((section) => ({
          ...section,
          items: (['songs', 'videos', 'albums'].includes(section.key) &&
            (hasExactArtist || section.items.some((item) => searchCreditMatches(item, query)))
            ? section.items.filter((item) => searchCreditMatches(item, query))
            : section.items)
        }))
        .filter((section) => section.items.length > 0)
    };
  }

  function mergeSearchResults(primary = { sections: [] }, supplements = []) {
    const sectionOrder = ['songs', 'videos', 'albums', 'artists', 'playlists'];
    const sections = new Map(
      (primary.sections || []).map((section) => [section.key, { ...section, items: [...(section.items || [])] }])
    );

    for (const result of supplements) {
      for (const supplement of result?.sections || []) {
        const current = sections.get(supplement.key);
        sections.set(supplement.key, {
          ...(current || supplement),
          items: dedupeMediaItems([
            ...(supplement.items || []),
            ...(current?.items || [])
          ])
        });
      }
    }

    return {
      ...primary,
      sections: [...sections.values()]
        .filter((section) => section.items.length > 0)
        .sort((left, right) => {
          const leftIndex = sectionOrder.indexOf(left.key);
          const rightIndex = sectionOrder.indexOf(right.key);
          return (leftIndex < 0 ? sectionOrder.length : leftIndex) -
            (rightIndex < 0 ? sectionOrder.length : rightIndex);
        })
    };
  }

  async function cachedTrackPopularity(videoId, loadTrackPopularity) {
    if (!videoId || !loadTrackPopularity) return 0;
    if (trackPopularityCache.has(videoId)) return trackPopularityCache.get(videoId);

    const pending = Promise.resolve(loadTrackPopularity(videoId))
      .then((value) => {
        const popularity = Number(value || 0);
        return Number.isFinite(popularity) && popularity > 0 ? popularity : 0;
      })
      .catch(() => {
        trackPopularityCache.delete(videoId);
        return 0;
      });
    trackPopularityCache.set(videoId, pending);

    if (trackPopularityCache.size > 500) {
      const oldest = trackPopularityCache.keys().next().value;
      trackPopularityCache.delete(oldest);
    }

    return pending;
  }

  async function hydrateExactTrackPopularity(result, query, loadTrackPopularity) {
    if (!loadTrackPopularity) return result;

    const normalizedQuery = normalizedLookupText(query);
    if (!normalizedQuery) return result;

    const songs = result.sections?.find((section) => section.key === 'songs');
    const candidates = (songs?.items || [])
      .filter((item) => item.id && normalizedLookupText(item.title) === normalizedQuery)
      .slice(0, 12);
    if (!candidates.length) return result;

    const popularity = new Map(await Promise.all(candidates.map(async (item) => [
      item.id,
      await cachedTrackPopularity(item.id, loadTrackPopularity)
    ])));

    return {
      ...result,
      sections: result.sections.map((section) => section.key !== 'songs' ? section : {
        ...section,
        items: section.items.map((item) => popularity.has(item.id)
          ? { ...item, searchPopularity: popularity.get(item.id) }
          : item)
      })
    };
  }

  async function searchCatalog(collection, query, filter = 'songs', loadTrackPopularity) {
    if (filter !== 'all') {
      const search = await collection.search(query, { type: filter.slice(0, -1) });
      const result = normalizeSearch(search, query);
      return filter === 'songs'
        ? hydrateExactTrackPopularity(result, query, loadTrackPopularity)
        : result;
    }

    const [primary, songs, artists] = await Promise.allSettled([
      collection.search(query),
      collection.search(query, { type: 'song' }),
      collection.search(query, { type: 'artist' })
    ]);
    if (primary.status === 'rejected') throw primary.reason;

    const supplements = [songs, artists]
      .filter((result) => result.status === 'fulfilled' && result.value)
      .map((result) => normalizeSearch(result.value, query));

    const result = mergeSearchResults(normalizeSearch(primary.value, query), supplements);
    return hydrateExactTrackPopularity(result, query, loadTrackPopularity);
  }

  async function completeFilteredSearchItems(search, maxRequests = 6) {
    const items = search.contents?.flatMap((section) => shelfItems(section)) || [];
    let page = search;
    let requestCount = 0;

    while ((page?.has_continuation || page?.contents?.continuation) && requestCount < maxRequests) {
      requestCount += 1;

      try {
        page = await page.getContinuation();
      } catch {
        break;
      }

      const pageItems = page?.contents?.contents ? shelfItems(page.contents) : [];
      if (!pageItems.length) break;
      items.push(...pageItems);
    }

    return items;
  }

  function durationSeconds(item = {}) {
    const direct = Number(item.durationSeconds || item.duration?.seconds || 0);
    if (direct > 0) return Math.round(direct);
    const parts = String(item.duration || '').trim().split(':').map(Number);
    if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
    return parts.reduce((total, part) => total * 60 + part, 0);
  }

  function durationCompatible(candidate, target) {
    const candidateDuration = durationSeconds(candidate);
    const targetDuration = durationSeconds(target);
    if (!candidateDuration || !targetDuration) return true;
    return Math.abs(candidateDuration - targetDuration) <= 5;
  }

  async function searchTrackAlbumMetadata(collection, track, artistName, artistBrowseId = '', options = {}) {
    if (!collection?.search || !track?.title || (track.album && track.albumId && track.duration)) return null;

    const targetArtist = artistName || track.artist || track.artists?.[0] || '';
    const query = [targetArtist, track.title].filter(Boolean).join(' ').trim();
    if (!query) return null;

    try {
      const search = await collection.search(query, { type: 'song' });
      if (!search) return null;
      const candidates = dedupeMediaItems([
        ...shelfItems(search?.songs),
        ...await completeFilteredSearchItems(search, options.maxContinuationRequests ?? 2)
      ]);
      const targetTitle = normalizedLooseText(track.title);
      const scored = candidates
        .filter((item) =>
          item?.id &&
          (item.album || item.albumId || item.duration) &&
          normalizedLooseText(item.title) === targetTitle &&
          durationCompatible(item, track)
        )
        .map((item) => {
          const sameId = item.id === track.id;
          const artistMatch = artistMatchesSearchItem(item, targetArtist, artistBrowseId) ||
            (track.artistBrowseIds || []).some((id) => item.artistBrowseIds?.includes(id));
          if (!sameId && !artistMatch) return { item, score: -Infinity };

          return {
            item,
            score: (sameId ? 8 : 0) +
              (artistMatch ? 4 : 0) +
              (item.album || item.albumId ? 3 : 0) +
              (item.isAudioOnly || item.musicVideoType === 'MUSIC_VIDEO_TYPE_ATV' ? 1 : 0)
          };
        })
        .sort((left, right) => right.score - left.score);

      return scored[0]?.score > 0 ? scored[0].item : null;
    } catch (error) {
      console.warn(`Could not search album metadata for ${track.title}: ${error.message}`);
      return null;
    }
  }

  function artistMatchesSearchItem(item, artistName, artistBrowseId = '') {
    const itemArtistBrowseIds = (item.artistBrowseIds || []).filter(Boolean);
    if (artistBrowseId && itemArtistBrowseIds.length) {
      return itemArtistBrowseIds.includes(artistBrowseId);
    }

    const target = normalizedLooseText(artistName);
    const credits = [
      item.artist,
      ...(item.artists || [])
    ];
    const subtitleCredits = String(item.subtitle || '')
      .split(/[•·,]/)
      .map((value) => value.trim())
      .filter(Boolean);

    return [...credits, ...subtitleCredits]
      .some((value) => normalizedLooseText(value) === target);
  }

  function dedupeMediaItems(items = []) {
    const seen = new Set();
    const deduped = [];

    for (const item of items) {
      const key = item.browseId ||
        item.albumId ||
        item.id ||
        normalizedLooseText([item.title, item.artist, item.subtitle, item.year].filter(Boolean).join(' '));
      if (!key || seen.has(key)) continue;
      seen.add(key);
      deduped.push(item);
    }

    return deduped;
  }

  function trackMetadataKeys(item) {
    const keys = [];
    if (item?.id) keys.push(`id:${item.id}`);

    const title = normalizedLooseText(item?.title);
    const artists = [
      item?.artist,
      ...(item?.artists || [])
    ]
      .map(normalizedLooseText)
      .filter(Boolean);

    if (title) {
      for (const artist of artists.length ? artists : ['']) {
        keys.push(`text:${title}:${artist}`);
      }
    }

    return [...new Set(keys)];
  }

  function trackMetadataKey(item) {
    return trackMetadataKeys(item)[0] || '';
  }

  function trackMetadataMatches(left, right) {
    const leftKeys = trackMetadataKeys(left);
    const rightKeys = new Set(trackMetadataKeys(right));
    return leftKeys.some((key) => rightKeys.has(key));
  }

  function normalizedTrackArtists(item = {}) {
    return [
      item.artist,
      ...(item.artists || [])
    ]
      .map(normalizedLooseText)
      .filter(Boolean);
  }

  function futureTrackPlayableMatches(track, candidate) {
    const title = normalizedLooseText(track?.title);
    if (!title || title !== normalizedLooseText(candidate?.title)) return false;

    const trackArtists = normalizedTrackArtists(track);
    const candidateArtists = new Set(normalizedTrackArtists(candidate));
    if (!trackArtists.length || !candidateArtists.size) return false;

    return trackArtists.some((artist) => candidateArtists.has(artist));
  }

  function isSelfTitledTrackRelease(track = {}) {
    const title = normalizedLooseText(track.title);
    const album = normalizedLooseText(track.album);
    return Boolean(title && album && title === album);
  }

  function mergeTrackMetadata(track, candidates = []) {
    if (!trackMetadataKeys(track).length) return track;

    const matches = candidates.filter((item) =>
      item !== track &&
      trackMetadataMatches(track, item) &&
      (item.album || item.albumId || item.duration || item.futureAlbumId)
    );
    const futureAlbumMatch = matches.find((item) =>
      item.futureAlbumId &&
      item.album &&
      (!track.album || isSelfTitledTrackRelease(track))
    );
    const match = futureAlbumMatch || matches[0];
    if (!match) return track;

    const redirectSingleToFutureAlbum = Boolean(futureAlbumMatch && isSelfTitledTrackRelease(track));

    return {
      ...track,
      album: redirectSingleToFutureAlbum ? match.album : (track.album || match.album || ''),
      albumId: redirectSingleToFutureAlbum ? null : (track.albumId || match.albumId || null),
      futureAlbumId: track.futureAlbumId || match.futureAlbumId || null,
      futureAlbumUrl: track.futureAlbumUrl || match.futureAlbumUrl || '',
      futureAlbumReleaseDate: track.futureAlbumReleaseDate || match.futureAlbumReleaseDate || '',
      albumThumbnail: track.albumThumbnail || match.albumThumbnail || '',
      duration: track.duration || match.duration || '',
      durationSeconds: track.durationSeconds || match.durationSeconds || 0
    };
  }

  function isSingleOrEpRelease(item) {
    const releaseText = [
      item?.subtitle,
      item?.year,
      item?.itemCount
    ].filter(Boolean).join(' ');
    const trackCount = Number(releaseText.match(/(\d+)\s+(?:songs?|tracks?)\b/i)?.[1] || 0);
    if (trackCount >= 7) return false;

    return /(?:^|[^\w])(single|ep)(?:[^\w]|$)/i.test(releaseText);
  }

  function isAlbumReleaseItem(item) {
    const pageType = item?.browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    const browseId = item?.browsePayload?.browseId || item?.browseId || item?.albumId || item?.id || '';

    return pageType === 'MUSIC_PAGE_TYPE_ALBUM' ||
      item?.type === 'album' ||
      browseId.startsWith('MPR') ||
      browseId.startsWith('FEmusic_library_privately_owned_release');
  }

  function artistBrowseSectionItemMatches(item, artistName, artistBrowseId, sectionTitle = '') {
    if (artistMatchesSearchItem(item, artistName, artistBrowseId)) return true;
    if (!/albums?|singles?|eps?/i.test(sectionTitle)) return false;
    if (!isAlbumReleaseItem(item)) return false;

    const credits = [
      item?.artist,
      ...(item?.artists || [])
    ]
      .map(normalizedLooseText)
      .filter(Boolean);

    return credits.length === 0;
  }

  function itemMatchesReleaseSection(item, sectionTitle = '') {
    if (/singles?|eps?/i.test(sectionTitle)) return isSingleOrEpRelease(item);
    if (/albums?/i.test(sectionTitle)) return !isSingleOrEpRelease(item);
    return true;
  }

  async function searchArtistShelfFallback(collection, artistName, artistBrowseId, sectionTitle) {
    if (!artistName) return [];

    const wantsVideo = /videos?/i.test(sectionTitle);
    const wantsSongs = /top songs/i.test(sectionTitle);
    const wantsSingle = /singles?/i.test(sectionTitle);
    const wantsAlbum = /albums?/i.test(sectionTitle);
    const type = wantsVideo ? 'video' : wantsSongs ? 'song' : 'album';
    const queries = wantsVideo
      ? [`${artistName} videos`, artistName]
      : wantsSongs
        ? [`${artistName} songs`, artistName]
      : wantsSingle
        ? [`${artistName} singles`, artistName]
        : [`${artistName} albums`, artistName];
    const items = [];
    let fallbackError = null;

    for (const query of queries) {
      try {
        const search = await collection.search(query, { type });
        if (!search) continue;
        items.push(...await completeFilteredSearchItems(search));
      } catch (error) {
        fallbackError ||= error;
        if (/Cannot read properties of null/.test(error.message || '')) break;
      }
    }
    if (fallbackError && !items.length) {
      console.warn(`Could not search ${sectionTitle} fallback for ${artistName}: ${fallbackError.message}`);
    }

    return dedupeMediaItems(items.filter((item) => {
      if (!artistMatchesSearchItem(item, artistName, artistBrowseId)) return false;
      if (wantsSingle) return isSingleOrEpRelease(item);
      if (wantsAlbum) return !isSingleOrEpRelease(item);
      return true;
    }));
  }

  function normalizedLookupText(value = '') {
    return String(value)
      .toLowerCase()
      .replace(/\([^)]*(official|video|visualizer|lyrics?|audio|remaster|hd|4k)[^)]*\)/gi, '')
      .replace(/\[[^\]]*(official|video|visualizer|lyrics?|audio|remaster|hd|4k)[^\]]*\]/gi, '')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  }

  return {
    artistBrowseSectionItemMatches,
    artistMatchesSearchItem,
    dedupeMediaItems,
    futureTrackPlayableMatches,
    isSingleOrEpRelease,
    itemMatchesReleaseSection,
    mergeTrackMetadata,
    mergeSearchResults,
    normalizeSearch,
    normalizedLookupText,
    searchCatalog,
    searchTrackAlbumMetadata,
    searchArtistShelfFallback
  };
}
