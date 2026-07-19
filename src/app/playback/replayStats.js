import { computed, ref, watch } from 'vue';

const REPLAY_STORAGE_KEY = 'orchard:replay-events';
const MAX_REPLAY_EVENTS = 1500;
const DEFAULT_PERIOD = 'month';

function cleanText(value = '') {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function positiveSeconds(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.round(number) : 0;
}

function durationFromText(value = '') {
  const parts = cleanText(value).split(':').map(Number);
  if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
  return parts.reduce((total, part) => total * 60 + part, 0);
}

function eventDay(timestamp) {
  return new Date(timestamp).toISOString().slice(0, 10);
}

function periodStart(period) {
  const now = Date.now();
  const day = 24 * 60 * 60 * 1000;
  if (period === 'week') return now - 7 * day;
  if (period === 'month') return now - 30 * day;
  return 0;
}

function readReplayEvents() {
  if (typeof window === 'undefined') return [];

  try {
    const parsed = JSON.parse(window.localStorage.getItem(REPLAY_STORAGE_KEY) || '[]');
    return Array.isArray(parsed) ? parsed.filter((event) => event?.track?.id && event.playedAt) : [];
  } catch {
    return [];
  }
}

function writeReplayEvents(events) {
  if (typeof window === 'undefined') return;

  try {
    window.localStorage.setItem(REPLAY_STORAGE_KEY, JSON.stringify(events.slice(0, MAX_REPLAY_EVENTS)));
  } catch {
    // Stats should never interrupt playback in locked-down storage modes.
  }
}

function trackSnapshot(track = {}, ctx) {
  const artists = Array.isArray(track.artists)
    ? track.artists.map(cleanText).filter(Boolean)
    : [];
  const artist = cleanText(track.artist || artists.join(', ') || ctx.itemMeta?.(track));
  const durationSeconds = positiveSeconds(track.durationSeconds) || durationFromText(track.duration);

  return {
    id: cleanText(track.id),
    title: cleanText(track.title),
    artist,
    artists: artists.length ? artists : [artist].filter(Boolean),
    album: cleanText(track.album),
    albumId: cleanText(track.albumId || track.futureAlbumId),
    thumbnail: cleanText(track.thumbnail),
    duration: cleanText(track.duration),
    durationSeconds,
    explicit: Boolean(track.explicit),
    type: track.type || 'song',
    mediaKind: track.mediaKind || 'audio'
  };
}

function aggregateBy(events, keyForEvent, itemForEvent) {
  const map = new Map();

  events.forEach((event) => {
    const key = keyForEvent(event);
    if (!key) return;

    const entry = map.get(key) || {
      key,
      item: itemForEvent(event),
      plays: 0,
      seconds: 0,
      lastPlayedAt: 0
    };
    entry.plays += 1;
    entry.seconds += positiveSeconds(event.listenedSeconds);
    entry.lastPlayedAt = Math.max(entry.lastPlayedAt, Number(event.playedAt) || 0);
    map.set(key, entry);
  });

  return [...map.values()].sort((left, right) =>
    right.plays - left.plays ||
    right.seconds - left.seconds ||
    right.lastPlayedAt - left.lastPlayedAt
  );
}

function summarizeReplay(events) {
  const totalSeconds = events.reduce((total, event) => total + positiveSeconds(event.listenedSeconds), 0);
  const trackEntries = aggregateBy(
    events,
    (event) => event.track.id,
    (event) => event.track
  );
  const artistEntries = aggregateBy(
    events,
    (event) => event.track.artist || event.track.artists?.[0],
    (event) => ({ title: event.track.artist || event.track.artists?.[0] || 'Unknown Artist' })
  );
  const albumEntries = aggregateBy(
    events,
    (event) => event.track.album ? `${event.track.artist}|${event.track.album}` : '',
    (event) => ({
      title: event.track.album,
      artist: event.track.artist,
      thumbnail: event.track.thumbnail
    })
  );

  return {
    totalPlays: events.length,
    totalSeconds,
    uniqueTracks: trackEntries.length,
    uniqueArtists: artistEntries.length,
    activeDays: new Set(events.map((event) => eventDay(event.playedAt))).size,
    tracks: trackEntries,
    artists: artistEntries,
    albums: albumEntries
  };
}

export function installReplayStats(ctx) {
  ctx.replayPeriod = ref(DEFAULT_PERIOD);
  ctx.replayEvents = ref(readReplayEvents());
  ctx.replaySession = {
    trackId: '',
    startedAt: 0,
    recorded: false
  };

  ctx.replayPeriodOptions = [
    { label: '30 days', value: 'month' },
    { label: '7 days', value: 'week' },
    { label: 'All time', value: 'all' }
  ];

  ctx.replayFilteredEvents = computed(() => {
    const start = periodStart(ctx.replayPeriod.value);
    return ctx.replayEvents.value.filter((event) => Number(event.playedAt) >= start);
  });

  ctx.replaySummary = computed(() => summarizeReplay(ctx.replayFilteredEvents.value));

  ctx.replayDurationLabel = function replayDurationLabel(seconds) {
    const totalMinutes = Math.round(positiveSeconds(seconds) / 60);
    if (totalMinutes < 60) return `${totalMinutes}m`;
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return minutes ? `${hours}h ${minutes}m` : `${hours}h`;
  };

  ctx.replayLastPlayedLabel = function replayLastPlayedLabel(timestamp) {
    if (!timestamp) return '';
    return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(new Date(timestamp));
  };

  ctx.replayRankStyle = function replayRankStyle(entry, entries = []) {
    const max = Math.max(...entries.map((item) => item.plays), 1);
    const fill = Math.max(6, Math.round((entry.plays / max) * 100));
    return { '--replay-fill': `${fill}%` };
  };

  ctx.persistReplayEvents = function persistReplayEvents() {
    writeReplayEvents(ctx.replayEvents.value);
  };

  ctx.recordReplayEvent = function recordReplayEvent(track, listenedSeconds) {
    const snapshot = trackSnapshot(track, ctx);
    if (!snapshot.id || !snapshot.title) return;

    ctx.replayEvents.value = [{
      id: `${Date.now()}:${snapshot.id}`,
      playedAt: Date.now(),
      listenedSeconds: positiveSeconds(listenedSeconds),
      track: snapshot
    }, ...ctx.replayEvents.value].slice(0, MAX_REPLAY_EVENTS);
    ctx.persistReplayEvents();
  };

  ctx.updateReplaySession = function updateReplaySession() {
    const track = ctx.activeTrack.value;
    if (!track?.id || ctx.replaySession.recorded || !ctx.isPlaying.value) return;

    const current = positiveSeconds(ctx.currentTime.value);
    const knownDuration = positiveSeconds(ctx.duration.value) || positiveSeconds(track.durationSeconds);
    const threshold = knownDuration ? Math.min(30, Math.max(8, knownDuration * 0.5)) : 30;
    if (current < threshold) return;

    ctx.replaySession.recorded = true;
    ctx.recordReplayEvent(track, Math.max(current, threshold));
  };

  ctx.playReplayTopTracks = function playReplayTopTracks() {
    const tracks = ctx.replaySummary.value.tracks.map((entry) => entry.item).filter(ctx.isPlayableTrack);
    if (!tracks.length) return;
    ctx.playTrack(tracks[0], { queueSource: tracks.slice(0, 60) });
  };

  ctx.clearReplayStats = function clearReplayStats() {
    ctx.replayEvents.value = [];
    ctx.replaySession.recorded = false;
    ctx.persistReplayEvents();
    ctx.showShareMessage?.('Cleared Replay stats.');
  };

  watch(ctx.activeTrack, (track) => {
    ctx.replaySession = {
      trackId: track?.id || '',
      startedAt: Date.now(),
      recorded: false
    };
  }, { immediate: true });

  watch([ctx.currentTime, ctx.duration, ctx.isPlaying], () => {
    ctx.updateReplaySession();
  });
}
