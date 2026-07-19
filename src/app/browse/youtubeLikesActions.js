import { computed, ref, watch } from 'vue';

function videoId(track) {
  return String(track?.youtubeVideoId || track?.id || '').trim();
}

function trackLabel(track) {
  return String(track?.title || 'track').trim();
}

export function installYouTubeLikesActions(ctx) {
  let requestId = 0;
  ctx.youtubeLikeState = ref({ videoId: '', liked: false, loading: false, pending: false });

  ctx.isActiveTrackLiked = computed(() => {
    const id = videoId(ctx.activeTrack.value);
    return Boolean(id && ctx.youtubeLikeState.value.videoId === id && ctx.youtubeLikeState.value.liked);
  });

  ctx.activeTrackLikePending = computed(() => ctx.youtubeLikeState.value.pending);
  ctx.canToggleActiveTrackLike = computed(() => Boolean(
    videoId(ctx.activeTrack.value) &&
    ctx.authState.value.signedIn &&
    ctx.socket.value?.connected &&
    !ctx.youtubeLikeState.value.loading &&
    !ctx.youtubeLikeState.value.pending
  ));

  ctx.loadYouTubeLikeStatus = async function loadYouTubeLikeStatus(track = ctx.activeTrack.value) {
    const id = videoId(track);
    const currentRequest = ++requestId;

    if (!id || !ctx.authState.value.signedIn || !ctx.socket.value?.connected) {
      ctx.youtubeLikeState.value = { videoId: id, liked: false, loading: false, pending: false };
      return;
    }

    ctx.youtubeLikeState.value = { videoId: id, liked: false, loading: true, pending: false };
    try {
      const result = await ctx.emitWithReply('music:like:status', { videoId: id }, { timeoutMs: 15_000 });
      if (currentRequest !== requestId || videoId(ctx.activeTrack.value) !== id) return;
      ctx.youtubeLikeState.value = { videoId: id, liked: Boolean(result?.liked), loading: false, pending: false };
    } catch {
      if (currentRequest !== requestId || videoId(ctx.activeTrack.value) !== id) return;
      ctx.youtubeLikeState.value = { videoId: id, liked: false, loading: false, pending: false };
    }
  };

  ctx.toggleActiveTrackLike = async function toggleActiveTrackLike() {
    const track = ctx.activeTrack.value;
    const id = videoId(track);
    const state = ctx.youtubeLikeState.value;
    if (!id || state.videoId !== id || !ctx.canToggleActiveTrackLike.value) return;

    const liked = !state.liked;
    ctx.youtubeLikeState.value = { videoId: id, liked, loading: false, pending: true };
    try {
      const result = await ctx.emitWithReply('music:like:set', { videoId: id, liked }, { timeoutMs: 15_000 });
      if (videoId(ctx.activeTrack.value) !== id) return;
      ctx.youtubeLikeState.value = { videoId: id, liked: Boolean(result?.liked), loading: false, pending: false };
      ctx.showShareMessage?.(liked ? `Added ${trackLabel(track)} to Liked Songs.` : `Removed ${trackLabel(track)} from Liked Songs.`);
    } catch (error) {
      if (videoId(ctx.activeTrack.value) !== id) return;
      ctx.youtubeLikeState.value = { videoId: id, liked: state.liked, loading: false, pending: false };
      ctx.showShareMessage?.(`Could not update Liked Songs: ${error.message}`, true);
    }
  };

  watch([ctx.activeTrack, () => ctx.authState.value.signedIn, ctx.socketState], () => {
    void ctx.loadYouTubeLikeStatus();
  }, { immediate: true });
}
