import { computed, ref } from 'vue';

function releaseSectionForTiming(timing) {
  return {
    out_today: 'Out today',
    this_week: 'This week',
    coming_soon: 'Coming soon',
    recently_released: 'Recently released'
  }[timing] || 'Recently released';
}

function releaseTimingOrder(timing) {
  return ['out_today', 'this_week', 'coming_soon', 'recently_released'].indexOf(timing);
}

function compareReleaseDates(left, right) {
  if (left.releaseTiming === 'recently_released' && right.releaseTiming === 'recently_released') {
    return right.releaseDaysFromToday - left.releaseDaysFromToday ||
      left.artist.localeCompare(right.artist) ||
      left.title.localeCompare(right.title);
  }

  return left.releaseDaysFromToday - right.releaseDaysFromToday ||
    left.artist.localeCompare(right.artist) ||
    left.title.localeCompare(right.title);
}

export function installReleaseRadarActions(ctx) {
  ctx.releaseRadarArtists = ref([]);
  ctx.releaseRadarLoading = ref(false);
  ctx.releaseRadarError = ref('');
  ctx.releaseRadarReleases = ref([]);
  ctx.releaseRadarNotifiedIds = new Set();

  ctx.releaseRadarSections = computed(() => {
    const groups = new Map();
    for (const release of ctx.releaseRadarReleases.value) {
      const title = releaseSectionForTiming(release.releaseTiming);
      const items = groups.get(title) || [];
      items.push(release);
      groups.set(title, items);
    }

    return [...groups.entries()]
      .map(([title, items]) => ({
        key: title.toLowerCase().replace(/\s+/g, '-'),
        title,
        items: [...items].sort(compareReleaseDates)
      }))
      .sort((left, right) =>
        releaseTimingOrder(left.items[0]?.releaseTiming) - releaseTimingOrder(right.items[0]?.releaseTiming)
      );
  });

  ctx.releaseRadarSummary = computed(() => {
    const releases = ctx.releaseRadarReleases.value;
    return {
      subscribed: ctx.releaseRadarArtists.value.length,
      outToday: releases.filter((release) => release.releaseTiming === 'out_today').length,
      upcoming: releases.filter((release) => release.releaseDaysFromToday > 0).length
    };
  });

  ctx.notifyTodaysReleases = function notifyTodaysReleases(releases = []) {
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;

    releases
      .filter((release) => release.releaseTiming === 'out_today')
      .slice(0, 3)
      .forEach((release) => {
        const id = `${release.artist}:${release.title}:${release.releaseDate}`;
        if (ctx.releaseRadarNotifiedIds.has(id)) return;
        ctx.releaseRadarNotifiedIds.add(id);
        new Notification('New album is out', {
          body: `${release.title} by ${release.artist}`,
          icon: release.thumbnail || ctx.orchardLogoUrl
        });
      });
  };

  ctx.loadReleaseRadar = async function loadReleaseRadar({ force = false } = {}) {
    if (!ctx.socket.value?.connected || ctx.releaseRadarLoading.value) return;
    if (!force && ctx.releaseRadarReleases.value.length) return;

    ctx.releaseRadarLoading.value = true;
    ctx.releaseRadarError.value = '';

    try {
      const data = await ctx.emitWithReply('music:release-radar', {});
      ctx.releaseRadarReleases.value = data.releases || [];
      ctx.releaseRadarArtists.value = data.artists || [];
      ctx.notifyTodaysReleases(ctx.releaseRadarReleases.value);
    } catch (error) {
      ctx.releaseRadarError.value = error.message || 'Could not load Release Radar.';
    } finally {
      ctx.releaseRadarLoading.value = false;
    }
  };

  ctx.showReleaseRadar = function showReleaseRadar() {
    if (!ctx.authState.value.signedIn) {
      ctx.selectView('home');
      return;
    }

    ctx.navigateToView('releaseRadar');
    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';
    void ctx.loadReleaseRadar();
  };

  ctx.releaseCanPlay = function releaseCanPlay(release) {
    return Boolean(release?.releaseResolved);
  };

  ctx.releaseDetailForAction = async function releaseDetailForAction(release) {
    if (release.releaseResolved) {
      return ctx.emitWithReply('music:album', {
        browseId: ctx.itemBrowseId(release),
        browsePayload: release.browsePayload || null
      });
    }

    return ctx.emitWithReply('music:future-album', {
      browseId: `itunes:${release.futureAlbumId}`,
      futureAlbumId: release.futureAlbumId
    });
  };

  ctx.openReleaseAlbum = async function openReleaseAlbum(release) {
    if (release.releaseResolved) {
      await ctx.openCollection('album', release);
      return;
    }

    await ctx.openCollection('future-album', release);
  };

  ctx.playReleaseAlbum = async function playReleaseAlbum(release) {
    if (!ctx.releaseCanPlay(release)) return;
    const detail = await ctx.releaseDetailForAction(release);
    ctx.playCollection(detail);
  };

  ctx.addReleaseAlbumToQueue = async function addReleaseAlbumToQueue(release) {
    if (!ctx.releaseCanPlay(release)) return;
    const detail = await ctx.releaseDetailForAction(release);
    const tracks = ctx.tracksWithCollectionContext(detail).filter(ctx.isPlayableTrack);
    if (!tracks.length) return;

    const queued = new Set([ctx.activeTrack.value?.id, ...ctx.queue.value.map((track) => track.id)].filter(Boolean));
    const next = tracks.filter((track) => !queued.has(track.id));
    ctx.queue.value = [...ctx.queue.value, ...next].slice(0, 100);
    ctx.syncManualQueueOrder();
    ctx.showShareMessage?.(`Added ${detail.title} to the queue.`);
  };

  ctx.pinReleaseAlbumLeadTrack = async function pinReleaseAlbumLeadTrack(release) {
    if (!ctx.releaseCanPlay(release)) return;
    const detail = await ctx.releaseDetailForAction(release);
    const track = ctx.tracksWithCollectionContext(detail).find(ctx.isPlayableTrack);
    if (track) ctx.togglePinnedTrack(track);
  };
}
