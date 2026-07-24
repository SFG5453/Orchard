// Owns browser authentication windows, cached InnerTube clients, and sign-out cleanup.
import { createRequire } from 'node:module';
import path from 'node:path';
import { Innertube, UniversalCache } from 'youtubei.js';
import { createAccountProfileProbe } from './accountProfileProbe.js';
import { createBrowserMusicFetch } from './browserMusicApi.js';
import {
  isAuthSwitchDestinationUrl,
  loadAuthWindowUrl,
  observeAuthSwitchIdentity
} from './authWindowNavigation.js';
import {
  collectYouTubeAuthCookie,
  hasYouTubeLoginCookie,
  youtubeAccountIdentity
} from './youtubeAuthCookies.js';

const require = createRequire(import.meta.url);
const { app, BrowserWindow, session: electronSession } = require('electron');

const oauthCredentialsKey = 'youtubei_oauth_credentials';
const browserAuthPartition = 'persist:orchard-youtube-auth';
const authRefreshDelayMs = 300;

function createYoutubeCache(scope) {
  return new UniversalCache(true, path.join(app.getPath('userData'), 'youtubei-cache', scope));
}

/**
 * Owns OAuth/browser sessions, sign-in windows, and cached InnerTube clients.
 * Browser-window listeners and refresh timers are released by sign-out/window
 * closure; Electron owns persistent partition lifetime until process exit.
 * @param {object} dependencies Main-process UI and state callbacks.
 * @returns {object} Authentication state plus browse/playback client factories.
 */
export function createAuthService({
  accountSummary,
  allowDevTools = false,
  getMainWindow,
  onAuthState,
  youtubeMusicClientUserAgent,
  youtubeMusicClientVersion,
  youtubeMusicOrigin
}) {
  const accountProfile = createAccountProfileProbe({
    allowDevTools,
    BrowserWindow,
    partition: browserAuthPartition,
    userDataPath: app.getPath('userData')
  });
  let innertubePromise;
  let innertubeInstance;
  let browserInnertubePromise;
  let browserInnertubeIdentity = '';
  let guestInnertubePromise;
  let signInPromise;
  let authEventsBound = false;
  let browserAuthWindow;
  let browserAuthMode = '';
  let authSwitchIdentity = {};
  let authRefreshTimer;
  const authState = {
    status: 'signed_out',
    pending: null,
    error: '',
    user: null,
    browser: {
      cookie: '',
      visitorData: '',
      dataSyncId: '',
      poToken: ''
    }
  };
  const browserMusicFetch = createBrowserMusicFetch({
    authState,
    youtubeMusicClientUserAgent,
    youtubeMusicClientVersion,
    youtubeMusicOrigin
  });

  function getInnertube() {
    if (!innertubePromise) {
      innertubePromise = Innertube.create({
        cache: createYoutubeCache('oauth'),
        client_type: 'WEB_REMIX',
        retrieve_player: true,
        generate_session_locally: true
      }).then((yt) => {
        innertubeInstance = yt;
        bindAuthEvents(yt);
        return yt;
      });
    }

    return innertubePromise;
  }

  function getGuestInnertube() {
    if (!guestInnertubePromise) {
      guestInnertubePromise = Innertube.create({
        cache: createYoutubeCache('guest'),
        client_type: 'WEB_REMIX',
        retrieve_player: true,
        generate_session_locally: true
      });
    }

    return guestInnertubePromise;
  }

  function getBrowserInnertube() {
    if (!hasBrowserLoginCookie()) return null;

    const identity = browserIdentity();
    if (!browserInnertubePromise || browserInnertubeIdentity !== identity) {
      browserInnertubeIdentity = identity;
      browserInnertubePromise = Innertube.create({
        cache: createYoutubeCache('browser'),
        client_type: 'WEB_REMIX',
        retrieve_player: true,
        generate_session_locally: true,
        cookie: authState.browser.cookie,
        visitor_data: authState.browser.visitorData || undefined,
        on_behalf_of_user: authState.browser.dataSyncId || undefined,
        po_token: authState.browser.poToken || undefined,
        fetch: browserMusicFetch
      });
    }

    return browserInnertubePromise;
  }

  function publicAuthState() {
    const browserSignedIn = hasBrowserLoginCookie();

    return {
      signedIn: Boolean(innertubeInstance?.session.logged_in) || browserSignedIn,
      status: innertubeInstance?.session.logged_in || browserSignedIn ? 'signed_in' : authState.status,
      pending: authState.pending,
      error: authState.error,
      user: authState.user
    };
  }

  function publishAuthState() {
    onAuthState?.(publicAuthState());
  }

  function scheduleAuthRefresh() {
    if (authRefreshTimer) clearTimeout(authRefreshTimer);

    authRefreshTimer = setTimeout(() => {
      authRefreshTimer = null;
      const mainWindow = getMainWindow?.();
      if (!mainWindow || mainWindow.isDestroyed() || mainWindow.webContents.isDestroyed()) return;
      mainWindow.webContents.reload();
    }, authRefreshDelayMs);
  }

  function bindAuthEvents(yt) {
    if (authEventsBound) return;
    authEventsBound = true;

    yt.session.on('auth-pending', (pending) => {
      authState.status = 'pending';
      authState.pending = {
        userCode: pending.user_code,
        verificationUrl: pending.verification_url,
        expiresIn: pending.expires_in
      };
      authState.error = '';
      publishAuthState();
    });

    yt.session.on('auth', () => {
      const shouldRefresh = authState.status === 'starting' || authState.status === 'pending';
      authState.status = 'signed_in';
      authState.pending = null;
      authState.error = '';

      queueMicrotask(async () => {
        try {
          await yt.session.oauth.cacheCredentials();
          authState.user = await accountSummary(yt);
        } catch (error) {
          console.warn(`Could not finish auth bookkeeping: ${error.message}`);
        } finally {
          publishAuthState();
          if (shouldRefresh) scheduleAuthRefresh();
        }
      });
    });

    yt.session.on('update-credentials', async () => {
      try {
        await yt.session.oauth.cacheCredentials();
      } catch (error) {
        console.warn(`Could not update OAuth cache: ${error.message}`);
      }
    });

    yt.session.on('auth-error', (error) => {
      authState.status = 'signed_out';
      authState.pending = null;
      authState.error = error.message;
      publishAuthState();
    });
  }

  function hasBrowserLoginCookie() {
    return hasYouTubeLoginCookie(authState.browser.cookie);
  }

  function browserIdentity() {
    return `${authState.browser.cookie}\n${authState.browser.dataSyncId}\n${authState.browser.poToken}`;
  }

  function browserSwitchIdentity() {
    return youtubeAccountIdentity(authState.browser.cookie, authState.browser.dataSyncId);
  }

  function refreshSwitchedBrowserAccount() {
    browserInnertubePromise = null;
    browserInnertubeIdentity = '';
    authState.user = { name: 'Signed in', byline: 'YouTube Music', thumbnail: null };
    publishAuthState();

    const identity = browserIdentity();
    void Promise.resolve(getBrowserInnertube())
      .then((yt) => refreshBrowserAccountSummary(yt, identity))
      .catch((error) => console.warn(`Could not refresh switched browser account: ${error.message}`));
  }

  function usefulAccountProfile(profile) {
    return Boolean(profile?.thumbnail || profile?.channelId || (profile?.name && profile.name !== 'Signed in'));
  }

  async function refreshBrowserAccountSummary(yt, identity) {
    try {
      const summary = await accountSummary(yt, true);
      const cached = accountProfile.cached();
      const profile = usefulAccountProfile(summary)
        ? accountProfile.save(summary)
        : usefulAccountProfile(cached) ? cached : await accountProfile.probe() || summary;
      if (identity !== browserIdentity() || !usefulAccountProfile(profile)) return;
      authState.user = profile;
      publishAuthState();
    } catch (error) {
      console.warn(`Could not capture browser account profile: ${error.message}`);
    }
  }

  function normalizeDataSyncId(value = '') {
    const normalized = String(value || '').trim();
    if (!normalized || normalized.toLowerCase() === 'null') return '';
    if (!normalized.includes('||')) return normalized;
    if (normalized.endsWith('||')) return normalized.split('||')[0];
    return normalized.split('||').pop() || '';
  }

  async function collectBrowserCookies() {
    const authSession = electronSession.fromPartition(browserAuthPartition);
    authState.browser.cookie = await collectYouTubeAuthCookie(authSession);
  }

  async function captureBrowserPageAuth(webContents) {
    try {
      const pageAuth = await webContents.executeJavaScript(`
        (() => {
          const findScriptValue = (key) => {
            for (const script of document.querySelectorAll('script')) {
              const match = script.textContent && script.textContent.match(new RegExp('"' + key + '":"([^"]+)"'));
              if (match) return match[1];
            }
            return '';
          };
          const cfg = window.ytcfg && window.ytcfg.get ? window.ytcfg : null;
          const legacy = window.yt && window.yt.config_ ? window.yt.config_ : {};
          return {
            visitorData: (cfg && cfg.get('VISITOR_DATA')) || legacy.VISITOR_DATA || findScriptValue('VISITOR_DATA') || '',
            dataSyncId:
              (cfg && cfg.get('DELEGATED_SESSION_ID')) ||
              legacy.DELEGATED_SESSION_ID ||
              findScriptValue('DELEGATED_SESSION_ID') ||
              (cfg && cfg.get('DATASYNC_ID')) ||
              legacy.DATASYNC_ID ||
              findScriptValue('DATASYNC_ID') ||
              '',
            poToken: (cfg && cfg.get('PO_TOKEN')) || findScriptValue('PO_TOKEN') || ''
          };
        })()
      `);

      if (pageAuth?.visitorData) authState.browser.visitorData = pageAuth.visitorData;
      if (pageAuth?.dataSyncId) authState.browser.dataSyncId = normalizeDataSyncId(pageAuth.dataSyncId);
      if (pageAuth?.poToken) authState.browser.poToken = pageAuth.poToken;
    } catch (error) {
      console.warn(`Could not capture YouTube page auth values: ${error.message}`);
    }
  }

  async function refreshBrowserAuth(webContents) {
    const previousIdentity = browserIdentity();
    if (webContents) await captureBrowserPageAuth(webContents);
    const wasSigningIn = authState.status === 'starting' || authState.status === 'pending';
    const wasSignedIn = publicAuthState().signedIn;
    await collectBrowserCookies();
    const identityChanged = browserIdentity() !== previousIdentity;
    if (identityChanged) {
      browserInnertubePromise = null;
      browserInnertubeIdentity = '';
    }

    if (hasBrowserLoginCookie()) {
      authState.status = 'signed_in';
      authState.pending = null;
      authState.error = '';
      if (identityChanged || !usefulAccountProfile(authState.user)) {
        authState.user = accountProfile.cached() || { name: 'Signed in', byline: 'YouTube Music', thumbnail: null };
        const identity = browserIdentity();
        void Promise.resolve(getBrowserInnertube())
          .then((yt) => refreshBrowserAccountSummary(yt, identity))
          .catch((error) => console.warn(`Could not start browser account refresh: ${error.message}`));
      }
      publishAuthState();
      if (wasSigningIn && !wasSignedIn) scheduleAuthRefresh();
    }
  }

  async function openBrowserAuthWindow({ mode, title, url }) {
    if (browserAuthWindow && !browserAuthWindow.isDestroyed()) {
      browserAuthMode = mode;
      authSwitchIdentity = {};
      await loadAuthWindowUrl(browserAuthWindow, url);
      browserAuthWindow.focus();
      return publicAuthState();
    }

    browserAuthMode = mode;
    authSwitchIdentity = {};

    browserAuthWindow = new BrowserWindow({
      width: 1120,
      height: 820,
      title,
      webPreferences: {
        devTools: allowDevTools,
        partition: browserAuthPartition,
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true
      }
    });

    browserAuthWindow.webContents.setWindowOpenHandler(({ url }) => {
      const window = browserAuthWindow;
      if (window && !window.isDestroyed()) {
        void loadAuthWindowUrl(window, url)
          .catch((error) => console.warn(`Could not open browser auth page: ${error.message}`));
      }
      return { action: 'deny' };
    });

    browserAuthWindow.webContents.on('did-finish-load', async () => {
      const url = browserAuthWindow?.webContents.getURL() || '';
      if (/https?:\/\/([^/]+\.)?(youtube|google)\.com/i.test(url)) {
        await refreshBrowserAuth(browserAuthWindow.webContents);
        if (browserAuthMode === 'switch') {
          const chooserWasLoaded = Boolean(authSwitchIdentity.ready);
          authSwitchIdentity = observeAuthSwitchIdentity(authSwitchIdentity, browserSwitchIdentity());
          if (authSwitchIdentity.completed || (chooserWasLoaded && isAuthSwitchDestinationUrl(url))) {
            browserAuthMode = '';
            refreshSwitchedBrowserAccount();
            browserAuthWindow?.close();
            scheduleAuthRefresh();
          }
        }
      }
    });

    browserAuthWindow.on('closed', async () => {
      const closedMode = browserAuthMode;
      browserAuthWindow = null;
      browserAuthMode = '';
      await refreshBrowserAuth();
      const completedSwitch = closedMode === 'switch' &&
        authSwitchIdentity.ready &&
        browserSwitchIdentity() !== authSwitchIdentity.baseline;
      authSwitchIdentity = {};
      if (!hasBrowserLoginCookie() && authState.status === 'starting') {
        authState.status = 'signed_out';
        authState.error = 'Browser sign-in was closed before YouTube cookies were captured.';
        publishAuthState();
      }
      if (completedSwitch) scheduleAuthRefresh();
    });

    await loadAuthWindowUrl(browserAuthWindow, url);
    return publicAuthState();
  }

  async function startBrowserSignIn() {
    accountProfile.clear();
    authState.status = 'starting';
    authState.pending = null;
    authState.error = '';
    publishAuthState();
    return openBrowserAuthWindow({
      mode: 'sign-in',
      title: 'Sign in to YouTube Music',
      url: 'https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com'
    });
  }

  async function startAccountSwitch() {
    if (!hasBrowserLoginCookie()) throw new Error('Browser YouTube Music login is unavailable.');
    accountProfile.clear();
    return openBrowserAuthWindow({
      mode: 'switch',
      title: 'Choose a YouTube account',
      url: 'https://www.youtube.com/channel_switcher'
    });
  }

  async function cachedCredentials(yt) {
    const data = await yt.session.cache?.get(oauthCredentialsKey);
    if (!data) return null;

    try {
      const raw = new TextDecoder().decode(data).trim();
      if (!raw) {
        await yt.session.cache?.remove(oauthCredentialsKey);
        return null;
      }

      const credentials = JSON.parse(raw);
      return yt.session.oauth.validateTokens(credentials) ? credentials : null;
    } catch (error) {
      await yt.session.cache?.remove(oauthCredentialsKey);
      console.warn(`Discarded invalid OAuth cache: ${error.message}`);
      return null;
    }
  }

  async function restoreCachedSignIn(yt) {
    if (yt.session.logged_in) return true;
    const credentials = await cachedCredentials(yt);
    if (!credentials) return false;

    await yt.session.signIn(credentials);
    authState.status = 'signed_in';
    authState.pending = null;
    authState.error = '';
    authState.user = await accountSummary(yt);
    return true;
  }

  async function startInteractiveSignIn() {
    const yt = await getInnertube();
    if (yt.session.logged_in) return publicAuthState();
    if (signInPromise) return publicAuthState();

    if (await restoreCachedSignIn(yt)) {
      publishAuthState();
      return publicAuthState();
    }

    authState.status = 'starting';
    authState.pending = null;
    authState.error = '';
    publishAuthState();

    signInPromise = yt.session.signIn()
      .catch((error) => {
        authState.status = 'signed_out';
        authState.pending = null;
        authState.error = error.message;
        publishAuthState();
      })
      .finally(() => {
        signInPromise = null;
      });

    return publicAuthState();
  }

  async function signOutAuth() {
    const yt = await getInnertube();
    if (yt.session.logged_in) await yt.session.signOut();
    await yt.session.oauth.removeCache();
    await electronSession.fromPartition(browserAuthPartition).clearStorageData({ storages: ['cookies'] });
    authState.browser = { cookie: '', visitorData: '', dataSyncId: '', poToken: '' };
    browserInnertubePromise = null;
    browserInnertubeIdentity = '';
    authState.status = 'signed_out';
    authState.pending = null;
    authState.error = '';
    authState.user = null;
    accountProfile.clear();
    publishAuthState();
    scheduleAuthRefresh();
    return publicAuthState();
  }

  async function ensureSignedIn() {
    const yt = await getInnertube();
    if (yt.session.logged_in) return yt;
    if (await restoreCachedSignIn(yt)) return yt;
    const browserYt = getBrowserInnertube();
    if (browserYt) return browserYt;
    throw new Error('Sign in to load your YouTube Music library');
  }

  async function musicClientForBrowse() {
    const yt = await getInnertube();
    if (yt.session.logged_in || await restoreCachedSignIn(yt)) return yt;
    const browserYt = getBrowserInnertube();
    if (browserYt) return browserYt;
    return getGuestInnertube();
  }

  async function musicClientForPlayback(preferBrowserAuth = false) {
    if (preferBrowserAuth) await refreshBrowserAuth();
    if (preferBrowserAuth) {
      const browserYt = getBrowserInnertube();
      if (browserYt) return browserYt;
    }

    const yt = await getInnertube();
    if (yt.session.logged_in || await restoreCachedSignIn(yt)) return yt;

    const browserYt = getBrowserInnertube();
    if (browserYt) return browserYt;

    return getGuestInnertube();
  }

  return {
    authState,
    ensureSignedIn,
    getBrowserInnertube,
    getGuestInnertube,
    getInnertube,
    hasBrowserLoginCookie,
    musicClientForBrowse,
    musicClientForPlayback,
    publicAuthState,
    refreshBrowserAuth,
    restoreCachedSignIn,
    signOutAuth,
    startAccountSwitch,
    startBrowserSignIn,
    startInteractiveSignIn
  };
}
