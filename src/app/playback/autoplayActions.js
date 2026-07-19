const AUTOPLAY_REFILL_THRESHOLD = 3;
const AUTOPLAY_QUEUE_LIMIT = 20;

export function installAutoplayActions(ctx) {
  ctx.autoplayTrackKey = function autoplayTrackKey(track) {
    return track?.id || '';
  };

  ctx.removeAutoplayTracks = function removeAutoplayTracks() {
    const filtered = ctx.queue.value.filter((track) => !track.autoplayGenerated);
    if (filtered.length === ctx.queue.value.length) return;

    ctx.queue.value = filtered;
    ctx.clearNextPreload();
    void ctx.preloadNextTrack();
  };

  ctx.autoplaySeedTrack = function autoplaySeedTrack() {
    const queuedTracks = ctx.queue.value.filter(ctx.isPlayableTrack);
    return queuedTracks.at(-1) || ctx.activeTrack.value;
  };

  ctx.autoplayKnownTrackIds = function autoplayKnownTrackIds() {
    return new Set([
      ctx.activeTrack.value,
      ...ctx.queue.value,
      ...ctx.history.value
    ].map(ctx.autoplayTrackKey).filter(Boolean));
  };

  ctx.normalizeAutoplayTracks = function normalizeAutoplayTracks(tracks, seed) {
    const knownIds = ctx.autoplayKnownTrackIds();
    if (seed?.id) knownIds.add(seed.id);

    return (Array.isArray(tracks) ? tracks : [])
      .filter(ctx.isPlayableTrack)
      .filter((track) => {
        const key = ctx.autoplayTrackKey(track);
        if (!key || knownIds.has(key)) return false;
        knownIds.add(key);
        return true;
      })
      .slice(0, AUTOPLAY_QUEUE_LIMIT)
      .map((track) => ({
        ...track,
        autoplayGenerated: true,
        queueOrigin: { kind: 'autoplay', title: 'Autoplay' }
      }));
  };

  ctx.ensureAutoplayQueue = async function ensureAutoplayQueue({ force = false } = {}) {
    if (!ctx.autoplayEnabled.value || !ctx.activeTrack.value?.id) return false;
    if (ctx.autoplaySuppressedTrackId === ctx.activeTrack.value.id) return false;
    ctx.autoplaySuppressedTrackId = '';
    if (!ctx.socket.value?.connected) return false;
    if (!force && ctx.queue.value.length > AUTOPLAY_REFILL_THRESHOLD) return false;

    const seed = ctx.autoplaySeedTrack();
    if (!seed?.id) return false;
    if (ctx.autoplayRequestPromise && ctx.autoplayRequestSeedId === seed.id) {
      return ctx.autoplayRequestPromise;
    }

    const requestId = ++ctx.autoplayRequest;
    ctx.autoplayRequestSeedId = seed.id;
    ctx.autoplayLoading.value = true;
    ctx.autoplayError.value = '';

    const request = ctx.emitWithReply('music:up-next', { videoId: seed.id })
      .then((tracks) => {
        if (requestId !== ctx.autoplayRequest || !ctx.autoplayEnabled.value) return false;

        const additions = ctx.normalizeAutoplayTracks(tracks, seed);
        if (!additions.length) {
          ctx.autoplayError.value = 'No more recommendations were found.';
          return false;
        }

        ctx.queue.value = [...ctx.queue.value, ...additions].slice(0, 100);
        void ctx.preloadNextTrack();
        return true;
      })
      .catch((error) => {
        if (requestId === ctx.autoplayRequest) {
          ctx.autoplayError.value = error?.message || 'Could not load Autoplay recommendations.';
        }
        return false;
      })
      .finally(() => {
        if (requestId !== ctx.autoplayRequest) return;
        ctx.autoplayLoading.value = false;
        ctx.autoplayRequestPromise = null;
      });

    ctx.autoplayRequestPromise = request;
    return request;
  };
}
