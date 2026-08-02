// Reads renderer-owned setup state without widening the preload API solely for startup gating.
export async function welcomeRequiredAtLaunch(window) {
  if (!window || window.isDestroyed()) return true;

  try {
    return !await window.webContents.executeJavaScript(`(() => {
      try {
        return Boolean(JSON.parse(localStorage.getItem('orchard:setup-state') || '{}').welcomeCompleted);
      } catch {
        return false;
      }
    })()`);
  } catch {
    return true;
  }
}
