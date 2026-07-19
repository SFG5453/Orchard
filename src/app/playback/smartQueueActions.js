import { ref } from 'vue';

const SMART_QUEUE_LIMIT = 36;

function cleanText(value = '') {
  return String(value || '').trim();
}

function normalizedKey(ctx, track = {}) {
  return ctx.normalizedLookupText(`${track.title || ''} ${track.artist || track.artists?.[0] || ''}`);
}

function collectSectionItems(sections = []) {
  return sections.flatMap((section) => section?.items || section?.contents || []);
}

function pushUnique(ctx, target, seen, track, seedId = '') {
  if (!ctx.isPlayableTrack(track) || track.id === seedId || seen.has(track.id)) return;
  seen.add(track.id);
  target.push(track);
}

export function installSmartQueueActions(ctx) {
  ctx.smartQueueLoadingTrackId = ref('');

  ctx.smartQueueOrigin = function smartQueueOrigin(track) {
    return {
      kind: 'smart',
      title: `Smart queue: ${cleanText(track?.title) || 'Track'}`
    };
  };

  ctx.smartQueueLocalCandidates = function smartQueueLocalCandidates(seed) {
    const candidates = [
      ...ctx.queue.value,
      ...ctx.history.value,
      ...ctx.recentSessionTracks.value,
      ...ctx.pinnedTracks.value,
      ...(ctx.replaySummary?.value?.tracks || []).map((entry) => entry.item),
      ...(ctx.browseDetail.value?.tracks || []),
      ...collectSectionItems(ctx.searchResult.value?.sections),
      ...collectSectionItems(ctx.homeData.value?.home?.sections),
      ...collectSectionItems(ctx.homeData.value?.library?.sections)
    ].filter(ctx.isPlayableTrack);
    const seedArtist = ctx.normalizedLookupText(seed?.artist || seed?.artists?.[0] || '');
    const seedAlbum = ctx.normalizedLookupText(seed?.album || '');
    const seedKey = normalizedKey(ctx, seed);

    return candidates
      .map((track) => {
        const artist = ctx.normalizedLookupText(track.artist || track.artists?.[0] || '');
        const album = ctx.normalizedLookupText(track.album || '');
        const key = normalizedKey(ctx, track);
        const score = Number(seedArtist && artist === seedArtist) * 4 +
          Number(seedAlbum && album === seedAlbum) * 2 +
          Number(key && seedKey && key !== seedKey && key.includes(seedKey.split(' ')[0] || ''));
        return { track, score };
      })
      .sort((left, right) => right.score - left.score)
      .map((entry) => entry.track);
  };

  ctx.smartQueueRelatedTracks = async function smartQueueRelatedTracks(seed) {
    if (!ctx.socket.value?.connected || !seed?.id) return [];

    try {
      const tracks = await ctx.emitWithReply('music:up-next', { videoId: seed.id });
      return Array.isArray(tracks) ? tracks.filter(ctx.isPlayableTrack) : [];
    } catch {
      return [];
    }
  };

  ctx.buildSmartQueueFromSeed = async function buildSmartQueueFromSeed(seed) {
    if (!ctx.isPlayableTrack(seed)) return;

    ctx.smartQueueLoadingTrackId.value = seed.id;
    const seen = new Set([seed.id]);
    const origin = ctx.smartQueueOrigin(seed);
    const queue = [];

    try {
      const relatedTracks = await ctx.smartQueueRelatedTracks(seed);
      for (const track of relatedTracks) {
        pushUnique(ctx, queue, seen, { ...track, queueOrigin: origin }, seed.id);
        if (queue.length >= SMART_QUEUE_LIMIT) break;
      }

      for (const track of ctx.smartQueueLocalCandidates(seed)) {
        pushUnique(ctx, queue, seen, { ...track, queueOrigin: origin }, seed.id);
        if (queue.length >= SMART_QUEUE_LIMIT) break;
      }

      const source = [{ ...seed, queueOrigin: origin }, ...queue].slice(0, SMART_QUEUE_LIMIT + 1);
      await ctx.playTrack(source[0], {
        queueSource: source,
        sessionAction: 'smart-queue'
      });
      ctx.showShareMessage?.(`Built a smart queue from ${cleanText(seed.title) || 'that song'}.`);
    } finally {
      ctx.smartQueueLoadingTrackId.value = '';
    }
  };
}
