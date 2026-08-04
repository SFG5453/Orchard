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

import {
  customArtistAliasForQuery as hostedCustomArtistAliasForQuery,
  customArtistProfileArtworkForId,
  fetchCustomArtistIndex
} from './customArtistPacks.js';

export function installCustomArtistProfileArtwork(ctx) {
  ctx.loadCustomArtistPages = function loadCustomArtistPages() {
    if (ctx.customArtistPagesEnabled?.value === false) return Promise.resolve(null);
    return fetchCustomArtistIndex().catch(() => null);
  };

  void ctx.loadCustomArtistPages();

  ctx.customArtistProfileArtwork = function customArtistProfileArtwork(item) {
    if (ctx.customArtistPagesEnabled?.value === false) return '';
    if (!ctx.isArtistItem?.(item)) return '';

    const browseId = ctx.itemBrowseId?.(item) || item?.browseId || '';
    return customArtistProfileArtworkForId(browseId);
  };

  ctx.mediaThumbnail = function mediaThumbnail(item) {
    return ctx.customArtistProfileArtwork(item) || item?.thumbnail || '';
  };

  ctx.customArtistAliasForQuery = function customArtistAliasForQuery(query) {
    if (ctx.customArtistPagesEnabled?.value === false) return null;
    return hostedCustomArtistAliasForQuery(query);
  };

  ctx.withCustomArtistAliasMetadata = function withCustomArtistAliasMetadata(item, alias) {
    if (!item || !alias || ctx.itemBrowseId?.(item) !== alias.browseId) return item;
    return {
      ...item,
      searchAliases: [...new Set([...(item.searchAliases || []), ...alias.aliases])],
      customSearchPriority: 1000
    };
  };
}
