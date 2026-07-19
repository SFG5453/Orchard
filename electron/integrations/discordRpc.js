// Owns the optional Discord RPC client and clears its connection during application shutdown.
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { Client: DiscordRpcClient } = require('@xhayper/discord-rpc');

const discordApplicationId = '1406052942712406095';
const discordSongLinksOrigin = 'https://songlinks.sfg545.dev';
const discordArtworkProxyOrigin = 'https://artwork-proxy.sfg545.dev';
const discordOrchardProjectUrl = 'https://sfg545.dev/orchard';
const discordSongLinkCache = new Map();
const discordArtworkWarmCache = new Map();
const discordArtworkVersion = '7';

let discordRpcClient;
let discordRpcLoginPromise;
let discordRpcReady = false;
let discordRpcLastActivity = null;
let discordRpcLastErrorAt = 0;
let discordPresenceRequestId = 0;

function logDiscordRpcError(error) {
  const now = Date.now();
  if (now - discordRpcLastErrorAt < 30000) return;
  discordRpcLastErrorAt = now;
  console.warn(`Discord RPC unavailable: ${error.message}`);
}

function trimDiscordText(value = '', fallback = '') {
  const text = String(value || fallback || '').replace(/\s+/g, ' ').trim();
  return text.slice(0, 128);
}

function normalizeDiscordUrl(value = '') {
  const text = String(value || '').trim();
  if (!/^https?:\/\//i.test(text)) return '';
  return text;
}

function normalizeDiscordImageUrl(value = '') {
  const text = normalizeDiscordUrl(value);
  if (!text) return '';

  try {
    const url = new URL(text);
    const path = url.pathname.toLowerCase();
    if (/\.(mp4|webm|mov|m4v)(?:$|\.)/i.test(path)) return '';
    return url.toString();
  } catch {
    return '';
  }
}

function discordAnimatedArtworkUrl(value = '') {
  const source = normalizeDiscordUrl(value);
  if (!source) return '';

  try {
    const sourceUrl = new URL(source);
    if (sourceUrl.protocol !== 'https:' || sourceUrl.hostname !== 'mvod.itunes.apple.com') return '';
    if (!sourceUrl.pathname.toLowerCase().endsWith('.mp4')) return '';

    const proxyUrl = new URL('/convert.gif', discordArtworkProxyOrigin);
    proxyUrl.searchParams.set('v', discordArtworkVersion);
    proxyUrl.searchParams.set('url', sourceUrl.toString());
    return proxyUrl.toString();
  } catch {
    return '';
  }
}

function absoluteSongLinksUrl(value = '') {
  try {
    return new URL(value, discordSongLinksOrigin).toString();
  } catch {
    return '';
  }
}

function numberOrZero(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

function discordServiceName(value = '') {
  return String(value || '').trim() === 'youtubeMusic' ? 'YouTube Music' : 'Orchard';
}

function cacheDiscordArtwork(url, result) {
  discordArtworkWarmCache.set(url, result);

  if (discordArtworkWarmCache.size <= 80) return;
  const firstKey = discordArtworkWarmCache.keys().next().value;
  if (firstKey) discordArtworkWarmCache.delete(firstKey);
}

async function warmDiscordArtwork(url) {
  if (!url) return false;
  if (discordArtworkWarmCache.has(url)) {
    const cached = discordArtworkWarmCache.get(url);
    if (!cached?.retryAfter || cached.retryAfter > Date.now()) return cached?.retryAfter ? false : cached;
    discordArtworkWarmCache.delete(url);
  }

  const request = (async () => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 90_000);

    try {
      const response = await fetch(url, {
        headers: { accept: 'image/gif', 'user-agent': 'Orchard Discord RPC' },
        signal: controller.signal
      });
      if (!response.ok) throw new Error(`Artwork proxy returned ${response.status}`);
      await response.arrayBuffer();
      cacheDiscordArtwork(url, true);
      return true;
    } catch (error) {
      cacheDiscordArtwork(url, { retryAfter: Date.now() + 30_000 });
      console.warn(`Could not prepare Discord artwork: ${error.message}`);
      return false;
    } finally {
      clearTimeout(timeout);
    }
  })();

  cacheDiscordArtwork(url, request);
  return request;
}

function discordSongLinkInput(presence = {}) {
  const title = trimDiscordText(presence.title);
  const artist = trimDiscordText(presence.artist);
  if (!title || !artist) return null;

  return {
    title,
    artist,
    album: trimDiscordText(presence.album),
    isrc: trimDiscordText(presence.isrc).toUpperCase(),
    youtubeVideoId: String(presence.youtubeVideoId || '').trim(),
    durationSeconds: Math.round(numberOrZero(presence.durationSeconds || presence.duration)),
    thumbnailUrl: normalizeDiscordImageUrl(presence.thumbnailUrl || presence.artworkUrl)
  };
}

function discordSongLinkCacheKey(input) {
  return [
    input.title,
    input.artist,
    input.album,
    input.isrc,
    input.youtubeVideoId,
    input.durationSeconds,
    input.thumbnailUrl
  ].join('\n');
}

function cacheDiscordSongLink(key, url) {
  discordSongLinkCache.set(key, url);

  if (discordSongLinkCache.size <= 80) return;
  const firstKey = discordSongLinkCache.keys().next().value;
  if (firstKey) discordSongLinkCache.delete(firstKey);
}

export async function resolveDiscordSongLink(presence = {}) {
  const result = await resolveDiscordSongLinkDetails(presence);
  return result?.shareUrl || '';
}

export async function resolveDiscordSongLinkDetails(presence = {}) {
  const input = discordSongLinkInput(presence);
  if (!input) return null;

  const cacheKey = discordSongLinkCacheKey(input);
  if (discordSongLinkCache.has(cacheKey)) return discordSongLinkCache.get(cacheKey);

  const request = (async () => {
    const url = new URL('/resolve', discordSongLinksOrigin);
    url.searchParams.set('title', input.title);
    url.searchParams.set('artist', input.artist);
    if (input.album) url.searchParams.set('album', input.album);
    if (input.isrc) url.searchParams.set('isrc', input.isrc);
    if (input.youtubeVideoId) url.searchParams.set('youtubeVideoId', input.youtubeVideoId);
    if (input.durationSeconds) url.searchParams.set('durationSeconds', String(input.durationSeconds));
    if (input.thumbnailUrl) url.searchParams.set('thumbnailUrl', input.thumbnailUrl);

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);

    try {
      const response = await fetch(url, {
        headers: {
          accept: 'application/json',
          'user-agent': 'Orchard Discord RPC'
        },
        signal: controller.signal
      });

      if (!response.ok) throw new Error(`Song link worker returned ${response.status}`);
      const data = await response.json();
      const shareUrl = absoluteSongLinksUrl(data?.shareUrl);
      const result = data?.ok ? { ...data, shareUrl } : null;
      cacheDiscordSongLink(cacheKey, result);
      return result;
    } catch (error) {
      cacheDiscordSongLink(cacheKey, null);
      console.warn(`Could not resolve Discord song link: ${error.message}`);
      return null;
    } finally {
      clearTimeout(timeout);
    }
  })();

  cacheDiscordSongLink(cacheKey, request);
  return request;
}

function buildDiscordActivity(presence = {}, options = {}) {
  if (!presence?.title) return null;

  const title = trimDiscordText(presence.title, 'Playing music');
  const artist = trimDiscordText(presence.artist, 'Orchard');
  const album = trimDiscordText(presence.album);
  const currentTime = numberOrZero(presence.currentTime);
  const duration = numberOrZero(presence.duration);
  const isPlaying = Boolean(presence.isPlaying);
  const service = discordServiceName(presence.activityName);
  const details = isPlaying ? `${title} on ${service}` : `Paused - ${title} on ${service}`;
  const activity = {
    name: artist || service,
    type: 2,
    details,
    state: artist,
    largeImageKey: normalizeDiscordImageUrl(options.artworkUrl || presence.artworkUrl),
    largeImageText: album,
    smallImageText: isPlaying ? 'Playing' : 'Paused'
  };

  if (isPlaying) {
    activity.startTimestamp = new Date(Date.now() - Math.round(currentTime * 1000));

    if (duration > currentTime) {
      activity.endTimestamp = new Date(Date.now() + Math.round((duration - currentTime) * 1000));
    }
  }

  const buttons = [
    { label: 'Listen on Your Platform', url: options.songLinkUrl },
    { label: 'View the Orchard Project', url: discordOrchardProjectUrl }
  ].filter((button) => normalizeDiscordUrl(button.url));

  if (buttons.length) activity.buttons = buttons.slice(0, 2);

  return activity;
}

export function resetDiscordRpcClient() {
  discordRpcReady = false;
  discordRpcLoginPromise = null;

  if (discordRpcClient) {
    discordRpcClient.destroy().catch(() => {});
    discordRpcClient = null;
  }
}

function getDiscordRpcClient() {
  if (discordRpcClient) return discordRpcClient;

  discordRpcClient = new DiscordRpcClient({
    clientId: discordApplicationId
  });

  discordRpcClient.on('ready', () => {
    discordRpcReady = true;
    if (discordRpcLastActivity) {
      discordRpcClient.user?.setActivity(discordRpcLastActivity).catch(logDiscordRpcError);
    }
  });

  discordRpcClient.on('disconnected', () => {
    discordRpcReady = false;
  });

  return discordRpcClient;
}

async function connectDiscordRpc() {
  const client = getDiscordRpcClient();
  if (discordRpcReady && client.user) return client;

  if (!discordRpcLoginPromise) {
    discordRpcLoginPromise = client.login()
      .then(() => client)
      .catch((error) => {
        resetDiscordRpcClient();
        throw error;
      })
      .finally(() => {
        discordRpcLoginPromise = null;
      });
  }

  return discordRpcLoginPromise;
}

async function enhanceDiscordPresence(presence, requestId, animatedArtworkUrl) {
  const [animatedArtworkReady, songLinkUrl] = await Promise.all([
    warmDiscordArtwork(animatedArtworkUrl),
    resolveDiscordSongLink(presence)
  ]);
  if (requestId !== discordPresenceRequestId) return;

  const activity = buildDiscordActivity(presence, {
    artworkUrl: animatedArtworkReady ? animatedArtworkUrl : presence.artworkUrl,
    songLinkUrl
  });

  try {
    const client = await connectDiscordRpc();
    if (requestId !== discordPresenceRequestId) return;
    discordRpcLastActivity = activity;
    await client.user?.setActivity(activity);
  } catch (error) {
    logDiscordRpcError(error);
  }
}

export async function setDiscordPresence(presence) {
  const requestId = ++discordPresenceRequestId;
  const activity = buildDiscordActivity(presence);
  const animatedArtworkUrl = discordAnimatedArtworkUrl(presence?.animatedArtworkUrl);

  discordRpcLastActivity = activity;

  if (!activity) {
    await clearDiscordPresence();
    return;
  }

  try {
    const client = await connectDiscordRpc();
    if (requestId !== discordPresenceRequestId) return;
    await client.user?.setActivity(activity);
    void enhanceDiscordPresence(presence, requestId, animatedArtworkUrl);
  } catch (error) {
    logDiscordRpcError(error);
  }
}

export async function clearDiscordPresence() {
  discordPresenceRequestId += 1;
  discordRpcLastActivity = null;

  if (!discordRpcClient) return;

  try {
    if (discordRpcClient.user) await discordRpcClient.user.clearActivity();
  } catch (error) {
    logDiscordRpcError(error);
  }
}
