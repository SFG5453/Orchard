// Where "where was I?" state is kept: the playback queue and the last page.
//
// Both used to be written straight to localStorage, which Chromium flushes to
// disk on its own schedule -- so a force quit, an OS shutdown or a crash could
// drop the newest writes and bring the app back a song or a page behind. In the
// desktop app these now go through the main process, which owns an atomic file
// write and flushes it before the app exits.
//
// localStorage stays as the fallback for builds without the bridge, and as the
// migration path: an existing install reads its old value once, and the next
// change writes it to the durable store.
function bridge() {
  return globalThis.window?.orchardSessionState || null;
}

function localStorageAvailable() {
  return typeof window !== 'undefined' && Boolean(window.localStorage);
}

export function readSessionValue(key, fallback = null) {
  const stored = bridge()?.get(key);
  if (stored !== null && stored !== undefined) {
    // Values are stored serialized (see writeSessionValue). Objects can still
    // come back from a store written before that, so both shapes are read.
    if (typeof stored !== 'string') return stored;
    try {
      return JSON.parse(stored);
    } catch {
      return fallback;
    }
  }

  if (!localStorageAvailable()) return fallback;
  try {
    const raw = window.localStorage.getItem(key);
    return raw === null ? fallback : JSON.parse(raw);
  } catch {
    return fallback;
  }
}

export function writeSessionValue(key, value) {
  const durable = bridge();
  if (durable) {
    // Serialized before it crosses the bridge, the way localStorage always
    // did. The IPC channel structured-clones its arguments, and app state
    // reaches here as reactive proxies, which clone throws on.
    try {
      durable.set(key, JSON.stringify(value ?? null));
    } catch {
      // A value that will not serialize is a session we cannot restore, not a
      // navigation we should block.
    }
    return;
  }

  if (!localStorageAvailable()) return;
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Restoring a session is useful, but storage failures must never block
    // playback or navigation.
  }
}

export function clearSessionValue(key) {
  bridge()?.set(key, null);

  if (!localStorageAvailable()) return;
  try {
    window.localStorage.removeItem(key);
  } catch {
    // Best-effort, as above.
  }
}
