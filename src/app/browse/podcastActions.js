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
