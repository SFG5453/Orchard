// Keeps browser-auth navigation failures distinct from navigations that YouTube
// intentionally supersedes with a redirect or client-side URL replacement.

export function isTrustedAuthUrl(value = '') {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && (
      url.hostname === 'youtube.com' ||
      url.hostname.endsWith('.youtube.com') ||
      url.hostname === 'google.com' ||
      url.hostname.endsWith('.google.com')
    );
  } catch {
    return false;
  }
}

export function isAuthSwitchDestinationUrl(value = '') {
  try {
    const url = new URL(value);
    const youtubeHost = url.hostname === 'youtube.com' || url.hostname.endsWith('.youtube.com');
    return url.protocol === 'https:' &&
      youtubeHost &&
      !/^\/channel_switcher\/?$/.test(url.pathname);
  } catch {
    return false;
  }
}

export function isSupersededAuthNavigation(error, attemptedUrl, currentUrl = '') {
  const aborted = error?.code === 'ERR_ABORTED' || error?.errno === -3;
  if (!aborted || !isTrustedAuthUrl(attemptedUrl)) return false;
  return !currentUrl || isTrustedAuthUrl(currentUrl);
}

export function observeAuthSwitchIdentity(state = {}, identity = '') {
  const currentIdentity = String(identity || '');
  if (!state.ready) {
    return {
      baseline: currentIdentity,
      ready: true,
      completed: false
    };
  }

  return {
    baseline: state.baseline,
    ready: true,
    completed: currentIdentity !== state.baseline
  };
}

export async function loadAuthWindowUrl(browserWindow, url) {
  try {
    await browserWindow.loadURL(url);
    return true;
  } catch (error) {
    const currentUrl = browserWindow.isDestroyed()
      ? ''
      : browserWindow.webContents.getURL();
    if (isSupersededAuthNavigation(error, url, currentUrl)) return false;
    throw error;
  }
}
