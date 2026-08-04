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

export function installPodcastActions(ctx) {
  ctx.isPodcastItem = function isPodcastItem(item) {
    const pageType = item?.browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    const browseId = item?.browsePayload?.browseId || item?.browseId || '';
    return pageType === 'MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE' ||
      item?.type === 'podcast' ||
      browseId.startsWith('MPSP');
  };

  ctx.loadPodcasts = async function loadPodcasts(options = {}) {
    if (!ctx.authState.value.signedIn) {
      ctx.selectView('home');
      return;
    }

    ctx.navigateToView('podcasts');
    if (!options.force && ctx.podcastFeed.value.sections.length) return;
    if (!ctx.socket.value?.connected || ctx.podcastLoading.value) return;

    ctx.podcastLoading.value = true;
    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';
    try {
      ctx.podcastFeed.value = await ctx.emitWithReply('music:podcasts');
    } catch (error) {
      ctx.errorMessage.value = error.message;
    } finally {
      ctx.podcastLoading.value = false;
    }
  };
}
