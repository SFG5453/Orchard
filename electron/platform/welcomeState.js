// Reads renderer-owned setup state without widening the preload API solely for startup gating.
export async function welcomeRequiredAtLaunch(window, { currentVersion, resetVersion }) {
  if (!window || window.isDestroyed()) return true;

  try {
    const state = await window.webContents.executeJavaScript(`(() => {
      try {
        const setup = JSON.parse(localStorage.getItem('orchard:setup-state') || '{}');
        return {
          completed: Boolean(setup.welcomeCompleted),
          resetVersion: localStorage.getItem('orchard:welcome-reset-version') || ''
        };
      } catch {
        return { completed: false, resetVersion: '' };
      }
    })()`);
    const resetPending = currentVersion === resetVersion && state?.resetVersion !== resetVersion;
    return !state?.completed || resetPending;
  } catch {
    return true;
  }
}
