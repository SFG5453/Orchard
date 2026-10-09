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

// Builds a personalized radio feed from injected authenticated music clients.
function textValue(value = {}) {
  return value?.simpleText || value?.runs?.map((run) => run?.text || '').join('') || '';
}

function bestRawThumbnail(renderer = {}) {
  const thumbnails = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails ||
    renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails ||
    renderer.thumbnail?.thumbnails ||
    [];

  return thumbnails.at(-1)?.url || thumbnails[0]?.url || null;
}

function collectMixItems(value, items = []) {
  if (!value || typeof value !== 'object') return items;

  const renderer = value.musicTwoRowItemRenderer;
  if (renderer) {
    const titleRun = renderer.title?.runs?.[0] || {};
    const browsePayload = renderer.navigationEndpoint?.browseEndpoint ||
      titleRun.navigationEndpoint?.browseEndpoint ||
      null;
    const title = textValue(renderer.title);

    if (browsePayload?.browseId && /^(My Supermix|My Mix \d+|Discover Mix)$/i.test(title)) {
      items.push({
        type: 'playlist',
        title,
        subtitle: textValue(renderer.subtitle),
        thumbnail: bestRawThumbnail(renderer),
        browseId: browsePayload.browseId,
        browsePayload: { ...browsePayload }
      });
    }
  }

  for (const child of Object.values(value)) {
    if (Array.isArray(child)) child.forEach((entry) => collectMixItems(entry, items));
    else if (child && typeof child === 'object') collectMixItems(child, items);
  }

  return items;
}

export function createPersonalizedRadio({
  musicClientForBrowse,
  resolveMusicCollectionWithFallback
}) {
  return async function personalizedRadio() {
    const yt = await musicClientForBrowse();
    const collection = await resolveMusicCollectionWithFallback(yt, 'mixed', {
      browseId: 'FEmusic_mixed_for_you'
    });
    const mixes = collectMixItems(collection.data);
    const radio = mixes.find((item) => item.title === 'My Supermix') ||
      mixes.find((item) => /^My Mix \d+$/i.test(item.title)) ||
      mixes.find((item) => item.title === 'Discover Mix');

    if (!radio) throw new Error('YouTube Music did not return a personalized radio station.');
    return radio;
  };
}
