/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import { createArtistGenreResolver } from '../../../electron/catalog/artistGenre.js';
import { createBrowseNormalizers } from '../../../electron/catalog/browseNormalizers.js';
import { createFutureAlbums } from '../../../electron/catalog/futureAlbums.js';
import {
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  formatMillisDuration,
  hasExplicitBadge,
  normalizedLooseText,
  textMatchesArtist,
  textMatchesTitle,
  textParts
} from '../../../electron/catalog/musicText.js';
import { normalizePodcastDetail, normalizePodcastFeed } from '../../../electron/catalog/podcastCatalog.js';
import { subscribedArtistsFromChannels } from '../../../electron/catalog/subscribedArtists.js';
import { normalizeTauriSearch } from './tauriMusicCatalog.js';

const MAX_SUBSCRIPTION_PAGES = 20;

const rawCatalog = createBrowseNormalizers({
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  hasExplicitBadge,
  normalizeTrack: (item) => item,
  normalizedLooseText,
  textParts
});

function dedupeMediaItems(items = []) {
  const seen = new Set();
  return items.filter((item) => {
    const key = item?.browseId || item?.id || `${item?.type || ''}:${normalizedLooseText(item?.title)}`;
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function futureTrackPlayableMatches(track, candidate) {
  return textMatchesTitle(track?.title, candidate?.title) &&
    textMatchesArtist(track?.artist, candidate?.artist || candidate?.artists?.[0]);
}

function createFutureAlbumService() {
  return createFutureAlbums({
    dedupeMediaItems,
    formatMillisDuration,
    futureTrackPlayableMatches,
    normalizedLooseText,
    textMatchesArtist,
    textMatchesTitle
  });
}

function responseData(response) {
  return response?.data || response || {};
}

async function musicBrowse(client, payload) {
  return responseData(await client.actions.execute('/browse', {
    ...payload,
    client: 'YTMUSIC'
  }));
}

function nestedRawItems(value, result = [], seen = new Set()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return result;
  seen.add(value);

  if (value.musicTwoRowItemRenderer || value.musicResponsiveListItemRenderer) result.push(value);
  for (const child of Object.values(value)) {
    if (Array.isArray(child)) child.forEach((item) => nestedRawItems(item, result, seen));
    else nestedRawItems(child, result, seen);
  }
  return result;
}

export function normalizeTauriRadio(data) {
  const candidates = dedupeMediaItems([
    ...rawCatalog.rawBrowseItemsFromData(data),
    ...nestedRawItems(data)
  ].map(rawCatalog.normalizeRawBrowseItem).filter(Boolean));
  const preferred = [
    (item) => /^my supermix$/i.test(item.title),
    (item) => /^my mix \d+$/i.test(item.title),
    (item) => /^discover mix$/i.test(item.title)
  ];

  for (const matches of preferred) {
    const item = candidates.find((candidate) => matches(candidate) && candidate.browseId);
    if (item) return { ...item, type: 'playlist' };
  }
  throw new Error('YouTube Music did not return a personalized radio station.');
}

function playlistBrowseIds(value = '') {
  const browseId = String(value).trim();
  if (!browseId) return [];
  if (browseId.startsWith('VL')) return [browseId, browseId.slice(2)];
  if (/^(PL|OLAK)/.test(browseId)) return [`VL${browseId}`, browseId];
  if (browseId.startsWith('RD')) return [browseId, `VL${browseId}`];
  return [browseId, `VL${browseId}`];
}

async function mapInOrder(items, worker) {
  const results = [];
  for (const item of items) results.push(await worker(item));
  return results;
}

export function createTauriSidebarCatalog({
  getMusicClient,
  getSubscriptionClient,
  futureAlbums = createFutureAlbumService(),
  artistGenres = createArtistGenreResolver()
}) {
  async function subscribedArtists() {
    const client = await getSubscriptionClient();
    const channels = [];
    let feed = await client.getChannelsFeed();
    let pageCount = 0;

    while (feed && pageCount < MAX_SUBSCRIPTION_PAGES) {
      channels.push(...(feed.channels || []));
      pageCount += 1;
      feed = feed.has_continuation ? await feed.getContinuation() : null;
    }
    return subscribedArtistsFromChannels(channels);
  }

  async function resolveReleasedAlbum(music, release) {
    try {
      const search = normalizeTauriSearch(await music.search(`${release.artist} ${release.title}`, { type: 'album' }));
      const albums = search.sections.flatMap((section) => section.items || []).filter((item) => item.type === 'album');
      const match = albums.find((album) => futureAlbums.releaseAlbumMatches(release, album));
      return match ? {
        ...release,
        ...match,
        releaseDate: release.releaseDate,
        releaseDateText: release.releaseDateText,
        releaseDaysFromToday: release.releaseDaysFromToday,
        releaseTiming: release.releaseTiming,
        releaseTimingLabel: release.releaseTimingLabel,
        releaseResolved: true,
        sourceRelease: release
      } : release;
    } catch {
      return release;
    }
  }

  return {
    async radio() {
      return normalizeTauriRadio(await musicBrowse(await getMusicClient(), {
        browseId: 'FEmusic_mixed_for_you'
      }));
    },

    async podcasts() {
      return normalizePodcastFeed(await musicBrowse(await getMusicClient(), {
        browseId: 'FEmusic_podcasts'
      }));
    },

    async podcast(payload = {}) {
      const browseId = String(payload.browseId || '').trim();
      if (!browseId) throw new Error('A podcast browse ID is required.');
      return normalizePodcastDetail(await musicBrowse(await getMusicClient(), {
        browseId,
        ...(payload.params ? { params: payload.params } : {})
      }), browseId);
    },

    async playlist(payload = {}) {
      let lastError;
      for (const browseId of playlistBrowseIds(payload.browseId)) {
        try {
          const data = await musicBrowse(await getMusicClient(), {
            browseId,
            ...(payload.params ? { params: payload.params } : {})
          });
          return rawCatalog.normalizePlaylist({ data, browseId });
        } catch (error) {
          lastError = error;
        }
      }
      throw lastError || new Error('A playlist browse ID is required.');
    },

    async continuePlaylist(continuation, startIndex = 0) {
      const data = await musicBrowse(await getMusicClient(), { continuation });
      return rawCatalog.normalizePlaylistPage(data, startIndex);
    },

    async artistSection(payload = {}) {
      const browsePayload = payload.section?.browsePayload || {};
      if (!browsePayload.browseId) return { items: payload.section?.items || [] };
      const data = await musicBrowse(await getMusicClient(), browsePayload);
      const sections = rawCatalog.rawSectionList(data)
        .map(rawCatalog.normalizeBrowseSection)
        .filter((section) => section.items.length);
      return {
        title: payload.section?.title || sections[0]?.title || 'More',
        items: dedupeMediaItems(sections.flatMap((section) => section.items))
      };
    },

    subscribedArtists,

    async setArtistSubscription(payload = {}) {
      const browseId = String(payload.browseId || '').trim();
      if (!browseId) throw new Error('Artist channel ID is required.');
      const client = await getSubscriptionClient();
      if (payload.subscribed) await client.interact.subscribe(browseId);
      else await client.interact.unsubscribe(browseId);
      return { browseId, subscribed: Boolean(payload.subscribed), artists: await subscribedArtists() };
    },

    async releaseRadar() {
      const artists = await subscribedArtists();
      const releases = await futureAlbums.releaseRadarForArtists(artists);
      const music = (await getMusicClient()).music;
      return {
        artists,
        releases: await mapInOrder(releases, (release) => resolveReleasedAlbum(music, release))
      };
    },

    resolveFutureAlbum: (payload) => futureAlbums.resolveFutureAlbum(payload),
    resolveItunesAlbum: (payload) => futureAlbums.resolveItunesAlbum(payload?.albumId),
    resolveArtistGenre: (payload) => artistGenres.resolveArtistGenre(payload)
  };
}
