// Resolves authenticated streams and owns their short-lived caches and proxy lifecycle.
import {
  chooseAudioFormatFromFormats,
  chooseVideoFormatFromFormats,
  chooseVideoOnlyFormatFromFormats,
  compareVideoFormats,
  formatHasInlineAudio,
  playableAudioFormats,
  playableVideoFormats,
  playableVideoOnlyFormats,
  rawPlayableAudioFormats,
  rawPlayableVideoFormats,
  rawPlayableVideoOnlyFormats
} from './playbackFormats.js';
import {
  parseRangeHeader,
  pipeWebBody,
  proxyHeadResponseHeaders,
  proxyResponseHeaders,
  rangeNotSatisfiable,
  upstreamRangeHeader,
  upstreamStreamRequest,
  validateUpstreamStreamUrl
} from './streamProxy.js';
import {
  canFallbackToGuest,
  isAgeGatePlaybackError,
  isBotCheckPlaybackError,
  isPrivatePlaybackError
} from './playbackErrors.js';
import { createSongCache } from './songCache.js';
import { createPlaybackStreamCache } from './playbackStreamCache.js';
const youtubeWebUserAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)';
const androidVrClient = {
  clientName: 'ANDROID_VR',
  clientVersion: '1.65.10',
  deviceMake: 'Oculus',
  deviceModel: 'Quest 3',
  androidSdkVersion: 32,
  userAgent: 'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip',
  osName: 'Android',
  osVersion: '12L',
  hl: 'en',
  timeZone: 'UTC',
  utcOffsetMinutes: 0
};
const androidVrBotCheckCooldownMs = 10 * 60_000;
const androidVrRapidResolveWindowMs = 5_000;
const androidVrRapidResolveMinGapMs = 1_500;
const upstreamFailureCooldownMs = 10 * 60_000;

function formatMimeFamily(format) {
  return (format?.mime_type || format?.mimeType || '').split(';', 1)[0].trim().toLowerCase();
}

function directStreamingFormats(info = {}) {
  const streamingData = info.streaming_data || info.streamingData || {};
  return [
    ...(streamingData.formats || []),
    ...(streamingData.adaptive_formats || streamingData.adaptiveFormats || [])
  ];
}

function requireDirectStreamingFormats(info) {
  if (directStreamingFormats(info).length) return info;

  const playability = info?.playability_status || info?.playabilityStatus || {};
  const reason = playability.reason || '';
  const status = playability.status || '';
  const error = new Error(
    reason ||
    (status ? `YouTube returned ${status} without direct playback formats` : 'InnerTube returned no direct playback formats')
  );
  error.info = playability;
  error.noStreamingFormats = true;
  throw error;
}

function streamExpiresAt(url) { try { return Number(new URL(url).searchParams.get('expire')) * 1000 || Date.now() + 45 * 60_000; } catch { return Date.now() + 45 * 60_000; } }
export function createPlaybackService({
  authState,
  cookieWithPlaybackDefaults,
  getBrowserInnertube,
  getGuestInnertube,
  hasBrowserLoginCookie,
  refreshBrowserAuth,
  youtubeWebOrigin
}) {
  const streamCache = createPlaybackStreamCache();
  const upstreamFailures = new Map();
  const songCache = createSongCache();
  let foregroundResolveStarts = [];
  let androidVrIdentityPromise;
  let androidVrCooldownUntil = 0;
  let androidVrCooldownReason = '';

  function responseCookies(headers) {
    const setCookies = typeof headers.getSetCookie === 'function'
      ? headers.getSetCookie()
      : (headers.get('set-cookie') ? [headers.get('set-cookie')] : []);

    return setCookies
      .map((cookie) => cookie.split(';', 1)[0])
      .filter((cookie) => cookie && !cookie.startsWith('__Secure-YEC='));
  }
  async function androidVrIdentity(videoId, { useBrowserAuth = false } = {}) {
    if (useBrowserAuth && hasBrowserLoginCookie()) {
      const cookie = cookieWithPlaybackDefaults(authState.browser.cookie);

      if (authState.browser.visitorData) {
        return {
          visitorData: authState.browser.visitorData,
          cookie
        };
      }

      const watchUrl = `${youtubeWebOrigin}/watch?v=${encodeURIComponent(videoId)}&bpctr=9999999999&has_verified=1`;
      const response = await fetch(watchUrl, {
        headers: {
          'User-Agent': youtubeWebUserAgent,
          Cookie: cookie
        }
      });
      const html = await response.text();
      const visitorData = /"visitorData":"([^"]+)/.exec(html)?.[1];

      if (visitorData) {
        authState.browser.visitorData = visitorData;
        return {
          visitorData,
          cookie
        };
      }
    }

    if (androidVrIdentityPromise) return androidVrIdentityPromise;

    androidVrIdentityPromise = (async () => {
      const watchUrl = `${youtubeWebOrigin}/watch?v=${encodeURIComponent(videoId)}&bpctr=9999999999&has_verified=1`;
      const response = await fetch(watchUrl, {
        headers: {
          'User-Agent': youtubeWebUserAgent,
          Cookie: cookieWithPlaybackDefaults()
        }
      });
      const html = await response.text();
      const visitorData = /"visitorData":"([^"]+)/.exec(html)?.[1];

      if (!response.ok || !visitorData) {
        throw new Error('Could not prepare YouTube visitor identity for playback');
      }

      return {
        visitorData,
        cookie: cookieWithPlaybackDefaults(responseCookies(response.headers).join('; '))
      };
    })().catch((error) => {
      androidVrIdentityPromise = null;
      throw error;
    });

    return androidVrIdentityPromise;
  }

  function androidVrCooldownActive() {
    return Date.now() < androidVrCooldownUntil;
  }

  function pauseAndroidVrFallback(error) {
    const wasActive = androidVrCooldownActive();
    androidVrCooldownUntil = Date.now() + androidVrBotCheckCooldownMs;
    const reason = error?.message || 'YouTube requested bot verification';

    if (!wasActive || reason !== androidVrCooldownReason) {
      androidVrCooldownReason = reason;
      console.warn(`Android VR stream fallback paused for ${Math.round(androidVrBotCheckCooldownMs / 60_000)} minutes: ${reason}`);
    }
  }

  function androidVrRapidResolveActive(options = {}) {
    if (options.lowPriority) return true;

    const now = Date.now();
    foregroundResolveStarts = foregroundResolveStarts.filter((startedAt) =>
      now - startedAt < androidVrRapidResolveWindowMs
    );
    const previousStartedAt = foregroundResolveStarts.at(-1) || 0;
    foregroundResolveStarts.push(now);

    return foregroundResolveStarts.length >= 3 ||
      (previousStartedAt && now - previousStartedAt < androidVrRapidResolveMinGapMs);
  }

  async function basicPlaybackInfo(yt, videoId, options = {}) {
    if (typeof yt.getBasicInfo === 'function') {
      const request = { client: 'YTMUSIC' };
      if (options.poToken) request.po_token = options.poToken;
      return yt.getBasicInfo(videoId, request);
    }

    const request = {};
    if (options.poToken) request.po_token = options.poToken;
    return yt.music.getInfo(videoId, request);
  }

  async function playbackInfo(videoId, options = {}) {
    const preferBrowserAuth = Boolean(options.preferBrowserAuth || androidVrCooldownActive());
    if (preferBrowserAuth) await refreshBrowserAuth();
    const browserYtPromise = getBrowserInnertube();
    const browserYt = preferBrowserAuth && browserYtPromise ? await browserYtPromise : null;
    const primaryYt = options.yt || browserYt || await getGuestInnertube();
    const browserPlaybackOptions = () => hasBrowserLoginCookie()
      ? { poToken: authState.browser.poToken }
      : {};

    try {
      return {
        yt: primaryYt,
        info: requireDirectStreamingFormats(
          options.info || await basicPlaybackInfo(primaryYt, videoId, browserYt === primaryYt ? browserPlaybackOptions() : {})
        )
      };
    } catch (error) {
      let fallbackBrowserYt = browserYtPromise ? await browserYtPromise : null;
      const shouldRetryBrowser = error.noStreamingFormats ||
        isAgeGatePlaybackError(error) ||
        isBotCheckPlaybackError(error) ||
        isPrivatePlaybackError(error);

      if ((error.noStreamingFormats || isPrivatePlaybackError(error)) && hasBrowserLoginCookie()) {
        try {
          await refreshBrowserAuth();
          fallbackBrowserYt = await getBrowserInnertube() || fallbackBrowserYt;
        } catch {
          // The captured browser client can still recover if refreshing cookies fails.
        }
      }

      if (
        fallbackBrowserYt &&
        shouldRetryBrowser &&
        (primaryYt !== fallbackBrowserYt || error.noStreamingFormats)
      ) {
        if (primaryYt !== fallbackBrowserYt) pauseAndroidVrFallback(error);
        try {
          return {
            yt: fallbackBrowserYt,
            info: requireDirectStreamingFormats(
              await basicPlaybackInfo(fallbackBrowserYt, videoId, browserPlaybackOptions())
            )
          };
        } catch (browserError) {
          error = browserError;
        }
      }

      const guestYt = await getGuestInnertube();
      if (primaryYt !== guestYt && canFallbackToGuest(error)) {
        return {
          yt: guestYt,
          info: requireDirectStreamingFormats(await basicPlaybackInfo(guestYt, videoId))
        };
      }

      throw error;
    }
  }

  async function androidVrPlayer(videoId, options = {}) {
    const [identity, yt] = await Promise.all([
      androidVrIdentity(videoId, options),
      getGuestInnertube()
    ]);
    const context = {
      client: {
        ...androidVrClient,
        visitorData: identity.visitorData
      }
    };
    const response = await fetch('https://www.youtube.com/youtubei/v1/player?prettyPrint=false', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'User-Agent': androidVrClient.userAgent,
        'X-Youtube-Client-Name': '28',
        'X-Youtube-Client-Version': androidVrClient.clientVersion,
        'X-Goog-Visitor-Id': identity.visitorData,
        Origin: youtubeWebOrigin,
        Cookie: identity.cookie
      },
      body: JSON.stringify({
        context,
        videoId,
        playbackContext: {
          contentPlaybackContext: {
            html5Preference: 'HTML5_PREF_WANTS',
            signatureTimestamp: yt.session.player?.signature_timestamp
          }
        },
        contentCheckOk: true,
        racyCheckOk: true
      })
    });
    const text = await response.text();
    let data;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = {};
    }

    if (!response.ok || data.playabilityStatus?.status !== 'OK') {
      const message = data.playabilityStatus?.reason || data.error?.message || `YouTube player request failed with HTTP ${response.status}`;
      const error = new Error(message);
      error.status = response.status;
      error.info = text;
      throw error;
    }

    return data;
  }

  async function resolveAndroidVrStream(videoId, options = {}) {
    let data;
    if (options.useBrowserAuth && hasBrowserLoginCookie()) {
      data = await androidVrPlayer(videoId, { useBrowserAuth: true });
    }

    try {
      data ||= await androidVrPlayer(videoId);
    } catch (error) {
      if (!hasBrowserLoginCookie() || (!isAgeGatePlaybackError(error) && !isBotCheckPlaybackError(error))) throw error;
      data = await androidVrPlayer(videoId, { useBrowserAuth: true });
    }

    const rawFormats = [
      ...(data.streamingData?.formats || []),
      ...(data.streamingData?.adaptiveFormats || [])
    ];
    const wantsVideo = options.mediaKind === 'video';
    const audioFormats = rawPlayableAudioFormats(rawFormats);
    const videoOnlyFormats = rawPlayableVideoOnlyFormats(rawFormats);
    const inlineVideoFormats = rawPlayableVideoFormats(rawFormats);
    const avoidedItags = new Set((options.avoidItags || []).map(String));
    const avoidedMimeTypes = new Set((options.avoidMimeTypes || []).map((mime) => mime.split(';', 1)[0].trim().toLowerCase()).filter(Boolean));
    const selectableAudioFormats = audioFormats.filter((candidate) =>
      !avoidedItags.has(String(candidate.itag)) && !avoidedMimeTypes.has(formatMimeFamily(candidate))
    );
    const selectableVideoOnlyFormats = videoOnlyFormats.filter((candidate) => !avoidedItags.has(String(candidate.itag)));
    const selectableInlineVideoFormats = inlineVideoFormats.filter((candidate) => !avoidedItags.has(String(candidate.itag)));
    const formats = wantsVideo
      ? [...selectableVideoOnlyFormats, ...selectableInlineVideoFormats].sort(compareVideoFormats)
      : selectableAudioFormats;
    const format = options.itag
      ? formats.find((candidate) => String(candidate.itag) === String(options.itag))
      : wantsVideo
        ? (options.preferInlineVideo
          ? (chooseVideoFormatFromFormats(selectableInlineVideoFormats, options.supportedMimes) || chooseVideoOnlyFormatFromFormats(selectableVideoOnlyFormats, options.supportedMimes))
          : (chooseVideoOnlyFormatFromFormats(selectableVideoOnlyFormats, options.supportedMimes) || chooseVideoFormatFromFormats(selectableInlineVideoFormats, options.supportedMimes)))
        : chooseAudioFormatFromFormats(selectableAudioFormats, options.supportedMimes);
    const audioFormat = wantsVideo && format && !formatHasInlineAudio(format)
      ? chooseAudioFormatFromFormats(selectableAudioFormats, options.supportedAudioMimes || [])
      : null;

    if (!format) throw new Error(`No playable Android VR ${wantsVideo ? 'video' : 'audio'} format was returned by YouTube`);
    if (wantsVideo && !formatHasInlineAudio(format) && !audioFormat) {
      throw new Error('No playable Android VR audio companion format was returned by YouTube');
    }

    return {
      url: format.url,
      audioUrl: audioFormat?.url || '',
      format: formatMetadata(format),
      audioFormat: audioFormat ? formatMetadata(audioFormat) : null,
      mediaKind: wantsVideo ? 'video' : 'audio',
      cacheMetadata: cacheMetadata(options),
      userAgent: androidVrClient.userAgent,
      expiresAt: streamExpiresAt(format.url)
    };
  }

  async function resolveStream(videoId, options = {}) {
    const cacheKey = streamCache.key(videoId, options);
    const cached = streamCache.getStream(cacheKey);
    const explicitItag = options.itag ? String(options.itag) : '';
    const refreshedItag = options.refreshStream ? explicitItag : '';
    const avoidedItags = new Set((options.avoidItags || []).map(String));
    for (const [key, until] of upstreamFailures) {
      if (until <= Date.now()) upstreamFailures.delete(key);
      else if (key.startsWith(`${videoId}:${options.mediaKind === 'video' ? 'video' : 'audio'}:`)) {
        const failedItag = key.split(':').pop();
        // A proxy retry is allowed to refresh the failed format once. Other
        // requests must not silently substitute another format under an URL
        // whose explicit itag identifies a different byte stream.
        if (failedItag !== refreshedItag) avoidedItags.add(failedItag);
      }
    }
    if (explicitItag && avoidedItags.has(explicitItag)) {
      throw new Error(`Requested stream format ${explicitItag} is temporarily unavailable`);
    }
    if (
      !options.refreshStream &&
      cached &&
      cached.expiresAt > Date.now() + 60_000 &&
      !avoidedItags.has(String(cached.format?.itag || ''))
    ) {
      return cached;
    }
    const wantsVideo = options.mediaKind === 'video';
    const requiresAuth = Boolean(options.requiresAuth);
    const avoidAndroidVr = requiresAuth || androidVrRapidResolveActive(options);
    const canTryAndroidVr = !requiresAuth && (!wantsVideo || !avoidAndroidVr) && !androidVrCooldownActive();
    if (canTryAndroidVr) {
      try {
        const cacheEntry = await resolveAndroidVrStream(videoId, {
          ...options,
          avoidItags: [...avoidedItags],
          useBrowserAuth: androidVrCooldownActive()
        });
        if (!await validateUpstreamStreamUrl(cacheEntry.url, { fallbackUserAgent: cacheEntry.userAgent || youtubeWebUserAgent })) throw new Error('Android VR stream probes failed');
        streamCache.cacheStream(videoId, cacheKey, cacheEntry, options);
        return cacheEntry;
      } catch (error) {
        if (isAgeGatePlaybackError(error)) {
          error.ageGateBlocked = true;
          throw error;
        } else if (isBotCheckPlaybackError(error) || error.message === 'Android VR stream probes failed' || /Could not prepare YouTube visitor identity/i.test(error.message || '')) {
          pauseAndroidVrFallback(error);
        } else {
          console.warn(`Android VR stream fallback failed: ${error.message}`);
        }
      }
    }
    const hasPrefetchedPlayback = Boolean(options.playbackClient && options.playbackInfo);
    const preferBrowserPlayback = Boolean(!hasPrefetchedPlayback && (requiresAuth || androidVrCooldownActive() || (wantsVideo && avoidAndroidVr)));
    const { yt, info } = await playbackInfo(videoId, {
      yt: preferBrowserPlayback ? null : options.playbackClient,
      info: preferBrowserPlayback ? null : options.playbackInfo,
      preferBrowserAuth: preferBrowserPlayback
    });
    const audioFormats = playableAudioFormats(info);
    const supportedAudioMimes = options.supportedMimes;
    const supportedCompanionMimes = options.supportedAudioMimes || [];
    const videoOnlyFormats = playableVideoOnlyFormats(info);
    const inlineVideoFormats = playableVideoFormats(info);
    const videoFormats = [...videoOnlyFormats, ...inlineVideoFormats].sort(compareVideoFormats);
    const avoidedMimeTypes = new Set((options.avoidMimeTypes || []).map((mime) => mime.split(';', 1)[0].trim().toLowerCase()).filter(Boolean));
    const selectableAudioFormats = audioFormats.filter((candidate) =>
      !avoidedItags.has(String(candidate.itag)) && !avoidedMimeTypes.has(formatMimeFamily(candidate))
    );
    const selectableVideoOnlyFormats = videoOnlyFormats.filter((candidate) => !avoidedItags.has(String(candidate.itag)));
    const selectableInlineVideoFormats = inlineVideoFormats.filter((candidate) => !avoidedItags.has(String(candidate.itag)));
    let format = options.itag && !avoidedItags.has(String(options.itag))
      ? (wantsVideo ? videoFormats : audioFormats).find((candidate) => String(candidate.itag) === String(options.itag))
      : wantsVideo
        ? (options.preferInlineVideo
          ? (chooseVideoFormatFromFormats(selectableInlineVideoFormats, options.supportedMimes) || chooseVideoOnlyFormatFromFormats(selectableVideoOnlyFormats, options.supportedMimes))
          : (chooseVideoOnlyFormatFromFormats(selectableVideoOnlyFormats, options.supportedMimes) || chooseVideoFormatFromFormats(selectableInlineVideoFormats, options.supportedMimes)))
        : chooseAudioFormatFromFormats(selectableAudioFormats, supportedAudioMimes);
    if (!format) throw new Error(`No playable ${wantsVideo ? 'video' : 'audio'} format was returned by InnerTube`);
    const rejectedItags = new Set();
    let cacheEntry = null;
    while (format && !cacheEntry) {
      const audioFormat = wantsVideo && !formatHasInlineAudio(format)
        ? chooseAudioFormatFromFormats(selectableAudioFormats, supportedCompanionMimes)
        : null;
      if (wantsVideo && !formatHasInlineAudio(format) && !audioFormat) {
        throw new Error('No playable audio companion format was returned by InnerTube');
      }
      const candidateUrl = await format.decipher(yt.session.player);
      const candidateEntry = {
        url: candidateUrl,
        audioUrl: audioFormat ? await audioFormat.decipher(yt.session.player) : '',
        format: formatMetadata(format),
        audioFormat: audioFormat ? formatMetadata(audioFormat) : null,
        mediaKind: wantsVideo ? 'video' : 'audio',
        cacheMetadata: cacheMetadata(options),
        expiresAt: streamExpiresAt(candidateUrl)
      };
      if (await validateUpstreamStreamUrl(candidateEntry.url, { fallbackUserAgent: candidateEntry.userAgent || youtubeWebUserAgent })) {
        cacheEntry = candidateEntry;
        break;
      }
      rejectedItags.add(String(format.itag));
      if (options.itag || wantsVideo) break;
      const remainingFormats = selectableAudioFormats.filter((candidate) => !rejectedItags.has(String(candidate.itag)));
      format = remainingFormats.length ? chooseAudioFormatFromFormats(remainingFormats, supportedAudioMimes) : null;
    }
    if (!cacheEntry) throw new Error(`No validated ${wantsVideo ? 'video' : 'audio'} stream was returned by InnerTube`);
    streamCache.cacheStream(videoId, cacheKey, cacheEntry, options);
    return cacheEntry;
  }
  async function proxyStream(videoId, req, res, allowRetry = true, retryOptions = null) {
    const requestUrl = new URL(req.url, 'http://127.0.0.1');
    const mediaKind = requestUrl.searchParams.get('media') === 'video' ? 'video' : 'audio';
    const requestedItag = requestUrl.searchParams.get('itag');
    const effectiveItag = requestedItag || retryOptions?.itag || null;
    const requestedCacheKey = streamCache.key(videoId, { mediaKind, itag: requestedItag });
    const fallbackCacheKey = streamCache.key(videoId, { mediaKind });
    const cachedOptions = retryOptions || streamCache.getOptions(requestedCacheKey) || streamCache.getOptions(fallbackCacheKey) || {};
    const stream = await resolveStream(videoId, { ...cachedOptions, itag: effectiveItag, mediaKind });
    const totalLength = Number(stream.format.contentLength || 0);
    const contentType = stream.format.mimeType || 'audio/mp4';
    const rangeHeader = req.headers.range || '';
    const range = parseRangeHeader(rangeHeader, totalLength);
    if (!range.ok) return rangeNotSatisfiable(res, totalLength);
    if (await songCache.serve({ videoId, stream, range, req, res })) return;
    if (req.method === 'HEAD') {
      res.writeHead(200, proxyHeadResponseHeaders(contentType, totalLength));
      res.end();
      return;
    }
    const fallbackUserAgent = stream.userAgent || youtubeWebUserAgent;
    const upstreamRequest = upstreamStreamRequest(stream.url, {
      fallbackUserAgent,
      rangeHeader: upstreamRangeHeader(range, totalLength)
    });
    const upstream = await fetch(upstreamRequest.url, { headers: upstreamRequest.headers });
    const failureKey = `${videoId}:${mediaKind}:${stream.format.itag}`;
    if ([403, 410, 429, 500, 502, 503, 504].includes(upstream.status) && allowRetry) {
      await upstream.body?.cancel().catch(() => {});
      upstreamFailures.set(failureKey, Date.now() + upstreamFailureCooldownMs);
      streamCache.deleteKey(streamCache.key(videoId, { mediaKind }));
      streamCache.deleteKey(streamCache.key(videoId, { mediaKind, itag: stream.format.itag }));
      return proxyStream(videoId, req, res, false, {
        ...cachedOptions,
        itag: String(stream.format.itag),
        mediaKind,
        refreshStream: true
      });
    }
    if (!upstream.ok && upstream.status !== 206) {
      throw new Error(`Upstream stream failed with HTTP ${upstream.status}`);
    }
    upstreamFailures.delete(failureKey);
    const headers = proxyResponseHeaders(upstream, contentType, totalLength, range.wantsRange);
    res.writeHead(upstream.status, headers);
    if (!upstream.body) {
      res.end();
      return;
    }
    if (await songCache.pipeAndStore({ videoId, stream, range, upstream, res })) return;
    try {
      await pipeWebBody(upstream.body, res);
    } catch (error) {
      if (!res.writableEnded) res.destroy(error);
    }
  }
  return { androidVrCooldownActive, playbackInfo, proxyStream, resolveStream, songCache, updateSongCacheSettings: songCache.update };
}

function cacheMetadata(options = {}) {
  return { title: options.title || '', artist: options.artist || options.artists?.[0] || '', album: options.album || '', thumbnail: options.thumbnail || '', durationSeconds: Number(options.durationSeconds || 0) };
}

function formatMetadata(format) {
  return {
    itag: format.itag,
    mimeType: format.mime_type || format.mimeType || '',
    bitrate: format.bitrate || format.average_bitrate || format.averageBitrate || 0,
    contentLength: format.content_length || format.contentLength || 0
  };
}
