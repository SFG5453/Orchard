import { computed, ref } from 'vue';
import { sanitizedTrack } from './queuePersistence.js';

const SESSION_HISTORY_STORAGE_KEY = 'orchard:session-history';
const MAX_SESSION_EVENTS = 80;
const MAX_QUEUE_SNAPSHOT = 40;

function storageAvailable() {
  return typeof window !== 'undefined' && Boolean(window.localStorage);
}

function cleanText(value = '') {
  return String(value || '').trim();
}

function actionLabel(action = '') {
  if (action === 'crossfade') return 'Crossfaded';
  if (action === 'ended') return 'Finished';
  if (action === 'previous') return 'Back';
  if (action === 'smart-queue') return 'Smart queue';
  if (action === 'restore') return 'Restored';
  if (action === 'manual') return 'Started';
  return 'Played';
}

function readSessionHistory() {
  if (!storageAvailable()) return [];

  try {
    const parsed = JSON.parse(window.localStorage.getItem(SESSION_HISTORY_STORAGE_KEY) || '[]');
    return Array.isArray(parsed) ? parsed.slice(0, MAX_SESSION_EVENTS) : [];
  } catch {
    return [];
  }
}

function writeSessionHistory(events) {
  if (!storageAvailable()) return;

  try {
    window.localStorage.setItem(SESSION_HISTORY_STORAGE_KEY, JSON.stringify(events.slice(0, MAX_SESSION_EVENTS)));
  } catch {
    // Session history is helpful context, not required playback state.
  }
}

function queueSnapshot(items = []) {
  return items
    .map(sanitizedTrack)
    .filter(Boolean)
    .slice(0, MAX_QUEUE_SNAPSHOT);
}

export function installSessionHistoryActions(ctx) {
  ctx.sessionHistory = ref(readSessionHistory());

  ctx.sessionHistoryCount = computed(() => ctx.sessionHistory.value.length);

  ctx.recentSessionTracks = computed(() => {
    const seen = new Set();
    return ctx.sessionHistory.value
      .map((event) => event.track)
      .filter((track) => {
        if (!track?.id || seen.has(track.id)) return false;
        seen.add(track.id);
        return true;
      })
      .slice(0, 20);
  });

  ctx.recordSessionEvent = function recordSessionEvent(action, track, details = {}) {
    const cleanTrack = sanitizedTrack(track);
    if (!cleanTrack?.id) return;

    const previous = ctx.sessionHistory.value[0];
    if (
      previous?.track?.id === cleanTrack.id &&
      previous.action === action &&
      Date.now() - Number(previous.playedAt || 0) < 2500
    ) {
      return;
    }

    const event = {
      id: `${Date.now()}-${cleanTrack.id}-${action}`,
      action,
      label: actionLabel(action),
      playedAt: Date.now(),
      track: cleanTrack,
      fromTrack: sanitizedTrack(details.fromTrack),
      queue: queueSnapshot(details.queue || ctx.queue.value),
      queueOrigin: cleanText(details.queueOrigin || ctx.activeQueueOriginLabel?.value || ''),
      progressSeconds: Math.max(0, Math.round(Number(details.progressSeconds || 0))),
      durationSeconds: Math.max(0, Math.round(Number(details.durationSeconds || cleanTrack.durationSeconds || 0)))
    };

    ctx.sessionHistory.value = [event, ...ctx.sessionHistory.value].slice(0, MAX_SESSION_EVENTS);
    writeSessionHistory(ctx.sessionHistory.value);
  };

  ctx.clearSessionHistory = function clearSessionHistory() {
    ctx.sessionHistory.value = [];
    writeSessionHistory([]);
    ctx.showShareMessage?.('Cleared now playing history.');
  };

  ctx.restoreSessionEvent = function restoreSessionEvent(event) {
    if (!event?.track?.id) return;
    const source = [event.track, ...(event.queue || [])]
      .filter(ctx.isPlayableTrack)
      .filter((track, index, tracks) => tracks.findIndex((item) => item.id === track.id) === index);
    if (!source.length) return;

    ctx.playTrack(source[0], {
      queueSource: source,
      sessionAction: 'restore'
    });
  };

  ctx.sessionEventMeta = function sessionEventMeta(event) {
    const artist = ctx.itemMeta(event.track);
    const origin = event.queueOrigin ? `from ${event.queueOrigin}` : '';
    return [artist, origin].filter(Boolean).join(' / ');
  };

  ctx.sessionEventTime = function sessionEventTime(event) {
    const formatter = new Intl.DateTimeFormat([], { hour: 'numeric', minute: '2-digit' });
    return formatter.format(new Date(event.playedAt || Date.now()));
  };
}
