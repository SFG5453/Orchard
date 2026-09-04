export function isTauriRuntime(target = globalThis) {
  return Boolean(target.__TAURI_INTERNALS__);
}

