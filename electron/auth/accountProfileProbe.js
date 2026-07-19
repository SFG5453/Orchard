// Probes an isolated YouTube Music window for account metadata without exposing it to the renderer.
import fs from 'node:fs';
import path from 'node:path';

const profileFilename = 'account-profile.json';

function normalizedProfile(value) {
  if (!value || typeof value !== 'object') return null;
  const name = String(value.name || '').trim();
  const byline = String(value.byline || '').trim();
  const thumbnail = String(value.thumbnail || '').trim();
  const channelId = String(value.channelId || '').trim();
  const channelUrl = String(value.channelUrl || '').trim();
  if (!name && !byline && !thumbnail && !channelId && !channelUrl) return null;
  return { name: name || 'Signed in', byline, thumbnail: thumbnail || null, channelId, channelUrl };
}

function decodeHtml(value = '') {
  return String(value || '')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

function metaContent(html, property) {
  const escaped = property.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(`<meta[^>]+(?:property|name)=["']${escaped}["'][^>]+content=["']([^"']+)["']`, 'i');
  return decodeHtml(html.match(pattern)?.[1] || '');
}

async function publicChannelProfile(profile) {
  const normalized = normalizedProfile(profile);
  if (!normalized?.channelId && !normalized?.channelUrl) return normalized;
  if (normalized.name !== 'Signed in' && normalized.thumbnail) return normalized;

  const target = normalized.channelId
    ? `https://www.youtube.com/channel/${encodeURIComponent(normalized.channelId)}`
    : normalized.channelUrl;

  try {
    const response = await fetch(target, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126 Safari/537.36',
        'Accept': 'text/html'
      }
    });
    if (!response.ok) return normalized;
    const html = await response.text();
    const name = metaContent(html, 'og:title');
    const thumbnail = metaContent(html, 'og:image');
    return normalizedProfile({
      ...normalized,
      name: normalized.name !== 'Signed in' ? normalized.name : name,
      thumbnail: normalized.thumbnail || thumbnail
    });
  } catch {
    return normalized;
  }
}

export function createAccountProfileProbe({ allowDevTools = false, BrowserWindow, partition, userDataPath }) {
  const profilePath = path.join(userDataPath, profileFilename);
  let cachedProfile = null;
  let probePromise = null;

  try {
    cachedProfile = normalizedProfile(JSON.parse(fs.readFileSync(profilePath, 'utf8')));
  } catch {
    cachedProfile = null;
  }

  function cached() {
    return cachedProfile;
  }

  function save(profile) {
    const normalized = normalizedProfile(profile);
    if (!normalized) return null;
    cachedProfile = normalized;
    try {
      fs.writeFileSync(profilePath, `${JSON.stringify(normalized, null, 2)}\n`, { mode: 0o600 });
    } catch {
      // The in-memory profile still improves the current session.
    }
    return normalized;
  }

  function clear() {
    cachedProfile = null;
    try {
      fs.rmSync(profilePath, { force: true });
    } catch {
      // Sign-out should continue even if the cache cannot be removed.
    }
  }

  async function capture(window) {
    return window.webContents.executeJavaScript(`
      (async () => {
        const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
        const clean = (value) => String(value || '').replace(/\\s+/g, ' ').trim();
        const ignored = /^(account|account menu|manage your google account|switch account|sign out|youtube|youtube music)$/i;
        const channelIdFromHref = (href = '') => href.match(/\\/channel\\/(UC[a-zA-Z0-9_-]{22})/)?.[1] || '';
        const channelUrlFromHref = (href = '') => {
          const match = href.match(/(?:https?:\\/\\/www\\.youtube\\.com)?\\/(?:channel\\/UC[a-zA-Z0-9_-]{22}|@[^/?#]+)/);
          return match ? new URL(match[0], 'https://www.youtube.com').href : '';
        };
        let clicked = false;

        for (let attempt = 0; attempt < 30; attempt += 1) {
          const button = document.querySelector(
            '#avatar-btn, ytmusic-settings-button button, button[aria-label*="Account" i], button[aria-label*="profile" i], ytmusic-nav-bar #avatar img, ytmusic-nav-bar img[alt*="avatar" i]'
          );
          if (button && !clicked) {
            (button.closest('button, #avatar-btn') || button).click();
            clicked = true;
            await wait(250);
          }

          const area = document.querySelector(
            'ytd-active-account-header-renderer, yt-multi-page-menu-header-renderer, ytmusic-account-info, tp-yt-iron-dropdown'
          );
          const image = area?.querySelector('img[src]') || button?.querySelector?.('img[src]') || (button?.matches?.('img[src]') ? button : null);
          const link = area?.querySelector('a[href*="/channel/UC"], a[href*="/@"], a[href*="youtube.com/channel/UC"], a[href*="youtube.com/@"]');
          const channelUrl = channelUrlFromHref(link?.href || '');
          const channelId = channelIdFromHref(channelUrl);
          const lines = String(area?.innerText || '').split('\\n').map(clean).filter(Boolean);
          const byline = lines.find((line) => /^@/.test(line) || /^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(line)) || '';
          const name = lines.find((line) => !ignored.test(line) && line !== byline && !/^(privacy|terms|add account)/i.test(line)) || '';
          const thumbnail = image?.currentSrc || image?.src || '';
          if ((thumbnail && !/data:image/i.test(thumbnail)) || name || channelId || channelUrl) {
            return { name, byline, thumbnail, channelId, channelUrl };
          }
          await wait(150);
        }
        return null;
      })()
    `);
  }

  async function probe() {
    if (probePromise) return probePromise;
    probePromise = (async () => {
      const window = new BrowserWindow({
        show: false,
        webPreferences: { partition, devTools: allowDevTools, nodeIntegration: false, contextIsolation: true, sandbox: true }
      });
      try {
        await window.loadURL('https://music.youtube.com/');
        return save(await publicChannelProfile(await capture(window)));
      } finally {
        if (!window.isDestroyed()) window.destroy();
      }
    })().finally(() => {
      probePromise = null;
    });
    return probePromise;
  }

  return { cached, clear, probe, save };
}
