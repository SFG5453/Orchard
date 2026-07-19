// Derives stable library sections from normalized feed and playlist data.
function feedPlaylistItems(feed) {
  return (feed?.sections || [])
    .flatMap((section) => section.items || [])
    .filter((item) => item.type === 'playlist' && (item.browseId || item.browsePayload?.browseId));
}

function playlistItemId(item) {
  return item?.browseId || item?.browsePayload?.browseId || '';
}

function fallbackPlaylistItems(primary, fallback) {
  const primaryPlaylistIds = new Set(feedPlaylistItems(primary).map(playlistItemId));

  return feedPlaylistItems(fallback).filter((item) => {
    const id = playlistItemId(item);
    return id && !primaryPlaylistIds.has(id);
  });
}

function librarySectionIndex(sections) {
  const exactIndex = sections.findIndex((section) => String(section.title || '').toLowerCase() === 'library');
  return exactIndex >= 0 ? exactIndex : 0;
}

function mergeLibraryPlaylistSections(primary, fallback) {
  const playlistItems = fallbackPlaylistItems(primary, fallback);
  if (!playlistItems.length) return primary || fallback || { sections: [], filters: [] };

  const sections = [...(primary?.sections || [])];
  const targetIndex = librarySectionIndex(sections);
  const targetSection = sections[targetIndex] || { key: 'library', title: 'Library', items: [] };
  sections[targetIndex] = {
    ...targetSection,
    title: targetSection.title || 'Library',
    items: [...playlistItems, ...(targetSection.items || [])]
  };

  return {
    filters: primary?.filters || fallback?.filters || [],
    sections
  };
}

function playlistFallbackAsLibrary(fallback) {
  const playlistItems = fallbackPlaylistItems(null, fallback);
  if (!playlistItems.length) return fallback;
  const remainingSections = (fallback?.sections || [])
    .map((section) => ({
      ...section,
      items: (section.items || []).filter((item) => item.type !== 'playlist')
    }))
    .filter((section) => section.items.length > 0);

  return {
    filters: fallback?.filters || [],
    sections: [
      { key: 'library-playlists', title: 'Library', items: playlistItems },
      ...remainingSections
    ]
  };
}

export function createLibraryFeed({ bridgeError, fetchMusicLibraryFallback, normalizeFeedResult }) {
  async function fetchMusicLibraryFeed(yt) {
    const errors = [];
    let primaryFeed = null;
    const fallbackPromise = fetchMusicLibraryFallback(yt)
      .then(normalizeFeedResult)
      .catch((error) => {
        errors.push(`Music library fallback: ${bridgeError(error)}`);
        return null;
      });

    try {
      primaryFeed = normalizeFeedResult(await yt.music.getLibrary());
      if (feedPlaylistItems(primaryFeed).length > 0) return { feed: primaryFeed, error: null };
    } catch (error) {
      errors.push(`Music library: ${bridgeError(error)}`);
    }

    try {
      const fallbackFeed = await fallbackPromise;
      if (!fallbackFeed) throw new Error('Music library fallback returned no data');
      if (primaryFeed) {
        const merged = mergeLibraryPlaylistSections(primaryFeed, fallbackFeed);
        return {
          feed: merged,
          error: feedPlaylistItems(merged).length > 0 ? null : 'Music library: no playlist items were returned'
        };
      }
      return { feed: playlistFallbackAsLibrary(fallbackFeed), error: null };
    } catch (error) {
      if (!errors.some((message) => message.startsWith('Music library fallback:'))) {
        errors.push(`Music library fallback: ${bridgeError(error)}`);
      }
    }

    return {
      feed: primaryFeed || { sections: [], filters: [] },
      error: errors.join(' | ')
    };
  }

  return { fetchMusicLibraryFeed };
}
