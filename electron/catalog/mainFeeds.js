// Coordinates home and library feed retrieval using injected browser clients and normalizers.
import { createLibraryFeed } from './libraryFeed.js';
import { shouldShowMusicItem } from './musicItemTypes.js';

export function createMainFeeds({
  asText,
  browseContinuationTokenFromData,
  bridgeError,
  fetchRawBrowserMusicBrowse,
  hasBrowserLoginCookie,
  normalizeBrowseSection,
  normalizeRawBrowseItem,
  normalizeTrack,
  normalizeTvLibrary,
  rawBrowseItemsFromData,
  rawSectionList
}) {
  function shelfItems(shelf) {
    const contents = shelf?.contents || shelf?.items || [];
    return contents.map(normalizeTrack).filter((item) => item.id || item.browseId || item.title !== 'Untitled');
  }

  function catalogAudioItems(items) {
    return items.filter((item) =>
      item.musicVideoType !== 'MUSIC_VIDEO_TYPE_OMV' &&
      item.musicVideoType !== 'MUSIC_VIDEO_TYPE_UGC'
    );
  }

  function normalizeShelf(section, index) {
    const title = asText(section?.title || section?.header?.title || section?.header?.strapline) || 'Library';
    const items = shelfItems(section);
    return {
      key: `${section?.type || 'section'}-${index}`,
      title,
      items: items.filter(shouldShowMusicItem)
    };
  }

  function normalizeFeed(feed) {
    const sections = feed.sections || feed.contents || [];

    return {
      filters: feed.filters || [],
      sections: sections
        .map(normalizeShelf)
        .filter((section) => section.items.length > 0)
    };
  }

  function normalizeFeedResult(result) {
    if (Array.isArray(result?.sections) && result.sections.every((section) => Array.isArray(section.items))) {
      return result;
    }

    return normalizeFeed(result);
  }

  function libraryFilterRequest(endpoint = {}) {
    const command = endpoint.innertubeCommand || endpoint.command || endpoint.performOnceCommand || endpoint;
    const commands = command.commandExecutorCommand?.commands || command.serialCommand?.commands || command.parallelCommand?.commands || [];

    for (const child of [...commands].reverse()) {
      const request = libraryFilterRequest(child);
      if (request) return request;
    }

    const browse = command.browseEndpoint;
    if (browse?.browseId) {
      return {
        browseId: browse.browseId,
        ...(browse.params ? { params: browse.params } : {})
      };
    }

    const reload = command.browseSectionListReloadEndpoint;
    const continuation = reload?.continuation?.reloadContinuationData?.continuation ||
      reload?.continuation?.continuationCommand?.token || reload?.continuation;
    if (typeof continuation === 'string' && continuation) return { continuation };

    const token = command.continuationCommand?.token;
    return token ? { continuation: token } : null;
  }

  function findLibraryFilterRequest(data, title) {
    const target = title.trim().toLowerCase();
    const pending = [data];

    while (pending.length) {
      const value = pending.pop();
      if (!value || typeof value !== 'object') continue;
      const chip = value.chipCloudChipRenderer || value.musicMultiSelectMenuItemRenderer;
      if (chip && asText(chip.text || chip.title).trim().toLowerCase() === target) {
        return libraryFilterRequest(chip.navigationEndpoint || chip.endpoint || chip.serviceEndpoint);
      }
      pending.push(...Object.values(value));
    }

    return null;
  }

  async function fetchRawLibraryPage(yt, request) {
    if (hasBrowserLoginCookie()) {
      try {
        return await fetchRawBrowserMusicBrowse(request);
      } catch {
        // OAuth-backed InnerTube can still satisfy the same raw request.
      }
    }

    const response = await yt.actions.execute('/browse', { ...request, client: 'YTMUSIC' });
    return response.data;
  }

  function normalizedRawLibraryItems(data) {
    return rawBrowseItemsFromData(data)
      .map(normalizeRawBrowseItem)
      .filter(Boolean)
      .filter(shouldShowMusicItem);
  }

  async function fetchMusicLibraryCategory(yt, title) {
    const landing = await fetchRawLibraryPage(yt, { browseId: 'FEmusic_library_landing' });
    const filterRequest = findLibraryFilterRequest(landing, title);
    if (!filterRequest) return [];

    const items = [];
    let request = filterRequest;
    let pageCount = 0;

    while (request && pageCount < 20) {
      const page = await fetchRawLibraryPage(yt, request);
      items.push(...normalizedRawLibraryItems(page));
      pageCount += 1;
      const continuation = browseContinuationTokenFromData(page);
      request = continuation ? { continuation } : null;
    }

    const seen = new Set();
    return items.filter((item) => {
      const key = item.browseId || item.browsePayload?.browseId || item.id || `${item.type}:${item.title}`;
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  function normalizeRawMusicFeed(data) {
    return {
      filters: [],
      sections: rawSectionList(data)
        .map(normalizeBrowseSection)
        .map((section) => ({ ...section, items: section.items.filter(shouldShowMusicItem) }))
        .filter((section) => section.items.length > 0)
    };
  }

  async function fetchFeed(label, primary, fallback) {
    const errors = [];

    try {
      return { feed: normalizeFeedResult(await primary()), error: null };
    } catch (error) {
      errors.push(`${label}: ${bridgeError(error)}`);
    }

    if (fallback) {
      try {
        return { feed: normalizeFeedResult(await fallback()), error: null };
      } catch (error) {
        errors.push(`${label} fallback: ${bridgeError(error)}`);
      }
    }

    return {
      feed: { sections: [], filters: [] },
      error: errors.join(' | ')
    };
  }

  async function fetchTvLibrary(yt) {
    const response = await yt.actions.execute('/browse', {
      browseId: 'FElibrary',
      client: 'TV'
    });

    return normalizeTvLibrary(response.data);
  }

  async function fetchBrowserMusicHome() {
    return normalizeRawMusicFeed(await fetchRawBrowserMusicBrowse({
      browseId: 'FEmusic_home'
    }));
  }

  async function fetchBrowserMusicLibrary() {
    return normalizeRawMusicFeed(await fetchRawBrowserMusicBrowse({
      browseId: 'FEmusic_library_landing'
    }));
  }

  async function fetchMusicLibraryFallback(yt) {
    const errors = [];

    if (hasBrowserLoginCookie()) {
      try {
        return await fetchBrowserMusicLibrary();
      } catch (error) {
        errors.push(`browser-cookie: ${bridgeError(error)}`);
      }
    }

    try {
      return await fetchTvLibrary(yt);
    } catch (error) {
      errors.push(`TV: ${bridgeError(error)}`);
    }

    throw new Error(errors.join(' | ') || 'No Music library fallback is available.');
  }

  const { fetchMusicLibraryFeed } = createLibraryFeed({
    bridgeError,
    fetchMusicLibraryFallback,
    normalizeFeedResult
  });

  return {
    catalogAudioItems,
    fetchBrowserMusicHome,
    fetchFeed,
    fetchMusicLibraryCategory,
    fetchMusicLibraryFeed,
    normalizeFeedResult,
    shelfItems
  };
}
