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

import {
  asText,
  bestThumbnail,
  hasExplicitBadge,
  normalizeTrack,
  textParts
} from '../../../electron/catalog/musicText.js';
import { shouldShowMusicItem } from '../../../electron/catalog/musicItemTypes.js';

const MAX_HOME_PAGES = 12;
const MAX_LIBRARY_PAGES = 20;

function list(value) {
  if (!value) return [];
  return Array.isArray(value) ? value : [value];
}

function sectionItems(section) {
  return list(section?.contents || section?.items);
}

function sectionTitle(section, fallback = 'Library') {
  return asText(
    section?.title ||
    section?.header?.title ||
    section?.header?.strapline ||
    section?.primary_text
  ) || fallback;
}

function sectionBrowsePayload(section) {
  const payload = section?.endpoint?.payload ||
    section?.header?.more_content?.endpoint?.payload ||
    section?.header?.moreContent?.endpoint?.payload ||
    null;
  return payload?.browseId ? { ...payload } : null;
}

export function normalizeTauriMusicItem(item) {
  const normalized = normalizeTrack(item || {});
  return {
    ...normalized,
    artist: normalized.artists[0] || '',
    thumbnail: normalized.thumbnail || ''
  };
}

function normalizedItems(items) {
  return list(items)
    .map(normalizeTauriMusicItem)
    .filter((item) => item.id || item.browseId || item.title !== 'Untitled')
    .filter(shouldShowMusicItem);
}

function stableKey(value = '') {
  return String(value)
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '') || 'section';
}

export function normalizeTauriMusicFeed(feed, keyPrefix = 'tauri') {
  const sections = list(feed?.sections || feed?.contents)
    .map((section, index) => {
      const title = sectionTitle(section);
      return {
        key: `${keyPrefix}-${stableKey(title)}-${index}`,
        title,
        items: normalizedItems(sectionItems(section)),
        browsePayload: sectionBrowsePayload(section)
      };
    })
    .filter((section) => section.items.length);

  return {
    filters: list(feed?.filters),
    sections
  };
}

function searchCategory(item, sourceTitle = '') {
  const source = String(sourceTitle).trim().toLowerCase();
  const type = String(item?.type || '').trim().toLowerCase().replaceAll('_', ' ');
  if (/artists?/.test(source) || type === 'artist' || type === 'library artist') return 'artists';
  if (/albums?|singles?|eps?/.test(source) || type === 'album') return 'albums';
  if (/playlists?/.test(source) || type === 'playlist') return 'playlists';
  if (/videos?/.test(source) || type === 'video' || type === 'non music track') return 'videos';
  if (/songs?|tracks?/.test(source) || type === 'song' || type === 'track') return 'songs';
  return '';
}

function normalizeSearchCard(section) {
  const normalized = normalizeTauriMusicItem(section);
  const payload = section?.endpoint?.payload || section?.on_tap?.payload || {};
  const pageType = payload.browseEndpointContextSupportedConfigs
    ?.browseEndpointContextMusicConfig?.pageType || '';
  const subtitle = asText(section?.subtitle);
  const type = pageType === 'MUSIC_PAGE_TYPE_ARTIST' || /^artist\b/i.test(subtitle)
    ? 'artist'
    : pageType === 'MUSIC_PAGE_TYPE_ALBUM'
      ? 'album'
      : pageType === 'MUSIC_PAGE_TYPE_PLAYLIST'
        ? 'playlist'
        : payload.videoId
          ? 'song'
          : normalized.type;
  return { ...normalized, type, subtitle };
}

export function normalizeTauriSearch(search) {
  const buckets = {
    songs: [],
    videos: [],
    albums: [],
    artists: [],
    playlists: []
  };

  function add(item, sourceTitle) {
    const category = searchCategory(item, sourceTitle);
    if (category) buckets[category].push(item);
  }

  function visit(section, inheritedTitle = '') {
    if (!section) return;
    const title = sectionTitle(section, '') || inheritedTitle;
    if (section.type === 'MusicCardShelf') add(normalizeSearchCard(section), title);

    for (const child of sectionItems(section)) {
      if (/Shelf|Section$/.test(String(child?.type || '')) && sectionItems(child).length) {
        visit(child, title);
      } else {
        add(normalizeTauriMusicItem(child), title);
      }
    }
  }

  for (const section of list(search?.contents || search?.sections)) visit(section);

  const definitions = [
    ['songs', 'Songs'],
    ['videos', 'Videos'],
    ['albums', 'Albums'],
    ['artists', 'Artists'],
    ['playlists', 'Playlists']
  ];
  return {
    filters: list(search?.filters),
    didYouMean: asText(search?.did_you_mean),
    showingResultsFor: asText(search?.showing_results_for),
    message: asText(search?.message),
    sections: definitions
      .map(([key, title]) => ({ key, title, items: dedupeItems(buckets[key]) }))
      .filter((section) => section.items.length)
  };
}

function appendItems(target, incoming) {
  if (!incoming.length) return;
  const seen = new Set(target.map((item) => item.browseId || item.id || `${item.type}:${item.title}`));
  for (const item of incoming) {
    const key = item.browseId || item.id || `${item.type}:${item.title}`;
    if (!key || seen.has(key)) continue;
    seen.add(key);
    target.push(item);
  }
}

function appendContinuation(feed, page, keyPrefix) {
  const continuation = page?.contents;
  const items = normalizedItems(sectionItems(continuation));
  if (!items.length) return;

  const target = feed.sections.at(-1);
  if (target) {
    appendItems(target.items, items);
    return;
  }

  feed.sections.push({
    key: `${keyPrefix}-library-0`,
    title: 'Library',
    items
  });
}

async function collectLibraryPage(initial, keyPrefix) {
  const feed = normalizeTauriMusicFeed(initial, keyPrefix);
  let page = initial;
  let pageCount = 1;

  while (page?.has_continuation && pageCount < MAX_LIBRARY_PAGES) {
    const nextPage = await page.getContinuation().catch(() => null);
    if (!nextPage || nextPage === page) break;
    appendContinuation(feed, nextPage, keyPrefix);
    page = nextPage;
    pageCount += 1;
  }

  return feed;
}

function dedupeItems(items) {
  const result = [];
  appendItems(result, items);
  return result;
}

function playlistItems(feed) {
  return dedupeItems(
    feed.sections
      .flatMap((section) => section.items)
      .filter((item) => item.type === 'playlist')
  );
}

function copyMusicFeed(feed) {
  return {
    filters: [...(feed?.filters || [])],
    sections: (feed?.sections || []).map((section) => ({
      ...section,
      items: [...(section.items || [])]
    }))
  };
}

async function collectHomeContinuations(firstPage, initialFeed) {
  let page = firstPage;
  const feed = copyMusicFeed(initialFeed);
  let pageCount = 1;

  while (page?.has_continuation && pageCount < MAX_HOME_PAGES) {
    const nextPage = await page.getContinuation().catch(() => null);
    if (!nextPage || nextPage === page) break;
    page = nextPage;
    const normalized = normalizeTauriMusicFeed(page, `home-${pageCount}`);
    feed.sections.push(...normalized.sections);
    pageCount += 1;
  }

  return feed;
}

export async function startTauriHomeFeed(music) {
  const firstPage = await music.getHomeFeed();
  const initial = normalizeTauriMusicFeed(firstPage, 'home-0');
  return {
    initial,
    complete: collectHomeContinuations(firstPage, initial)
  };
}

export async function fetchTauriHomeFeed(music) {
  return (await startTauriHomeFeed(music)).complete;
}

async function completeLibraryFeed(library) {
  const primary = await collectLibraryPage(library, 'library');
  const playlistFilter = list(library.filters)
    .find((filter) => String(filter).trim().toLowerCase() === 'playlists');

  if (!playlistFilter) return primary;

  const filtered = await library.applyFilter(playlistFilter);
  const playlists = playlistItems(await collectLibraryPage(filtered, 'library-playlists'));
  if (!playlists.length) return primary;

  const existing = new Set(playlistItems(primary).map((item) => item.browseId || item.id));
  const additions = playlists.filter((item) => !existing.has(item.browseId || item.id));
  if (!additions.length) return primary;

  return {
    ...primary,
    sections: [
      { key: 'library-playlists', title: 'Library', items: additions },
      ...primary.sections
    ]
  };
}

export async function startTauriLibraryFeed(music) {
  const library = await music.getLibrary();
  return {
    initial: normalizeTauriMusicFeed(library, 'library'),
    complete: completeLibraryFeed(library)
  };
}

export async function fetchTauriLibraryFeed(music) {
  return (await startTauriLibraryFeed(music)).complete;
}

export async function fetchTauriLibraryCategory(music, title) {
  const requested = String(title || '').trim();
  if (!requested) throw new Error('A library category is required.');

  const library = await music.getLibrary();
  if (/^recent(?:ly)? (?:added|activity)$/i.test(requested)) {
    const feed = await collectLibraryPage(library, 'library-recently-added');
    return dedupeItems(feed.sections.flatMap((section) => section.items));
  }

  const filter = list(library.filters)
    .find((candidate) => String(candidate).trim().toLowerCase() === requested.toLowerCase());
  if (!filter) throw new Error(`YouTube Music did not return the ${requested} library category.`);

  const feed = await collectLibraryPage(await library.applyFilter(filter), `library-${stableKey(requested)}`);
  const items = dedupeItems(feed.sections.flatMap((section) => section.items));
  return requested.toLowerCase() === 'songs'
    ? items.filter((item) => item.id)
    : items;
}

function collectionHeader(collection) {
  return collection?.header?.header || collection?.header || {};
}

function headerDescription(header) {
  return asText(header?.description?.description || header?.description);
}

function headerThumbnail(collection, header = collectionHeader(collection)) {
  return bestThumbnail(
    header?.thumbnail ||
    header?.thumbnails ||
    header?.foreground_thumbnail ||
    collection?.background ||
    []
  ) || '';
}

function headerItemCount(header) {
  return header?.song_count ||
    textParts(header?.second_subtitle).find((part) => /\b(songs?|tracks?|videos?)\b/i.test(part)) ||
    '';
}

function headerDuration(header) {
  return header?.total_duration ||
    textParts(header?.second_subtitle).find((part) => /\b(hours?|minutes?)\b/i.test(part)) ||
    '';
}

export function normalizeTauriPlaylistPage(playlist, startIndex = 0) {
  return list(playlist?.items || playlist?.contents)
    .map(normalizeTauriMusicItem)
    .filter((item) => item.id)
    .map((item, index) => ({ ...item, index: String(startIndex + index + 1) }));
}

export function normalizeTauriPlaylist(playlist, browseId, continuation = '') {
  const header = collectionHeader(playlist);
  const tracks = normalizeTauriPlaylistPage(playlist);
  return {
    kind: 'playlist',
    browseId,
    title: asText(header?.title) || 'Playlist',
    subtitle: asText(header?.subtitle),
    artist: '',
    year: header?.year || '',
    itemCount: headerItemCount(header) || (tracks.length ? `${tracks.length} tracks` : ''),
    totalDuration: headerDuration(header),
    views: '',
    description: headerDescription(header),
    thumbnail: headerThumbnail(playlist, header),
    tracks,
    sections: [],
    continuation,
    hasMoreTracks: Boolean(continuation),
    editable: false
  };
}

export function normalizeTauriAlbum(album, browseId) {
  const header = collectionHeader(album);
  const tracks = normalizedItems(album?.contents)
    .filter((item) => item.id)
    .map((item, index) => ({ ...item, index: String(index + 1) }));
  const artist = header?.author?.name || asText(header?.strapline_text_one);
  const sections = normalizeTauriMusicFeed({ sections: album?.sections }, 'album').sections;
  return {
    kind: 'album',
    browseId,
    title: asText(header?.title) || 'Album',
    subtitle: asText(header?.subtitle),
    artist,
    artistBrowseId: header?.author?.channel_id || '',
    releaseType: 'Album',
    explicit: hasExplicitBadge(header) || tracks.some((track) => track.explicit),
    year: header?.year || textParts(header?.subtitle).find((part) => /^[12][0-9]{3}$/.test(part)) || '',
    itemCount: headerItemCount(header) || (tracks.length ? `${tracks.length} tracks` : ''),
    totalDuration: headerDuration(header),
    views: '',
    description: headerDescription(header),
    thumbnail: headerThumbnail(album, header),
    tracks,
    sections
  };
}

export function normalizeTauriArtist(artist, browseId) {
  const header = collectionHeader(artist);
  const sections = normalizeTauriMusicFeed({ sections: artist?.sections }, 'artist').sections;
  const songSectionIndex = sections.findIndex((section) =>
    /^(top songs|songs|popular)$/i.test(section.title)
  );
  const sectionTracks = songSectionIndex >= 0
    ? sections[songSectionIndex].items.filter((item) => item.id)
    : sections.flatMap((section) => section.items).filter((item) => item.id);
  const tracks = dedupeItems(sectionTracks)
    .slice(0, 5)
    .map((item, index) => ({ ...item, index: String(index + 1) }));

  return {
    kind: 'artist',
    browseId,
    title: asText(header?.title || header?.header?.title) || 'Artist',
    subtitle: asText(header?.subtitle),
    artist: '',
    year: '',
    itemCount: '',
    totalDuration: '',
    views: '',
    description: headerDescription(header),
    thumbnail: headerThumbnail(artist, header),
    tracks,
    sections: sections.filter((_, index) => index !== songSectionIndex)
  };
}

export function normalizeTauriUpNext(panel) {
  return normalizedItems(panel?.contents).filter((item) => item.id);
}
