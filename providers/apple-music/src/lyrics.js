/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Apple Music's own time-synced lyrics as TTML; hosts parse it with the other lyric sources.

import { AppleMusicError } from './client.js';
import { findSong } from './match.js';

// Word-by-word first; songs with only line timing answer 404 there.
const KINDS = ['syllable-lyrics', 'lyrics'];

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

function plainText(markup) {
  return markup
    .replace(/<[^>]*>/g, '')
    .replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (match, name) => {
      if (name[0] !== '#') return ENTITIES[name.toLowerCase()] ?? match;
      const code = name[1].toLowerCase() === 'x' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : match;
    })
    .trim();
}

/** {lineKey: text} from the subtitle translation in localized TTML that matches [language]. */
export function parseTranslations(ttml = '', language = '') {
  const primary = String(language).toLowerCase().split('-')[0];
  const blocks = [...String(ttml).matchAll(/<translation\b([^>]*)>([\s\S]*?)<\/translation>/g)];
  const block = blocks.find(([, attributes]) => {
    const lang = /xml:lang="([^"]*)"/.exec(attributes)?.[1] || '';
    return /type="subtitle"/.test(attributes) && lang.toLowerCase().split('-')[0] === primary;
  });
  const result = {};
  if (!block) return result;
  for (const [, key, body] of block[2].matchAll(/<text\b[^>]*\bfor="([^"]+)"[^>]*>([\s\S]*?)<\/text>/g)) {
    const text = plainText(body);
    if (text) result[key] = text;
  }
  return result;
}

function missing(error) {
  if (error instanceof AppleMusicError && error.code === 'not_found') return null;
  throw error;
}

export function createLyricsCatalog(client) {
  /** {songId, ttml} for a track from any service, or null when Apple has no lyrics for it. */
  async function lyrics(query = {}) {
    const song = await findSong(client, query);
    if (!song?.id || song.attributes?.hasLyrics === false) return null;
    const path = `/v1/catalog/${client.storefront()}/songs/${encodeURIComponent(song.id)}`;
    for (const kind of KINDS) {
      const result = await client.editorial(`${path}/${kind}`, {}, { personal: true }).catch(missing);
      const ttml = result?.data?.[0]?.attributes?.ttml;
      if (ttml) return { songId: song.id, ttml };
    }
    return null;
  }

  /** {songId, language, translations} from Apple's own translation, or null when it has none. */
  async function lyricsTranslation(query = {}) {
    const language = String(query.language || '');
    if (!language) return null;
    const song = await findSong(client, query);
    if (!song?.id || song.attributes?.hasLyrics === false) return null;
    const path = `/v1/catalog/${client.storefront()}/songs/${encodeURIComponent(song.id)}`;
    const params = { extend: 'ttmlLocalizations', 'l[lyrics]': language };
    for (const kind of KINDS) {
      const result = await client.editorial(`${path}/${kind}`, params, { personal: true }).catch(missing);
      const translations = parseTranslations(result?.data?.[0]?.attributes?.ttmlLocalizations, language);
      if (Object.keys(translations).length) return { songId: song.id, language, translations };
    }
    return null;
  }

  return { lyrics, lyricsTranslation };
}
