const HISTORY_LIMIT = 30;

export const DEFAULT_QUEUE_LAYOUT = 'upNext';

export const QUEUE_LAYOUT_OPTIONS = [
  { label: 'Up next', value: 'upNext' },
  { label: 'Continuous', value: 'continuous' }
];

export function normalizeQueueLayout(value) {
  return QUEUE_LAYOUT_OPTIONS.some((option) => option.value === value) ? value : DEFAULT_QUEUE_LAYOUT;
}

/**
 * Flattens playback state into the single YouTube Music style list: everything already
 * played (oldest first), the current track, then everything still queued.
 * `history` arrives most recent first, which is the reverse of how it reads on screen.
 */
export function continuousQueueEntries({ history = [], activeTrack = null, queue = [] } = {}) {
  const entries = [];
  const startsSection = (section) => entries[entries.length - 1]?.section !== section;

  history
    .map((track, historyIndex) => ({ track, historyIndex }))
    .filter(({ track }) => track?.id)
    .reverse()
    .forEach(({ track, historyIndex }) => {
      entries.push({
        key: `previous-${historyIndex}-${track.id}`,
        section: 'previous',
        sectionStart: startsSection('previous'),
        track,
        historyIndex,
        queueIndex: -1
      });
    });

  if (activeTrack?.id) {
    entries.push({
      key: `current-${activeTrack.id}`,
      section: 'current',
      sectionStart: startsSection('current'),
      track: activeTrack,
      historyIndex: -1,
      queueIndex: -1
    });
  }

  queue.forEach((track, queueIndex) => {
    if (!track?.id) return;
    entries.push({
      key: `next-${queueIndex}-${track.id}`,
      section: 'next',
      sectionStart: startsSection('next'),
      track,
      historyIndex: -1,
      queueIndex
    });
  });

  return entries;
}

/**
 * Jumping back to an already played track pushes the tracks in between back onto the
 * front of the queue, so the list the listener sees keeps its shape.
 */
export function rewindToHistoryEntry({ history = [], activeTrack = null, queue = [], historyIndex = 0 } = {}) {
  const track = history[historyIndex];
  if (!track?.id || historyIndex < 0) return null;

  const requeued = [...history.slice(0, historyIndex)].reverse();
  if (activeTrack?.id) requeued.push(activeTrack);

  return {
    track,
    history: history.slice(historyIndex + 1),
    queue: [...requeued, ...queue].filter((item) => item?.id)
  };
}

/**
 * Jumping ahead moves the skipped tracks into history instead of dropping them.
 */
export function advanceToQueueEntry({ history = [], activeTrack = null, queue = [], queueIndex = 0 } = {}) {
  const track = queue[queueIndex];
  if (!track?.id || queueIndex < 0) return null;

  const played = [...queue.slice(0, queueIndex)].reverse();
  if (activeTrack?.id) played.push(activeTrack);

  return {
    track,
    history: [...played, ...history].filter((item) => item?.id).slice(0, HISTORY_LIMIT),
    queue: queue.slice(queueIndex + 1)
  };
}
