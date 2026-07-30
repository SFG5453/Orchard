// Generic parent-side handle on a model-inference utility process.
//
// Both the beat-tracking model and the vocal-separation model need the exact
// same lifecycle: fork lazily on first use, re-fork after any exit, bound the
// wait on a wedged inference and kill the child if it's exceeded, and resolve
// every failure path (fork failure, crash, timeout, send failure) to null
// rather than throwing. A broken model and a silent one are the same thing to
// the caller -- a routing decision back to whatever fallback that feature
// has -- so this is extracted once rather than kept as two copies of the same
// ~120 lines that would need to stay in sync. `beatModelHost.js` and
// `vocalMaskHost.js` are thin wrappers over this that only supply their own
// entry point and service name.
//
// The wire protocol is one message per request: {id, spectrogram, modelPath}
// in, {id, result} out. "spectrogram" is deliberately generic -- it is
// whatever tensor-shaped payload the target process's model expects.

// Bounds the wait, not the work: a wedged inference cannot be interrupted, so
// on expiry the child is killed and the request resolves null. Generous
// because the first request also pays session construction over the model.
const DEFAULT_TIMEOUT_MS = 30_000;

export function createModelProcessHost({
  entryPath,
  serviceName,
  modelPath = '',
  log = () => {},
  timeoutMs = DEFAULT_TIMEOUT_MS,
  // Injectable so tests can stand in a fake child; the default forks the real
  // utility process. Imported lazily because this module is also loaded by
  // plain-node tests, where 'electron' does not resolve.
  fork = async () => {
    const { utilityProcess } = await import('electron');
    return utilityProcess.fork(entryPath, [], { serviceName });
  }
} = {}) {
  let child = null;
  let spawning = null;
  let nextId = 0;
  const inFlight = new Map();

  function settle(id, result) {
    const pending = inFlight.get(id);
    if (!pending) return;
    inFlight.delete(id);
    clearTimeout(pending.timer);
    pending.resolve(result ?? null);
  }

  function settleAll(result) {
    for (const id of [...inFlight.keys()]) settle(id, result);
  }

  async function spawn() {
    if (child) return child;
    // One fork at a time: concurrent first requests share the same child.
    if (!spawning) {
      spawning = (async () => {
        const spawned = await fork();
        spawned.on('message', (message) => settle(Number(message?.id), message?.result));
        spawned.on('exit', (code) => {
          // Every in-flight request dies with the process. Resolving null
          // routes each one to the caller's fallback; the next request forks
          // a fresh child.
          if (child === spawned) child = null;
          settleAll(null);
          log(`${serviceName}-process-exit`, { code: Number(code) || 0 });
        });
        child = spawned;
        log(`${serviceName}-process-forked`, {});
        return spawned;
      })().finally(() => {
        spawning = null;
      });
    }
    return spawning;
  }

  /**
   * Runs one spectrogram through the model in the utility process. Resolves
   * to the process's verdict, or null whenever the child cannot answer.
   */
  async function run(spectrogram) {
    if (!spectrogram?.frames) return null;
    let target;
    try {
      target = await spawn();
    } catch (error) {
      log(`${serviceName}-process-fork-failed`, {
        errorMessage: String(error?.message || error || 'fork failed').slice(0, 500)
      });
      return null;
    }
    // The fork is awaited, so the child can already have died in the gap --
    // its exit handler ran before this request was ever registered, and
    // settleAll could not see it. Without this check the request would sit in
    // `inFlight` addressed to a dead process until the timeout expired.
    if (target !== child) {
      log(`${serviceName}-process-died-before-send`, {});
      return null;
    }
    const id = ++nextId;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        // A request this late means the child is wedged mid-inference, and an
        // ONNX run cannot be cancelled -- killing the process is the only way
        // to reclaim the cores. The exit handler settles everything else.
        log(`${serviceName}-process-timeout`, { timeoutMs });
        inFlight.delete(id);
        resolve(null);
        try {
          target.kill();
        } catch {}
      }, timeoutMs);
      inFlight.set(id, { resolve, timer });
      try {
        target.postMessage({ id, spectrogram, modelPath });
      } catch (error) {
        clearTimeout(timer);
        inFlight.delete(id);
        log(`${serviceName}-process-send-failed`, {
          errorMessage: String(error?.message || error || 'send failed').slice(0, 500)
        });
        resolve(null);
      }
    });
  }

  function stop() {
    settleAll(null);
    const stopping = child;
    child = null;
    try {
      stopping?.kill();
    } catch {}
  }

  return { run, stop };
}
