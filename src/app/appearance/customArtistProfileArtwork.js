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
