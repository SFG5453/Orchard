import { createServer } from 'node:http';
import { Server } from 'socket.io';
import { createPlaylistMutations } from './playlistMutations.js';
import { normalizePodcastDetail, normalizePodcastFeed } from '../catalog/podcastCatalog.js';
import { createOrchardConnectServer } from '../connect/orchardConnectServer.js';
import { registerCacheBridge } from './cacheBridge.js';
import { registerYouTubeHistoryBridge } from './youtubeHistoryBridge.js';
import { registerYouTubeLikesBridge } from './youtubeLikesBridge.js';
import { registerArtistGenreBridge } from './artistGenreBridge.js';
import { playbackAudioBitrate } from '../playback/playbackFormats.js';
import { isAgeGatePlaybackError } from '../playback/playbackErrors.js';
import { isAgeGateRiskTrack } from '../playback/musicVideoFallback.js';
/** Starts the renderer-only loopback transport and returns its owned close handle. */
export async function startBridgeServer({
  bridgeError,
  catalogAudioItems,
  continueMusicPlaylistWithFallback,
  ensureSignedIn,
  fetchBrowserMusicHome,
  fetchFeed,
  fetchMusicLibraryCategory,
  fetchMusicLibraryFeed,
  findMusicVideoFallback,
  getBrowserInnertube,
  getGuestInnertube,
  getInnertube,
  hasBrowserLoginCookie,
  cachedArtistResult, hydrateArtist,
  musicClientForBrowse,
  musicClientForPlayback,
  normalizeAlbum,
  normalizeArtistSection,
  normalizePlaylist,
  normalizePlaylistPage,
  normalizeSearch,
  normalizeTrackInfo,
  personalizedRadio,
  playback,
  preferredAudioTrack,
  proxyStream,
  publicAuthState,
  releaseAlbumMatches,
  releaseRadarForArtists,
  refreshBrowserAuth,
  resolveFutureAlbum,
  resolveArtistGenre,
  resolveItunesAlbum,
  resolveLyrics,
  resolveMusicCollectionWithFallback,
  resolveStream,
  restoreCachedSignIn,
  searchCatalog,
  shelfItems,
  signOutAuth,
  startAccountSwitch,
  startBrowserSignIn,
  subscribedArtists,
  youtubeHistory,
  youtubeLikes,
  connectDevicesPath
}) {
  const playlistMutations = createPlaylistMutations({ ensureSignedIn, refreshBrowserAuth });
  const streamCorsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Range, Content-Type',
    'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS'
  };
  const httpServer = createServer(async (req, res) => {
    const requestUrl = new URL(req.url, 'http://127.0.0.1');
    if (requestUrl.pathname.startsWith('/stream/')) {
      if (req.method === 'OPTIONS') {
        res.writeHead(204, streamCorsHeaders);
        res.end();
        return;
      }
      const videoId = decodeURIComponent(requestUrl.pathname.replace('/stream/', ''));
      try {
        await proxyStream(videoId, req, res);
      } catch (error) {
        console.warn(`Stream proxy failed for ${videoId}: ${error.message}`);
        if (res.headersSent) {
          if (!res.writableEnded) res.destroy(error);
          return;
        }
        res.writeHead(502, {
          ...streamCorsHeaders,
          'Content-Type': 'application/json'
        });
        res.end(JSON.stringify({ error: error.message }));
      }
      return;
    }

    res.writeHead(404, streamCorsHeaders);
    res.end();
  });

  const ioServer = new Server(httpServer, { cors: { origin: '*' } });
  const connectServer = await createOrchardConnectServer({
    Server,
    desktopIo: ioServer,
    deviceStorePath: connectDevicesPath
  });

  function isAuthenticationCredentialError(error) {
    return Number(error?.status) === 401 || /\b401\b|required authentication credential/i.test(error?.message || '');
  }

  async function musicUpNext(videoId) {
    const primary = await musicClientForBrowse();

    try {
      return await primary.music.getUpNext(videoId, true);
    } catch (error) {
      if (!isAuthenticationCredentialError(error)) throw error;

      await refreshBrowserAuth();
      const browser = await getBrowserInnertube();
      if (browser && browser !== primary) {
        try {
          return await browser.music.getUpNext(videoId, true);
        } catch (browserError) {
          if (!isAuthenticationCredentialError(browserError)) throw browserError;
        }
      }

      const guest = await getGuestInnertube();
      if (guest !== primary) return guest.music.getUpNext(videoId, true);
      throw error;
    }
  }

  async function resolveTrackRequest({ videoId, supportedMimes = [], supportedVideoMimes = [], mediaKind = 'audio', preload = false, refreshStream = false, avoidItags = [], avoidMimeTypes = [], ...trackHint }) {
    const preferBrowserPlayback = Boolean(trackHint.isUpload || playback.androidVrCooldownActive());
    const [yt, searchYt] = await Promise.all([
      musicClientForPlayback(preferBrowserPlayback),
      getGuestInnertube()
    ]);
    const wantsVideo = mediaKind === 'video';
    const resolvedVideoId = wantsVideo
      ? videoId
      : await preferredAudioTrack(searchYt, { ...trackHint, videoId });
    let musicVideoFallbackPromise;

    function findMusicVideoFallbackOnce() {
      musicVideoFallbackPromise ||= findMusicVideoFallback(searchYt, { ...trackHint, videoId });
      return musicVideoFallbackPromise;
    }

    async function resolveCandidate(candidateId, { playAsVideo = false } = {}) {
      const resolvedInfo = await playback.playbackInfo(candidateId, {
        yt,
        preferBrowserAuth: preferBrowserPlayback
      });
      const normalizedInfo = normalizeTrackInfo(candidateId, resolvedInfo.info);
      const fallbackTargetDuration = playAsVideo
        ? Number(trackHint.fallbackTargetDurationSeconds || trackHint.durationSeconds || 0)
        : 0;
      if (fallbackTargetDuration && Math.abs(normalizedInfo.durationSeconds - fallbackTargetDuration) > 5) {
        throw new Error('The matching music video differs from the song by more than five seconds');
      }
      const streamAsVideo = wantsVideo || playAsVideo;
      const stream = await resolveStream(candidateId, {
        supportedMimes: streamAsVideo ? supportedVideoMimes : supportedMimes,
        supportedAudioMimes: supportedMimes,
        mediaKind: streamAsVideo ? 'video' : 'audio',
        preferInlineVideo: playAsVideo,
        requiresAuth: Boolean(trackHint.isUpload),
        lowPriority: Boolean(preload),
        refreshStream: Boolean(refreshStream),
        avoidItags,
        avoidMimeTypes,
        playbackClient: resolvedInfo.yt,
        playbackInfo: resolvedInfo.info,
        ...trackHint
      });
      const streamBaseUrl = `http://127.0.0.1:${httpServer.address().port}/stream/${encodeURIComponent(candidateId)}`;
      return {
        ...normalizedInfo,
        youtubeVideoId: candidateId,
        mediaKind: streamAsVideo ? 'video' : 'audio',
        mimeType: stream.format.mimeType,
        itag: stream.format.itag,
        bitrate: playbackAudioBitrate(stream, streamAsVideo ? 'video' : 'audio'),
        audioItag: stream.audioFormat?.itag || null,
        audioStreamUrl: streamAsVideo && stream.audioFormat
          ? `${streamBaseUrl}?itag=${stream.audioFormat.itag}&media=audio`
          : '',
        playbackSource: stream.playbackSource || 'youtube',
        externalSource: stream.externalSource || '',
        streamUrl: `${streamBaseUrl}?itag=${stream.format.itag}&media=${streamAsVideo ? 'video' : 'audio'}`, streamExpiresAt: stream.expiresAt || 0
      };
    }

    async function resolveMusicVideoFallback(fallback, reason) {
      console.warn(`Using ${reason} music video ${fallback.id} for age-gated track ${videoId}`);
      return {
        ...await resolveCandidate(fallback.id, { playAsVideo: true }),
        id: trackHint.originalVideoId || videoId,
        musicVideoAudioFallback: true,
        musicVideoFallbackId: fallback.id
      };
    }

    if (!wantsVideo && isAgeGateRiskTrack(trackHint)) {
      const fallback = await findMusicVideoFallbackOnce();
      if (fallback) {
        try {
          return await resolveMusicVideoFallback(fallback, 'proactive');
        } catch (fallbackError) {
          console.warn(`Proactive music-video fallback ${fallback.id} failed: ${fallbackError.message}`);
        }
      }
    }

    try {
      const resolved = await resolveCandidate(resolvedVideoId, {
        playAsVideo: Boolean(trackHint.musicVideoAudioFallback)
      });
      if (!trackHint.musicVideoAudioFallback) return resolved;

      return {
        ...resolved,
        id: trackHint.originalVideoId || videoId,
        musicVideoAudioFallback: true,
        musicVideoFallbackId: videoId
      };
    } catch (error) {
      if (wantsVideo || !isAgeGatePlaybackError(error)) throw error;
      const fallback = await findMusicVideoFallbackOnce();
      if (fallback) {
        try {
          return await resolveMusicVideoFallback(fallback, 'duration-matched');
        } catch (fallbackError) {
          console.warn(`Duration-matched music-video fallback ${fallback.id} failed: ${fallbackError.message}`);
        }
      }

      const alternateAudioId = await preferredAudioTrack(searchYt, {
        ...trackHint, videoId, preferAudioOnly: true, retryAlternateAudio: true, excludedVideoIds: [resolvedVideoId]
      });
      if (alternateAudioId !== videoId && alternateAudioId !== resolvedVideoId) {
        try {
          return await resolveCandidate(alternateAudioId);
        } catch (alternateError) {
          if (!isAgeGatePlaybackError(alternateError)) throw alternateError;
          error = alternateError;
        }
      }
      if (resolvedVideoId !== videoId) return resolveCandidate(videoId);
      throw error;
    }
  }

  async function resolveReleasedAlbum(yt, release) {
    if (!yt || release.releaseDaysFromToday > 0) return release;

    try {
      const search = await yt.music.search(`${release.artist} ${release.title}`, { type: 'album' });
      const albums = normalizeSearch(search, release.title).sections
        .flatMap((section) => section.items || [])
        .filter((item) => item.type === 'album');
      const match = albums.find((album) => releaseAlbumMatches(release, album));
      if (!match) return release;

      return {
        ...release,
        ...match,
        title: match.title || release.title,
        artist: match.artist || match.subtitle || release.artist,
        artists: match.artists?.length ? match.artists : release.artists,
        thumbnail: match.thumbnail || release.thumbnail,
        releaseDate: release.releaseDate,
        releaseDateText: release.releaseDateText,
        releaseDaysFromToday: release.releaseDaysFromToday,
        releaseTiming: release.releaseTiming,
        releaseTimingLabel: release.releaseTimingLabel,
        releaseResolved: true,
        sourceRelease: release
      };
    } catch (error) {
      console.warn(`Could not resolve release ${release.artist} - ${release.title}: ${error.message}`);
      return release;
    }
  }
  ioServer.on('connection', (socket) => {
    registerYouTubeHistoryBridge({ socket, youtubeHistory });
    registerYouTubeLikesBridge({ socket, youtubeLikes, bridgeError });
    socket.emit('bridge:ready', { port: httpServer.address().port });
    connectServer.registerDesktop(socket);
    registerCacheBridge({ socket, bridgeError, playback, resolveTrackRequest, normalizeTrackInfo });
    registerArtistGenreBridge({ socket, bridgeError, resolveArtistGenre });
    playlistMutations.register(socket, bridgeError);

    socket.on('music:search', async ({ query, filter = 'songs' }, reply) => {
      try {
        const yt = await getGuestInnertube();
        const loadTrackPopularity = async (videoId) => {
          const info = await yt.getBasicInfo(videoId);
          return info?.basic_info?.view_count || 0;
        };
        reply({ ok: true, data: await searchCatalog(yt.music, query, filter, loadTrackPopularity) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:radio', async (_payload, reply) => {
      try {
        reply({ ok: true, data: await personalizedRadio() });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:podcasts', async (_payload, reply) => {
      try {
        const yt = await musicClientForBrowse();
        const podcasts = await resolveMusicCollectionWithFallback(yt, 'podcast', { browseId: 'FEmusic_podcasts' });
        reply({ ok: true, data: normalizePodcastFeed(podcasts.data) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:podcast', async (payload, reply) => {
      try {
        const yt = await musicClientForBrowse();
        const podcast = await resolveMusicCollectionWithFallback(yt, 'podcast', payload);
        reply({ ok: true, data: normalizePodcastDetail(podcast.data, podcast.browseId) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:release-radar', async (_payload, reply) => {
      try {
        await refreshBrowserAuth();
        const artists = await subscribedArtists();
        const releases = await releaseRadarForArtists(artists);
        const yt = await musicClientForBrowse().catch(() => null);
        const resolved = [];

        for (const release of releases) {
          resolved.push(await resolveReleasedAlbum(yt, release));
        }

        reply({ ok: true, data: { artists, releases: resolved } });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:library-category', async ({ title }, reply) => {
      try {
        const yt = await ensureSignedIn();
        reply({ ok: true, data: await fetchMusicLibraryCategory(yt, title) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:subscribed-artists', async (_payload, reply) => {
      try {
        await refreshBrowserAuth();
        reply({ ok: true, data: await subscribedArtists() });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('auth:status', async (_payload, reply) => {
      try {
        await refreshBrowserAuth();
        const yt = await getInnertube();
        await restoreCachedSignIn(yt);
        reply({ ok: true, data: publicAuthState() });
      } catch {
        reply({ ok: true, data: publicAuthState() });
      }
    });

    socket.on('auth:login', async (_payload, reply) => {
      try {
        reply({ ok: true, data: await startLogin({ startBrowserSignIn, publicAuthState }) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('auth:logout', async (_payload, reply) => {
      try {
        reply({ ok: true, data: await signOutAuth() });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('auth:switch-account', async (_payload, reply) => {
      try {
        reply({ ok: true, data: await startAccountSwitch() });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:home', async (_payload, reply) => {
      try {
        if (!hasBrowserLoginCookie()) await refreshBrowserAuth();
        const yt = await ensureSignedIn();
        const [home, library] = await Promise.all([
          fetchFeed('Music home', () => yt.music.getHomeFeed(), hasBrowserLoginCookie() ? fetchBrowserMusicHome : null),
          fetchMusicLibraryFeed(yt)
        ]);
        const errors = [home.error, library.error].filter(Boolean);

        reply({
          ok: true,
          data: {
            home: home.feed,
            library: library.feed,
            auth: publicAuthState(),
            warnings: errors
          }
        });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:playlist', async (payload, reply) => {
      try {
        const yt = await musicClientForBrowse();
        const [playlist, youtubePlaylist] = await Promise.all([
          resolveMusicCollectionWithFallback(yt, 'playlist', payload),
          yt.getPlaylist(payload?.browseId).catch(() => null)
        ]);
        const normalized = normalizePlaylist(playlist, youtubePlaylist?.info?.total_items);
        reply({ ok: true, data: { ...normalized, editable: false } });
        void playlistMutations.canEdit(normalized.browseId, normalized.tracks?.[0]?.id)
          .then((editable) => socket.emit('music:playlist:editable', { browseId: normalized.browseId, editable }))
          .catch(() => {});
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:playlist:more', async (payload, reply) => {
      try {
        const continuation = payload?.continuation;
        if (!continuation) throw new Error('Playlist continuation is missing.');

        const startIndex = Number(payload?.startIndex) || 0;
        const yt = await musicClientForBrowse();
        const page = await continueMusicPlaylistWithFallback(yt, continuation);

        reply({ ok: true, data: normalizePlaylistPage(page, startIndex) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:album', async (payload, reply) => {
      try {
        const yt = await musicClientForBrowse();
        const album = await resolveMusicCollectionWithFallback(yt, 'album', payload);
        reply({ ok: true, data: normalizeAlbum(album.data, album.browseId) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:future-album', async (payload, reply) => {
      try {
        reply({ ok: true, data: await resolveFutureAlbum(payload) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:itunes-album', async (payload, reply) => {
      try {
        reply({ ok: true, data: await resolveItunesAlbum(payload?.albumId) });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:artist', async (payload, reply) => {
      try {
        const payloadCached = cachedArtistResult(payload, { hydratedOnly: true });
        if (payloadCached) return reply({ ok: true, data: payloadCached });
        const yt = await musicClientForBrowse();
        const artist = await resolveMusicCollectionWithFallback(yt, 'artist', payload);
        const cached = cachedArtistResult(artist.browseId, { hydratedOnly: true });
        reply({ ok: true, data: cached || await hydrateArtist(artist) });
      } catch (error) { reply({ ok: false, error: bridgeError(error) }); }
    });
    socket.on('music:artist:section', async (payload, reply) => {
      try {
        reply({ ok: true, data: await normalizeArtistSection(await resolveMusicCollectionWithFallback(await musicClientForBrowse(), 'artist', payload), payload?.section) });
      } catch (error) { reply({ ok: false, error: bridgeError(error) }); }
    });
    socket.on('music:suggestions', async ({ query }, reply) => {
      try {
        const yt = await getGuestInnertube();
        const suggestions = await yt.music.getSearchSuggestions(query);
        reply({
          ok: true,
          data: suggestions.flatMap((section) => shelfItems(section)).slice(0, 8)
        });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });

    socket.on('music:track', async ({ videoId, supportedMimes = [], supportedVideoMimes = [], mediaKind = 'audio', preload = false, refreshStream = false, avoidItags = [], avoidMimeTypes = [], ...trackHint }, reply) => {
      try {
        reply({
          ok: true,
          data: await resolveTrackRequest({ videoId, supportedMimes, supportedVideoMimes, mediaKind, preload, refreshStream, avoidItags, avoidMimeTypes, ...trackHint })
        });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });
    socket.on('music:lyrics', async ({ provider = 'amlyrics', ...track }, reply) => {
      try {
        reply({ ok: true, data: await resolveLyrics(track, provider) });
      } catch {
        reply({ ok: true, data: { status: 'unavailable', mode: '', lines: [], source: provider } });
      }
    });

    socket.on('music:up-next', async ({ videoId }, reply) => {
      try {
        const upNext = await musicUpNext(videoId);
        reply({
          ok: true,
          data: catalogAudioItems(shelfItems(upNext))
        });
      } catch (error) {
        reply({ ok: false, error: bridgeError(error) });
      }
    });
  });

  await new Promise((resolve) => httpServer.listen(0, '127.0.0.1', resolve));
  // The main-process lifecycle owns this handle and calls `close()` before quitting.
  return {
    port: httpServer.address().port,
    emit(event, payload) {
      ioServer.emit(event, payload);
    },
    close() {
      connectServer.close();
      ioServer.close();
      httpServer.close();
    }
  };
}

async function startLogin({ startBrowserSignIn }) { return startBrowserSignIn(); }
