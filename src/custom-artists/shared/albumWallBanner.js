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

const DEFAULT_ALBUM_WALL_LIMIT = 12;
const MIN_ALBUM_WALL_LIMIT = 4;
const MAX_ALBUM_WALL_LIMIT = 18;

function cleanText(value) {
  return String(value || '').trim();
}

function tileKey(tile) {
  return `${cleanText(tile.image)}::${cleanText(tile.title).toLowerCase()}`;
}

function sourceSectionMatches(section, sources) {
  const sourceText = `${section?.key || ''} ${section?.title || ''}`.toLowerCase();
  return sources.some((source) => sourceText.includes(source));
}

function preferredTileImage(item, mediaThumbnail) {
  return cleanText(mediaThumbnail?.(item)) ||
    cleanText(item?.thumbnail) ||
    cleanText(item?.image) ||
    cleanText(item?.artwork);
}

function limitForBanner(banner) {
  const rawLimit = Number.parseInt(banner?.limit, 10);
  if (!Number.isFinite(rawLimit)) return DEFAULT_ALBUM_WALL_LIMIT;
  return Math.min(MAX_ALBUM_WALL_LIMIT, Math.max(MIN_ALBUM_WALL_LIMIT, rawLimit));
}

function sectionSources(banner) {
  const sources = Array.isArray(banner?.sources) ? banner.sources : ['album', 'single', 'ep'];
  return sources.map((source) => cleanText(source).toLowerCase()).filter(Boolean);
}

export function albumWallBannerTiles(detail, mediaThumbnail) {
  const banner = detail?.customBanner;
  if (detail?.kind !== 'artist' || banner?.type !== 'albumWall') return [];

  const limit = limitForBanner(banner);
  const tiles = [];
  const seen = new Set();

  function addTile(tile) {
    const image = cleanText(tile?.image);
    if (!image) return;

    const normalized = {
      image,
      title: cleanText(tile.title),
      id: cleanText(tile.id) || image
    };
    const key = tileKey(normalized);
    if (seen.has(key)) return;

    seen.add(key);
    tiles.push(normalized);
  }

  for (const tile of banner.tiles || []) addTile(tile);

  const sources = sectionSources(banner);
  const sections = (detail.sections || []).filter((section) => sourceSectionMatches(section, sources));

  for (const section of sections) {
    for (const item of section.items || []) {
      addTile({
        id: item?.id || item?.browseId || item?.albumId || item?.title,
        title: item?.title,
        image: preferredTileImage(item, mediaThumbnail)
      });

      if (tiles.length >= limit) return tiles;
    }
  }

  return tiles.slice(0, limit);
}
