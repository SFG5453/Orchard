// Durable home for the state that answers "where was I?" -- the playback queue
// and the last page. Both used to live in the renderer's localStorage, which
// Chromium commits to disk on its own schedule: a force quit, an OS shutdown or
// a crash could drop the most recent writes, and the app came back a song or a
// page behind. Writing from the main process means the state is on disk under
// our control, and `flush` on quit closes the window entirely.
import {
  mkdirSync,
  readFileSync,
  renameSync,
  unlinkSync,
  writeFileSync
} from 'node:fs';
import path from 'node:path';

export const SESSION_STATE_FILENAME = 'session-state.json';

// Queue edits and navigation both fire in bursts, and each write is a file
// rename. Coalescing keeps that off the hot path without risking the data:
// anything still pending is flushed synchronously before the app exits.
const DEFAULT_WRITE_DELAY_MS = 400;

// A single stored key is a page entry or a playback queue, not a library.
const MAX_VALUE_BYTES = 512 * 1024;

export function readSessionState(filePath) {
  try {
    const parsed = JSON.parse(readFileSync(filePath, 'utf8'));
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

export function writeSessionState(filePath, state) {
  const temporaryPath = `${filePath}.${process.pid}.tmp`;

  mkdirSync(path.dirname(filePath), { recursive: true });
  try {
    // Written whole and renamed into place, so a quit mid-write leaves the
    // previous session readable rather than a truncated file.
    writeFileSync(temporaryPath, `${JSON.stringify(state)}\n`, {
      encoding: 'utf8',
      mode: 0o600
    });
    renameSync(temporaryPath, filePath);
  } catch (error) {
    try {
      unlinkSync(temporaryPath);
    } catch {
      // The temporary file may not have been created.
    }
    throw error;
  }

  return state;
}

export function createSessionStateStore({
  filePath,
  writeDelayMs = DEFAULT_WRITE_DELAY_MS
}) {
  const state = readSessionState(filePath);
  let timer = 0;
  let dirty = false;

  function commit() {
    if (timer) {
      clearTimeout(timer);
      timer = 0;
    }
    if (!dirty) return false;
    dirty = false;
    try {
      writeSessionState(filePath, state);
      return true;
    } catch {
      // Losing a session is a bad restore, not a broken app.
      return false;
    }
  }

  return {
    get(key) {
      return key ? state[key] ?? null : { ...state };
    },
    set(key, value) {
      if (!key || typeof key !== 'string') return false;
      // The renderer is the only writer, but it is also the least trusted
      // process in the app; a stored value is data, never a path or a command.
      const serialized = JSON.stringify(value ?? null);
      if (serialized.length > MAX_VALUE_BYTES) return false;
      if (JSON.stringify(state[key] ?? null) === serialized) return true;

      state[key] = JSON.parse(serialized);
      dirty = true;
      if (!timer) timer = setTimeout(commit, writeDelayMs);
      return true;
    },
    flush: commit
  };
}
