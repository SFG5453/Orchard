// Encapsulates authenticated browser-style YouTube Music requests used by main-process services.
import { createHash } from 'node:crypto';

export function createBrowserMusicApi({
  authState,
  musicBrowseRequest,
  musicBrowseRequests,
  youtubeMusicClientUserAgent,
  youtubeMusicClientVersion,
  youtubeMusicOrigin
}) {
  function browserAuthHeader(origin = youtubeMusicOrigin) {
    const cookie = authState.browser.cookie || '';
    const sapisid = /(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID)=([^;]+)/.exec(cookie)?.[1];
    if (!sapisid) return '';

    const timestamp = Math.floor(Date.now() / 1000);
    const hash = createHash('sha1')
      .update(`${timestamp} ${sapisid} ${origin}`)
      .digest('hex');
    return `SAPISIDHASH ${timestamp}_${hash}`;
  }

  function cookieWithPlaybackDefaults(cookie = '') {
    const source = cookie || authState.browser.cookie || '';
    if (!source) return '';

    const parts = source.split(';').map((part) => part.trim()).filter(Boolean);
    const names = new Set(parts.map((part) => part.split('=', 1)[0]));
    if (!names.has('SOCS')) parts.push('SOCS=CAI');
    if (!names.has('PREF')) parts.push('PREF=f2=8000000&hl=en');
    return parts.join('; ');
  }

  function browserMusicContext() {
    return {
      context: {
        client: {
          clientName: 'WEB_REMIX',
          clientVersion: youtubeMusicClientVersion,
          hl: 'en',
          gl: 'US',
          visitorData: authState.browser.visitorData || undefined
        },
        user: authState.browser.dataSyncId ? {
          onBehalfOfUser: authState.browser.dataSyncId
        } : undefined
      }
    };
  }

  async function rawBrowserMusicBrowse(request) {
    const authorization = browserAuthHeader(youtubeMusicOrigin);
    if (!authorization || !authState.browser.cookie) {
      throw new Error('Browser YouTube Music login is unavailable.');
    }

    const response = await fetch(`${youtubeMusicOrigin}/youtubei/v1/browse?prettyPrint=false`, {
      method: 'POST',
      headers: {
        'Authorization': authorization,
        'Content-Type': 'application/json',
        'Cookie': cookieWithPlaybackDefaults(),
        'X-YouTube-Client-Name': '67',
        'X-YouTube-Client-Version': youtubeMusicClientVersion,
        'X-Origin': youtubeMusicOrigin,
        'Origin': youtubeMusicOrigin,
        'Referer': `${youtubeMusicOrigin}/`,
        'User-Agent': youtubeMusicClientUserAgent,
        'Accept': 'application/json'
      },
      body: JSON.stringify({
        ...browserMusicContext(),
        ...request
      })
    });
    const text = await response.text();
    let data;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = {};
    }

    if (!response.ok) {
      const message = data.error?.message || `Browser-cookie YouTube Music browse failed with HTTP ${response.status}`;
      const error = new Error(message);
      error.status = response.status;
      error.info = text;
      throw error;
    }

    return data;
  }

  async function sendBrowserHistoryStat(sourceUrl, params = {}) {
    const authorization = browserAuthHeader(youtubeMusicOrigin);
    if (!authorization || !authState.browser.cookie) {
      throw new Error('Browser YouTube Music login is unavailable.');
    }

    const url = new URL(String(sourceUrl || '').replace('https://s.', 'https://music.'));
    url.searchParams.set('ver', '2');
    url.searchParams.set('c', 'ytmusic');
    url.searchParams.set('cbrver', youtubeMusicClientVersion);
    url.searchParams.set('cver', youtubeMusicClientVersion);
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));

    const response = await fetch(url, {
      headers: {
        Authorization: authorization,
        Cookie: cookieWithPlaybackDefaults(),
        Origin: youtubeMusicOrigin,
        Referer: `${youtubeMusicOrigin}/`,
        'User-Agent': youtubeMusicClientUserAgent,
        'X-Origin': youtubeMusicOrigin
      }
    });

    if (!response.ok) {
      const error = new Error(`Browser YouTube Music history request failed with HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
  }

  async function resolveMusicCollectionWithBrowserAuth(kind, payload = {}) {
    const errors = [];

    for (const request of musicBrowseRequests(kind, payload)) {
      try {
        return {
          browseId: request.browseId,
          data: await rawBrowserMusicBrowse(request),
          browse: (browsePayload) => rawBrowserMusicBrowse(musicBrowseRequest('artist', browsePayload)),
          search: () => null,
          continue: (continuation) => rawBrowserMusicBrowse({
            continuation
          })
        };
      } catch (error) {
        errors.push(error);
      }
    }

    throw errors[errors.length - 1] || new Error('Browser-cookie browse failed');
  }

  return {
    cookieWithPlaybackDefaults,
    rawBrowserMusicBrowse,
    resolveMusicCollectionWithBrowserAuth,
    sendBrowserHistoryStat
  };
}
