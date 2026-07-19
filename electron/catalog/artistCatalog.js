// Builds normalized artist catalog views from injected browse and search primitives.
export function createArtistCatalog({
  asText,
  artistBrowseSectionItemMatches,
  artistFutureAlbumMetadata,
  browseContinuationTokenFromData,
  cacheFutureAlbumDetails,
  dedupeMediaItems,
  hydrateFutureAlbumDetails,
  isExpandableBrowseSectionTitle,
  isSingleOrEpRelease,
  itemMatchesReleaseSection,
  mergeFutureAlbumsIntoSections,
  mergeTrackMetadata,
  normalizeAlbum,
  normalizeBrowseSection,
  normalizeRawBrowseItem,
  normalizedLooseText,
  rawBrowseDescription,
  rawBrowseItemsFromData,
  rawBrowseThumbnail,
  rawHeader,
  rawMicroformat,
  rawSectionList,
  searchTrackAlbumMetadata,
  searchArtistShelfFallback
}) {
  const hydratedArtistCache = new Map();
  const maxHydratedArtistCacheEntries = 48;

  function artistCacheKey(value) {
    return String(value?.browseId || value || '').trim();
  }

  function cachedArtistResult(browseId, { hydratedOnly = false, pendingOnly = false } = {}) {
    const key = artistCacheKey(browseId);
    const entry = key ? hydratedArtistCache.get(key) : null;
    if (!entry?.data) return null;
    if (hydratedOnly && !entry.hydrated) return null;
    if (pendingOnly && !entry.promise) return null;

    hydratedArtistCache.delete(key);
    hydratedArtistCache.set(key, entry);
    return entry.data;
  }

  function cacheArtistResult(key, data, { hydrated = true } = {}) {
    const cacheKey = artistCacheKey(key || data?.browseId);
    if (!cacheKey || !data) return data;

    const existing = hydratedArtistCache.get(cacheKey) || {};
    hydratedArtistCache.delete(cacheKey);
    hydratedArtistCache.set(cacheKey, {
      ...existing,
      data,
      hydrated
    });
    while (hydratedArtistCache.size > maxHydratedArtistCacheEntries) {
      hydratedArtistCache.delete(hydratedArtistCache.keys().next().value);
    }

    return data;
  }

  async function hydrateArtist(collection) {
    const key = artistCacheKey(collection);
    const cached = cachedArtistResult(key);
    if (cached && hydratedArtistCache.get(key)?.hydrated) return cached;

    const existing = key ? hydratedArtistCache.get(key) : null;
    if (existing?.promise) return existing.promise;

    const promise = normalizeArtist(collection)
      .then((result) => cacheArtistResult(key, result, { hydrated: true }))
      .catch((error) => {
        if (key && hydratedArtistCache.get(key)?.promise === promise) hydratedArtistCache.delete(key);
        throw error;
      });

    if (key) hydratedArtistCache.set(key, { ...(existing || {}), promise });
    return promise;
  }

  function cacheInitialArtistResult(collection, data) {
    return cacheArtistResult(collection, data, { hydrated: false });
  }

  async function completeBrowseSectionItems(collection, section, artistName, artistBrowseId, maxRequests = 12) {
    let expandedSection = {
      ...section,
      items: section.items
        .filter((item) => artistBrowseSectionItemMatches(item, artistName, artistBrowseId, section.title))
        .filter((item) => itemMatchesReleaseSection(item, section.title))
    };

    if (section.browsePayload && collection?.browse) {
      const rawItems = [];

      try {
        const firstPage = await collection.browse(section.browsePayload);
        rawItems.push(...rawBrowseItemsFromData(firstPage));

        let continuation = browseContinuationTokenFromData(firstPage);
        const seenContinuations = new Set();
        let requestCount = 0;

        while (continuation && requestCount < maxRequests && !seenContinuations.has(continuation)) {
          seenContinuations.add(continuation);
          requestCount += 1;

          const page = await collection.continue(continuation);
          rawItems.push(...rawBrowseItemsFromData(page));
          continuation = browseContinuationTokenFromData(page);
        }

        const items = rawItems
          .map((item, index) => normalizeRawBrowseItem(item, index))
          .filter((item) => item && artistBrowseSectionItemMatches(item, artistName, artistBrowseId, section.title))
          .filter((item) => itemMatchesReleaseSection(item, section.title));

        if (items.length > expandedSection.items.length) {
          expandedSection = { ...section, items: dedupeMediaItems(items) };
        }
      } catch (error) {
        console.warn(`Could not expand ${section.title} shelf: ${error.message}`);
      }
    }

    if (!expandedSection.items.length && collection?.search) {
      const fallbackItems = await searchArtistShelfFallback(collection, artistName, artistBrowseId, section.title);
      if (fallbackItems.length) {
        expandedSection = {
          ...expandedSection,
          items: dedupeMediaItems([...expandedSection.items, ...fallbackItems])
        };
      }
    }

    return expandedSection;
  }

  function releaseYear(item) {
    const matches = [
      item?.year,
      item?.subtitle,
      item?.itemCount
    ].filter(Boolean).join(' ').match(/\b(?:19|20)\d{2}\b/g);

    return matches?.length ? Number(matches.at(-1)) : 0;
  }

  function sortArtistSection(section) {
    if (!/albums?|singles?/i.test(section.title)) return section;

    return {
      ...section,
      items: [...section.items].sort((left, right) => releaseYear(right) - releaseYear(left))
    };
  }

  function canonicalReleaseTitle(item) {
    const title = String(item?.title || '');
    const withoutVariantTags = title
      .replace(/\s*[\[(][^\])]*(?:instrumentals?|samplers?)[^\])]*[\])]\s*/gi, ' ')
      .replace(/\s+[-–—]\s+(?:instrumentals?|samplers?)\b.*$/i, ' ')
      .replace(/\s+(?:instrumentals?|samplers?)\s*$/i, ' ');

    return normalizedLooseText(withoutVariantTags) || normalizedLooseText(title);
  }

  function releaseVariantRank(item) {
    return /\b(?:instrumentals?|samplers?)\b/i.test(item?.title || '') ? 1 : 0;
  }

  function releaseIdentityKey(item) {
    const title = canonicalReleaseTitle(item);
    if (!title) return '';

    const artist = normalizedLooseText(item.artist || item.artists?.[0] || '');
    const releaseKind = isSingleOrEpRelease(item) ? 'single' : 'album';
    return `${releaseKind}:${title}:${artist}`;
  }

  function preferredRelease(left, right) {
    if (!left) return right;
    if (!right) return left;

    const leftRank = releaseVariantRank(left);
    const rightRank = releaseVariantRank(right);
    if (leftRank !== rightRank) return leftRank < rightRank ? left : right;
    if (Boolean(left.explicit) !== Boolean(right.explicit)) return right.explicit ? right : left;

    return left;
  }

  function dedupeArtistReleases(items = []) {
    const releases = new Map();

    for (const item of items) {
      const key = releaseIdentityKey(item);
      if (!key) continue;
      releases.set(key, preferredRelease(releases.get(key), item));
    }

    return [...releases.values()];
  }

  function partitionArtistReleaseSections(sections) {
    const albumSection = sections.find((section) => /albums?/i.test(section.title));
    const singlesSection = sections.find((section) => /singles?|eps?/i.test(section.title));
    if (!albumSection && !singlesSection) return sections;

    const releases = dedupeArtistReleases([
      ...(albumSection?.items || []),
      ...(singlesSection?.items || [])
    ]);
    const albums = releases.filter((item) => !isSingleOrEpRelease(item));
    const singles = releases.filter(isSingleOrEpRelease);

    return sections.map((section) => {
      if (section === albumSection) return { ...section, items: albums };
      if (section === singlesSection) return { ...section, items: singles };
      return section;
    });
  }

  function isAlbumReleaseItem(item) {
    const pageType = item?.browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    const browseId = item?.browsePayload?.browseId || item?.browseId || item?.albumId || item?.id || '';

    return pageType === 'MUSIC_PAGE_TYPE_ALBUM' ||
      item?.type === 'album' ||
      browseId.startsWith('MPR') ||
      browseId.startsWith('FEmusic_library_privately_owned_release');
  }

  function artistCatalogSummary(sections) {
    const albumSection = sections.find((section) => /albums?/i.test(section.title));
    const singlesSection = sections.find((section) => /singles?|eps?/i.test(section.title));
    if (albumSection && albumSection === singlesSection) {
      const count = albumSection.items.length;
      return count ? `${count} release${count === 1 ? '' : 's'}` : '';
    }

    const counts = [];
    const albumCount = albumSection?.items.length || 0;
    const singlesCount = singlesSection?.items.length || 0;
    if (albumCount) counts.push(`${albumCount} album${albumCount === 1 ? '' : 's'}`);
    if (singlesCount) counts.push(singlesCount === 1 ? '1 single or EP' : `${singlesCount} singles & EPs`);
    return counts.join(' · ');
  }

  function releaseBrowsePayload(item) {
    const browsePayload = item?.browsePayload ? { ...item.browsePayload } : {};
    const browseId = browsePayload.browseId || item?.browseId || item?.albumId || item?.id || null;
    return browseId ? { ...browsePayload, browseId } : null;
  }

  async function albumTrackMetadata(collection, release) {
    const browsePayload = releaseBrowsePayload(release);
    if (!browsePayload || !collection?.browse) return [];

    try {
      const album = normalizeAlbum(await collection.browse(browsePayload), browsePayload.browseId);
      return album.tracks.map((track) => ({
        ...track,
        album: album.title || release.title || track.album || '',
        albumId: album.browseId || browsePayload.browseId,
        artist: track.artist || album.artist || release.artist || release.artists?.[0] || '',
        artists: track.artists?.length ? track.artists : [album.artist || release.artist || release.artists?.[0]].filter(Boolean)
      }));
    } catch (error) {
      console.warn(`Could not hydrate album metadata for ${release.title || browsePayload.browseId}: ${error.message}`);
      return [];
    }
  }

  async function artistReleaseTrackMetadata(collection, sections, maxReleases = 8) {
    const seen = new Set();
    const releases = sections
      .flatMap((section) => section.items || [])
      .filter(isAlbumReleaseItem)
      .filter((item) => {
        const browseId = releaseBrowsePayload(item)?.browseId;
        if (!browseId || seen.has(browseId)) return false;
        seen.add(browseId);
        return true;
      })
      .slice(0, maxReleases);

    const albums = await Promise.all(releases.map((release) => albumTrackMetadata(collection, release)));
    return albums.flat();
  }

  async function artistPopularTrackSearchMetadata(collection, tracks, artistName, artistBrowseId) {
    const missingMetadataTracks = tracks
      .filter((track) => track.id && (!track.album || !track.albumId || !track.duration))
      .slice(0, 5);
    if (!missingMetadataTracks.length || !searchTrackAlbumMetadata) return [];

    const results = await Promise.all(missingMetadataTracks.map((track) =>
      searchTrackAlbumMetadata(collection, track, artistName, artistBrowseId, {
        maxContinuationRequests: 0
      })
    ));
    return results.filter(Boolean);
  }

  function artistResult(artist, browseId, sections, { catalogComplete = false, trackMetadata = [] } = {}) {
    const header = rawHeader(artist);
    const artistName = asText(header.title) || rawMicroformat(artist).title || '';
    const topSongsSection = sections.find((section) => /top songs/i.test(section.title));
    const sectionItems = sections.flatMap((section) => section.items);
    const metadataCandidates = [...trackMetadata, ...sectionItems];
    const tracks = dedupeMediaItems(
      (topSongsSection?.items || sectionItems)
        .filter((item) => item.id)
    )
      .slice(0, 5)
      .map((item, index) => ({ ...mergeTrackMetadata(item, metadataCandidates), index: String(index + 1) }));

    return {
      kind: 'artist',
      browseId,
      title: artistName || 'Artist',
      subtitle: rawBrowseDescription(artist),
      artist: artistName,
      year: '',
      itemCount: catalogComplete ? artistCatalogSummary(sections) : '',
      totalDuration: '',
      views: '',
      description: rawBrowseDescription(artist),
      thumbnail: rawBrowseThumbnail(artist),
      tracks,
      sections: sections.filter((section) => !/top songs/i.test(section.title))
    };
  }

  function initialArtistSections(artist, browseId) {
    const artistName = asText(rawHeader(artist).title) || rawMicroformat(artist).title || '';
    return rawSectionList(artist)
      .map(normalizeBrowseSection)
      .map((section) => ({
        ...section,
        items: section.items.filter((item) =>
          artistBrowseSectionItemMatches(item, artistName, browseId, section.title)
        )
      }))
      .filter((section) => section.items.length > 0);
  }

  function initialArtistResult(collection) {
    const sections = initialArtistSections(collection.data, collection.browseId);
    const visibleSections = [
      ...sections.filter((section) => /top songs/i.test(section.title)).slice(0, 1),
      ...sections.filter((section) => !/top songs/i.test(section.title)).slice(0, 4)
    ];
    const partitionedSections = partitionArtistReleaseSections(visibleSections).map(sortArtistSection);

    return artistResult(collection.data, collection.browseId, partitionedSections);
  }

  async function normalizeArtist(collection) {
    const artist = collection.data;
    const initialSections = initialArtistSections(artist, collection.browseId);
    const visibleSections = [
      ...initialSections.filter((section) => /top songs/i.test(section.title)).slice(0, 1),
      ...initialSections.filter((section) => !/top songs/i.test(section.title)).slice(0, 4)
    ];

    const sections = partitionArtistReleaseSections(visibleSections).map(sortArtistSection);
    const initialResult = artistResult(artist, collection.browseId, sections);
    const [releaseMetadata, searchMetadata] = await Promise.all([
      artistReleaseTrackMetadata(collection, sections),
      artistPopularTrackSearchMetadata(
        collection,
        initialResult.tracks,
        initialResult.artist,
        collection.browseId
      )
    ]);

    return artistResult(artist, collection.browseId, sections, {
      trackMetadata: [...searchMetadata, ...releaseMetadata]
    });
  }

  async function normalizeArtistSection(collection, section = {}) {
    const artist = collection.data;
    const artistName = asText(rawHeader(artist).title) || rawMicroformat(artist).title || '';
    const expanded = await completeBrowseSectionItems(collection, section, artistName, collection.browseId);
    const items = /albums?|singles?|eps?/i.test(expanded.title)
      ? dedupeArtistReleases(expanded.items).filter((item) => itemMatchesReleaseSection(item, expanded.title))
      : expanded.items;
    return sortArtistSection({ ...expanded, items });
  }

  return {
    cachedArtistResult,
    cacheInitialArtistResult,
    hydrateArtist,
    initialArtistResult,
    normalizeArtist,
    normalizeArtistSection
  };
}
