import { fetch as tauriFetch } from '@tauri-apps/plugin-http';
import { Innertube, Platform } from 'youtubei.js/web';
import { createTauriYouTubeFetch, normalizedTauriYouTubeCookie } from './tauriAuthenticatedFetch.js';
import {
  fetchTauriLibraryCategory,
  normalizeTauriAlbum,
  normalizeTauriArtist,
  normalizeTauriPlaylist,
  normalizeTauriPlaylistPage,
  normalizeTauriSearch,
  normalizeTauriUpNext,
  startTauriHomeFeed,
  startTauriLibraryFeed
} from './tauriMusicCatalog.js';
import { createTauriSidebarCatalog } from './tauriSidebarCatalog.js';

let browseClientPromise;
let playbackClientPromise;
let subscriptionClientPromise;
let homeRequestGeneration = 0;
let playlistContinuationCounter = 0;
const playlistContinuations = new Map();
let browserSession = {
  cookie: '',
  visitorData: '',
  dataSyncId: '',
  accountIndex: 0,
  poToken: ''
};

// Stream URL deciphering needs YouTube.js to evaluate the function it extracts
// from YouTube's player. This matches YouTube.js's documented browser setup.
Platform.shim.eval = async (data) => new Function(data.output)();

const authenticatedFetch = createTauriYouTubeFetch({
  fetchImpl: tauriFetch,
  getSession: () => browserSession
});
const authenticatedSubscriptionFetch = createTauriYouTubeFetch({
  fetchImpl: tauriFetch,
  getSession: () => browserSession,
  origin: 'https://www.youtube.com',
  clientName: 'WEB',
  clientHeaderName: '1',
  clientVersion: null
});

function signedIn() {
  return browserSession.cookie.split(';').some((part) => {
    const name = part.trim().split('=', 1)[0];
    return name === 'SAPISID' || name === '__Secure-3PAPISID';
  });
}

function authState() {
  return {
    signedIn: signedIn(),
    status: signedIn() ? 'signed_in' : 'signed_out',
    pending: null,
    error: '',
    user: null
  };
}

function updateSession(payload = {}) {
  browserSession = {
    cookie: normalizedTauriYouTubeCookie(payload.cookie || ''),
    visitorData: String(payload.visitorData || ''),
    dataSyncId: String(payload.dataSyncId || ''),
    accountIndex: Math.max(0, Number(payload.accountIndex) || 0),
    poToken: String(payload.poToken || '')
  };
  browseClientPromise = null;
  playbackClientPromise = null;
  subscriptionClientPromise = null;
  homeRequestGeneration += 1;
  playlistContinuations.clear();
}

async function authenticatedClient({ playback = false } = {}) {
  if (!signedIn()) throw new Error('Sign in to YouTube Music before using Orchard.');
  const currentPromise = playback ? playbackClientPromise : browseClientPromise;
  if (currentPromise) return currentPromise;

  const pending = Innertube.create({
    client_type: 'WEB_REMIX',
    retrieve_player: playback,
    generate_session_locally: true,
    cookie: browserSession.cookie,
    visitor_data: browserSession.visitorData || undefined,
    account_index: browserSession.accountIndex,
    on_behalf_of_user: browserSession.dataSyncId || undefined,
    po_token: browserSession.poToken || undefined,
    enable_session_cache: false,
    fetch: authenticatedFetch
  }).catch((error) => {
    if (playback) playbackClientPromise = null;
    else browseClientPromise = null;
    throw error;
  });
  if (playback) playbackClientPromise = pending;
  else browseClientPromise = pending;
  return pending;
}

async function authenticatedSubscriptionClient() {
  if (!signedIn()) throw new Error('Sign in to YouTube before loading artist subscriptions.');
  if (subscriptionClientPromise) return subscriptionClientPromise;

  subscriptionClientPromise = Innertube.create({
    client_type: 'WEB',
    retrieve_player: false,
    generate_session_locally: true,
    cookie: browserSession.cookie,
    visitor_data: browserSession.visitorData || undefined,
    account_index: browserSession.accountIndex,
    on_behalf_of_user: browserSession.dataSyncId || undefined,
    po_token: browserSession.poToken || undefined,
    enable_session_cache: false,
    fetch: authenticatedSubscriptionFetch
  }).catch((error) => {
    subscriptionClientPromise = null;
    throw error;
  });
  return subscriptionClientPromise;
}

const sidebarCatalog = createTauriSidebarCatalog({
  getMusicClient: () => authenticatedClient(),
  getSubscriptionClient: authenticatedSubscriptionClient
});

async function searchMusic(payload) {
  const query = String(payload?.query || '').trim();
  if (!query) return { sections: [], filters: [] };
  const client = await authenticatedClient();
  const requestedFilter = String(payload?.filter || 'songs').trim().toLowerCase();
  const filter = {
    songs: 'song',
    videos: 'video',
    albums: 'album',
    artists: 'artist',
    playlists: 'playlist'
  }[requestedFilter] || requestedFilter;
  const result = await client.music.search(query, { type: filter });
  return normalizeTauriSearch(result);
}

function emptyFeed() {
  return { filters: [], sections: [] };
}

function homePayload(homeResult, libraryResult, fallbacks = {}) {
  const warnings = [];
  const home = homeResult.status === 'fulfilled'
    ? homeResult.value
    : fallbacks.home || emptyFeed();
  const library = libraryResult.status === 'fulfilled'
    ? libraryResult.value
    : fallbacks.library || emptyFeed();

  if (homeResult.status === 'rejected') warnings.push(`Music home: ${String(homeResult.reason)}`);
  if (libraryResult.status === 'rejected') warnings.push(`Music library: ${String(libraryResult.reason)}`);
  return { home, library, auth: authState(), warnings };
}

async function homeMusic({ publish } = {}) {
  const requestGeneration = ++homeRequestGeneration;
  const client = await authenticatedClient();
  const [homeStart, libraryStart] = await Promise.allSettled([
    startTauriHomeFeed(client.music),
    startTauriLibraryFeed(client.music)
  ]);
  const initialHome = homeStart.status === 'fulfilled' ? homeStart.value.initial : emptyFeed();
  const initialLibrary = libraryStart.status === 'fulfilled' ? libraryStart.value.initial : emptyFeed();
  const initial = homePayload(
    homeStart.status === 'fulfilled' ? { status: 'fulfilled', value: initialHome } : homeStart,
    libraryStart.status === 'fulfilled' ? { status: 'fulfilled', value: initialLibrary } : libraryStart
  );
  const completion = Promise.allSettled([
    homeStart.status === 'fulfilled' ? homeStart.value.complete : Promise.reject(homeStart.reason),
    libraryStart.status === 'fulfilled' ? libraryStart.value.complete : Promise.reject(libraryStart.reason)
  ]);

  if (typeof publish !== 'function') {
    const [homeResult, libraryResult] = await completion;
    return homePayload(homeResult, libraryResult, {
      home: initialHome,
      library: initialLibrary
    });
  }

  void completion.then(([homeResult, libraryResult]) => {
    if (requestGeneration !== homeRequestGeneration) return;
    publish('music:home:update', homePayload(homeResult, libraryResult, {
      home: initialHome,
      library: initialLibrary
    }));
  });
  return initial;
}

function rememberPlaylistContinuation(playlist, browseId) {
  if (!playlist?.has_continuation) return '';
  const token = `playlist:${++playlistContinuationCounter}`;
  playlistContinuations.set(token, { kind: 'parsed', playlist, browseId });
  return token;
}

function rememberRawPlaylistContinuation(continuation, browseId) {
  if (!continuation) return '';
  const token = `playlist:${++playlistContinuationCounter}`;
  playlistContinuations.set(token, { kind: 'raw', continuation, browseId });
  return token;
}

async function resolveRawPlaylist(payload) {
  const detail = await sidebarCatalog.playlist(payload);
  const continuation = rememberRawPlaylistContinuation(detail.continuation, detail.browseId);
  return { ...detail, continuation, hasMoreTracks: Boolean(continuation), editable: false };
}

async function resolvePlaylist(payload = {}) {
  const browseId = String(payload.browseId || '').trim();
  if (!browseId) throw new Error('A playlist browse ID is required.');
  if (browseId.startsWith('RD')) return resolveRawPlaylist(payload);

  const client = await authenticatedClient();
  try {
    const playlist = await client.music.getPlaylist(browseId);
    return normalizeTauriPlaylist(playlist, browseId, rememberPlaylistContinuation(playlist, browseId));
  } catch {
    return resolveRawPlaylist(payload);
  }
}

async function continuePlaylist(payload = {}) {
  const token = String(payload.continuation || '');
  const stored = playlistContinuations.get(token);
  if (!stored) throw new Error('The playlist continuation has expired. Reload the playlist.');
  playlistContinuations.delete(token);

  if (stored.kind === 'raw') {
    const page = await sidebarCatalog.continuePlaylist(stored.continuation, Number(payload.startIndex) || 0);
    const continuation = rememberRawPlaylistContinuation(page.continuation, stored.browseId);
    return { ...page, continuation, hasMoreTracks: Boolean(continuation) };
  }

  const nextPage = await stored.playlist.getContinuation();
  const continuation = rememberPlaylistContinuation(nextPage, stored.browseId);
  return {
    tracks: normalizeTauriPlaylistPage(nextPage, Number(payload.startIndex) || 0),
    continuation,
    hasMoreTracks: Boolean(continuation)
  };
}

async function resolveAlbum(payload = {}) {
  const browseId = String(payload.browseId || '').trim();
  if (!browseId) throw new Error('An album browse ID is required.');
  const client = await authenticatedClient();
  return normalizeTauriAlbum(await client.music.getAlbum(browseId), browseId);
}

async function resolveArtist(payload = {}) {
  const browseId = String(payload.browseId || '').trim();
  if (!browseId) throw new Error('An artist browse ID is required.');
  const client = await authenticatedClient();
  const artist = await client.music.getArtist(browseId);
  return normalizeTauriArtist(artist, browseId);
}

async function upNext(payload = {}) {
  const videoId = String(payload.videoId || '').trim();
  if (!videoId) return [];
  const client = await authenticatedClient();
  return normalizeTauriUpNext(await client.music.getUpNext(videoId));
}

async function resolveTrack(payload = {}) {
  const videoId = String(payload.videoId || '').trim();
  if (!videoId) throw new Error('A video ID is required for playback.');
  const client = await authenticatedClient({ playback: true });
  const info = await client.getBasicInfo(videoId);
  const streaming = info?.streaming_data || info?.streamingData || {};
  const formats = [
    ...(streaming.adaptive_formats || streaming.adaptiveFormats || []),
    ...(streaming.formats || [])
  ].filter((format) => {
    const mime = String(format?.mime_type || format?.mimeType || '');
    return mime.startsWith('audio/') || (format?.has_audio && !format?.has_video);
  }).sort((left, right) => Number(right?.bitrate || 0) - Number(left?.bitrate || 0));
  const format = formats[0];
  if (!format) throw new Error('YouTube returned no native-audio format for this track.');
  const streamUrl = await format.decipher(client.session.player);
  const basic = info?.basic_info || info?.basicInfo || {};
  return {
    id: videoId,
    title: String(payload.title || basic.title || ''),
    artist: String(payload.artist || basic.author || ''),
    streamUrl,
    audioStreamUrl: '',
    mediaKind: 'audio',
    mimeType: String(format.mime_type || format.mimeType || '').split(';', 1)[0],
    itag: format.itag,
    bitrate: Number(format.bitrate || 0),
    durationSeconds: Number(payload.durationSeconds || basic.duration || 0),
    thumbnail: String(payload.thumbnail || basic.thumbnail?.[0]?.url || ''),
    streamExpiresAt: Number(new URL(streamUrl).searchParams.get('expire') || 0) * 1000,
    playbackSource: 'youtube',
    authenticatedPlayback: true,
    isAudioOnly: true
  };
}

export async function dispatch(request = {}, context = {}) {
  switch (request.event) {
    case 'runtime:info':
    case 'sidecar:ping':
      return {
        runtime: 'webview',
        fetch: 'tauri-http',
        platform: 'linux',
        version: __APP_VERSION__
      };
    case 'auth:status':
      return authState();
    case 'auth:session':
      updateSession(request.payload);
      return authState();
    case 'music:home':
      return homeMusic(context);
    case 'music:library-category': {
      const client = await authenticatedClient();
      return fetchTauriLibraryCategory(client.music, request.payload?.title);
    }
    case 'music:subscribed-artists':
      return sidebarCatalog.subscribedArtists();
    case 'music:set-artist-subscription':
      return sidebarCatalog.setArtistSubscription(request.payload);
    case 'music:search':
      return searchMusic(request.payload);
    case 'music:radio':
      return sidebarCatalog.radio();
    case 'music:podcasts':
      return sidebarCatalog.podcasts();
    case 'music:podcast':
      return sidebarCatalog.podcast(request.payload);
    case 'music:release-radar':
      return sidebarCatalog.releaseRadar();
    case 'music:playlist':
      return resolvePlaylist(request.payload);
    case 'music:playlist:more':
      return continuePlaylist(request.payload);
    case 'music:album':
      return resolveAlbum(request.payload);
    case 'music:artist':
      return resolveArtist(request.payload);
    case 'music:artist:section':
      return sidebarCatalog.artistSection(request.payload);
    case 'music:future-album':
      return sidebarCatalog.resolveFutureAlbum(request.payload);
    case 'music:itunes-album':
      return sidebarCatalog.resolveItunesAlbum(request.payload);
    case 'music:itunes-artist-genre':
      return sidebarCatalog.resolveArtistGenre(request.payload);
    case 'music:up-next':
      return upNext(request.payload);
    case 'music:track':
      return resolveTrack(request.payload);
    default:
      throw new Error(`The Linux browser service does not implement ${request.event}.`);
  }
}
