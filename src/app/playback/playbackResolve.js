import { nextTick } from 'vue';
import { installPlaybackCollectionQueue, playlistPlayedTrackIds } from './playbackCollectionQueue.js';
import { resumeMediaAt } from './playbackDuration.js';

function trackDurationSeconds(item = {}) {
  const direct = Number(item.durationSeconds || 0);
  if (direct > 0) return Math.round(direct);
  const parts = String(item.duration || '').trim().split(':').map(Number);
  if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
  return parts.reduce((total, part) => total * 60 + part, 0);
}

export function installPlaybackResolve(ctx) {
  let preloadPromise = null;
  let preloadTrackId = '';

  ctx.shouldPlayAsVideo = function shouldPlayAsVideo(item, options = {}) {
    if (options.mediaKind === 'video' || item?.mediaKind === 'video') return true;
    if (options.mediaKind === 'audio' || item?.mediaKind === 'audio') return false;
    if (item?.isAudioOnly) return false;

    const text = `${item?.type || ''} ${item?.musicVideoType || ''} ${item?.title || ''} ${item?.subtitle || ''}`;
    return item?.type === 'video' ||
      /MUSIC_VIDEO_TYPE_(OMV|UGC)/i.test(text) ||
      /\b(live|performance|concert|official video|music video|visualizer)\b/i.test(text);
  };

  ctx.trackHasVideoVersion = function trackHasVideoVersion(track) {
    return ctx.shouldPlayAsVideo({ ...track, mediaKind: '' });
  };

  ctx.trackResolvePayload = function trackResolvePayload(item, options = {}) {
    if (!ctx.isPlayableTrack(item)) return null;
    const mediaKind = ctx.shouldPlayAsVideo(item, options) ? 'video' : 'audio';

    return {
      videoId: item.id,
      title: item.title,
      artist: item.artist || item.artists?.[0] || '',
      artists: item.artists || [],
      album: item.album || '',
      thumbnail: item.thumbnail || '',
      durationSeconds: trackDurationSeconds(item),
      explicit: Boolean(item.explicit),
      type: item.type || '',
      musicVideoType: item.musicVideoType || '',
      isAudioOnly: Boolean(item.isAudioOnly),
      mediaKind,
      preload: Boolean(options.preload),
      refreshStream: Boolean(options.refreshStream),
      avoidItags: options.avoidItags || [],
      avoidMimeTypes: options.avoidMimeTypes || [],
      preferAudioOnly: mediaKind === 'audio' ? (options.preferAudioOnly ?? true) : false,
      supportedMimes: ctx.supportedAudioMimes(),
      supportedVideoMimes: ctx.supportedVideoMimes()
    };
  };

  ctx.resolvePlayableTrack = function resolvePlayableTrack(item, options = {}) {
    if (!ctx.isPlayableTrack(item)) return Promise.reject(new Error('Track is unavailable'));
    return ctx.emitWithReply(
      'music:track',
      ctx.trackResolvePayload(item, options),
      { timeoutMs: options.preload ? 30_000 : 15_000 }
    );
  };

  ctx.activeTrackFromResolved = function activeTrackFromResolved(item, resolved) {
    return {
      ...resolved,
      ...item,
      id: resolved.id || item.id,
      streamUrl: resolved.streamUrl,
      audioStreamUrl: resolved.audioStreamUrl || '',
      mediaKind: resolved.mediaKind || item.mediaKind || 'audio',
      mimeType: resolved.mimeType,
      itag: resolved.itag,
      bitrate: Number(resolved.bitrate || 0),
      audioItag: resolved.audioItag || null,
      streamExpiresAt: resolved.streamExpiresAt || 0,
      playbackSource: resolved.playbackSource || 'youtube',
      externalSource: resolved.externalSource || '',
      thumbnail: item.thumbnail || resolved.thumbnail,
      isLive: Boolean(resolved.isLive || item.isLive),
      durationSeconds: item.durationSeconds || resolved.durationSeconds || 0,
      artists: item.artists?.length ? item.artists : resolved.artist ? [resolved.artist] : []
    };
  };

  ctx.preloadedTrackMatches = function preloadedTrackMatches(item) {
    return Boolean(item?.id && ctx.nextTrackPreload.value?.track?.id === item.id && (!ctx.nextTrackPreload.value?.resolved?.streamExpiresAt || ctx.nextTrackPreload.value.resolved.streamExpiresAt > Date.now() + 5 * 60_000));
  };

  ctx.clearNextPreload = function clearNextPreload(options = {}) {
    if (!options.force && ctx.autoCrossfade?.isActive?.()) {
      return false;
    }

    ctx.nextPreloadRequest += 1;
    ctx.nextTrackPreload.value = null;
    ctx.resetNextCrossfadeAnalysis();
    ctx.clearAudioElement(ctx.standbyAudio());
    return true;
  };

  ctx.prepareCorsMediaElement = function prepareCorsMediaElement(element) {
    if (element) element.crossOrigin = 'anonymous';
  };

  ctx.preloadNextTrack = async function preloadNextTrack(options = {}) {
    const next = ctx.queue.value[0];
    if (!ctx.isPlayableTrack(next) || next.mediaKind === 'video' || !ctx.socket.value?.connected) {
      return false;
    }
    if (!options.force && ctx.preloadedTrackMatches(next)) {
      const hasStandbySrc = Boolean(ctx.standbyAudio()?.src);
      return hasStandbySrc;
    }
    if (preloadPromise && preloadTrackId === next.id) {
      return preloadPromise;
    }

    preloadTrackId = next.id;
    const request = (async () => {
      const requestId = ctx.nextPreloadRequest + 1;
      ctx.nextPreloadRequest = requestId;
      ctx.resetNextCrossfadeAnalysis(next.id);

      try {
        const resolved = await ctx.resolvePlayableTrack(next, { preload: true, mediaKind: 'audio' });
        if (requestId !== ctx.nextPreloadRequest || ctx.queue.value[0]?.id !== next.id) return false;

        ctx.nextTrackPreload.value = { track: next, resolved };
        await nextTick();

        const audio = ctx.standbyAudio();
        if (!audio) return false;
        audio.pause();
        ctx.prepareCorsMediaElement(audio);
        audio.src = resolved.streamUrl;
        audio.volume = 1;
        ctx.audioAnalyzer.connectElement(audio);
        ctx.setAudioNormalization(audio);
        ctx.audioAnalyzer.setVolume(audio, 0);
        audio.load();
        if (ctx.crossfadeMode.value === 'smart') {
          void ctx.analyzeNextCrossfadeTrack(next, resolved.streamUrl, trackDurationSeconds(next));
        }
        return true;
      } catch (error) {
        if (requestId === ctx.nextPreloadRequest) ctx.nextTrackPreload.value = null;
        return false;
      }
    })();

    preloadPromise = request;
    try {
      return await request;
    } finally {
      if (preloadPromise === request) {
        preloadPromise = null;
        preloadTrackId = '';
      }
    }
  };

  ctx.playTrack = async function playTrack(item, options = {}) {
    if (!ctx.isPlayableTrack(item)) return;
    if (!options.listeningPartySync && ctx.requestListeningPartyHostControl?.({
      action: 'play-track',
      track: item,
      options: ctx.listeningPartyPlaybackOptions?.(options) || {}
    })) {
      return;
    }
    const requestedVideo = ctx.shouldPlayAsVideo(item, options);
    const trackItem = options.recoveryAttempt
      ? item
      : { ...item, playbackFallbackTried: false, streamRefreshTried: false };
    ctx.autoCrossfade.cancel();
    ctx.clearPlaybackStallRecovery?.();
    if (ctx.activeTrack.value?.id && ctx.activeTrack.value.id !== trackItem.id) {
      ctx.markPlaylistTrackPlayed?.(ctx.activeTrack.value);
    }
    if (ctx.activeTrack.value?.id) ctx.finishYouTubeHistory?.();
    const wantsVideo = requestedVideo;

    if (options.resetHistory) {
      ctx.history.value = [];
    } else if (!options.skipHistory && ctx.activeTrack.value?.id && ctx.activeTrack.value.id !== trackItem.id) {
      ctx.history.value.unshift(ctx.activeTrack.value);
      ctx.history.value = ctx.history.value.slice(0, 30);
    }

    ctx.loading.value = true;
    ctx.buffering.value = true;
    ctx.playbackError.value = '';
    ctx.errorMessage.value = '';
    const playRequestId = ctx.playTrackRequest + 1;
    ctx.playTrackRequest = playRequestId;
    const stalePlayRequest = () => playRequestId !== ctx.playTrackRequest;

    try {
      const usedPreload = !options.refreshStream && ctx.preloadedTrackMatches(trackItem);
      const resolved = options.resolved || (usedPreload ? ctx.nextTrackPreload.value?.resolved : null) || await ctx.resolvePlayableTrack(trackItem, options);
      if (stalePlayRequest()) return;
      ctx.activeMediaKind.value = resolved.mediaKind || (wantsVideo ? 'video' : 'audio');
      ctx.activeTrack.value = ctx.activeTrackFromResolved(trackItem, resolved);
      ctx.videoPlayerMinimized.value = false;
      ctx.resetCrossfadeAnalysis();
      if (ctx.activeTrackIsVideo.value) {
        ctx.clearNextPreload();
      }

      if (!options.preserveQueue) {
        const queueSource = (options.queueSource || ctx.activeQueueSource())
          .filter(ctx.isPlayableTrack);
        const queueStart = queueSource.findIndex((track) => track.id === trackItem.id);
        const isPlayFromQueue = Boolean(
          options.queueAlreadyShuffled ||
          (options.queueSource && (
            options.queueSource === ctx.queue.value ||
            (options.queueSource.length === ctx.queue.value.length &&
             options.queueSource.every((t, i) => t?.id === ctx.queue.value[i]?.id))
          ))
        );

        let seededQueue;
        if (ctx.shuffleEnabled.value && !isPlayFromQueue) {
          const seen = new Set([trackItem.id]);
          seededQueue = [];
          const startIdx = queueStart >= 0 ? queueStart + 1 : 0;
          for (let i = startIdx; i < queueSource.length && seededQueue.length < 100; i++) {
            const track = queueSource[i];
            if (ctx.isPlayableTrack(track) && track.id && !seen.has(track.id)) {
              seen.add(track.id);
              seededQueue.push(track);
            }
          }
        } else {
          seededQueue = queueSource
            .slice(queueStart >= 0 ? queueStart + 1 : 0)
            .filter((track) => ctx.isPlayableTrack(track) && track.id !== trackItem.id)
            .slice(0, 100);
        }

        const nextQueue = ctx.shuffleEnabled.value && !isPlayFromQueue
          ? ctx.shuffleItems(seededQueue)
          : seededQueue;

        if (options.queueSource || !ctx.queue.value.length || !ctx.queue.value.some((track) => track.id === trackItem.id)) {
          if (isPlayFromQueue) {
            const nextQueueIds = new Set(nextQueue.map((t) => t.id));
            ctx.shuffleSourceQueue.value = ctx.shuffleEnabled.value
              ? ctx.shuffleSourceQueue.value.filter((track) => nextQueueIds.has(track.id))
              : [];
            const playlistContext = ctx.playbackPlaylistContext.value;
            if (playlistContext && !playlistContext.allTracks.some((track) => track.id === trackItem.id)) {
              ctx.playbackPlaylistContext.value = null;
            }
          } else {
            ctx.shuffleSourceQueue.value = ctx.shuffleEnabled.value ? seededQueue : [];
            const detail = ctx.browseDetail.value;
            if (detail?.kind === 'playlist' && detail.browseId && options.queueSource) {
              const allTracks = ctx.tracksWithCollectionContext(detail).filter(ctx.isPlayableTrack);
              ctx.playbackPlaylistContext.value = {
                browseId: detail.browseId,
                continuation: detail.continuation || '',
                hasMoreTracks: Boolean(detail.hasMoreTracks),
                shuffled: ctx.shuffleEnabled.value,
                playedTrackIds: playlistPlayedTrackIds(allTracks, trackItem.id),
                allTracks
              };
            } else {
              ctx.playbackPlaylistContext.value = null;
            }
          }
          ctx.queue.value = nextQueue;
        }
      }

      await nextTick();
      if (stalePlayRequest()) return;

      if (!ctx.activeTrackIsVideo.value) {
        ctx.activeAudioDeck.value = ctx.activeAudioDeck.value === 'main' ? 'next' : 'main';
      }

      const media = await ctx.waitForPlaybackElement();
      const audio = ctx.currentAudio();
      const standby = ctx.standbyAudio();
      const videoAudio = ctx.videoAudioRef.value;
      if (!media) throw new Error(ctx.activeTrackIsVideo.value ? 'Video element is unavailable' : 'Audio element is unavailable');

      ctx.isSeeking.value = false;
      ctx.currentTime.value = 0;
      ctx.seekPosition.value = 0;
      ctx.duration.value = 0;
      ctx.clearAudioElement(standby);
      if (ctx.activeTrackIsVideo.value) {
        ctx.clearAudioElement(audio);
        ctx.clearAudioElement(videoAudio);
        ctx.clearMediaElement(ctx.videoRef.value);
      } else {
        ctx.clearMediaElement(ctx.videoRef.value);
        ctx.clearAudioElement(videoAudio);
        audio?.pause();
      }
      ctx.prepareCorsMediaElement(media);
      media.src = resolved.streamUrl;
      media.load();

      if (ctx.activeTrackIsVideo.value && resolved.audioStreamUrl && videoAudio) {
        ctx.prepareCorsMediaElement(videoAudio);
        videoAudio.src = resolved.audioStreamUrl;
        videoAudio.load();
        ctx.audioAnalyzer.connectElement(videoAudio);
        ctx.setAudioNormalization(videoAudio);
        ctx.audioAnalyzer.setVolume(videoAudio, ctx.effectivePlaybackVolume());
        media.muted = true;
      } else {
        media.muted = false;
        ctx.audioAnalyzer.connectElement(media);
        ctx.setAudioNormalization(media);
        ctx.audioAnalyzer.setVolume(media, ctx.effectivePlaybackVolume());
      }

      await ctx.audioAnalyzer.resume();
      if (stalePlayRequest()) return;
      await resumeMediaAt(media, options.resumeAt);
      if (ctx.activeTrackIsVideo.value && resolved.audioStreamUrl && videoAudio) {
        videoAudio.currentTime = media.currentTime || 0;
        await Promise.all([media.play(), videoAudio.play()]);
      } else {
        await media.play();
      }
      if (stalePlayRequest()) return;

      ctx.startYouTubeHistory?.(ctx.activeTrack.value?.youtubeVideoId || resolved.youtubeVideoId || ctx.activeTrack.value?.id);

      ctx.recordSessionEvent?.(options.sessionAction || 'manual', ctx.activeTrack.value, {
        queue: ctx.queue.value,
        queueOrigin: ctx.activeQueueOriginLabel?.value || '',
        durationSeconds: ctx.duration.value || ctx.activeTrack.value?.durationSeconds || 0
      });

      if (!ctx.activeTrackIsVideo.value && ctx.crossfadeMode.value === 'smart') {
        void ctx.analyzeCurrentCrossfadeTrack(
          ctx.activeTrack.value,
          resolved.streamUrl,
          ctx.duration.value || ctx.activeTrack.value?.durationSeconds || 0
        );
      }

      if (usedPreload || ctx.activeTrackIsVideo.value) ctx.clearNextPreload();
      if (!ctx.activeTrackIsVideo.value) void ctx.preloadNextTrack();
      void ctx.refillPlaylistQueue();
    } catch (error) {
      if (stalePlayRequest()) return;
      const recoveryTrack = ctx.activeTrack.value?.id === trackItem.id
        ? ctx.activeTrack.value
        : trackItem;
      const fallback = !wantsVideo && !options.recoveryAttempt && ctx.retryAudioWithAlternateFormat?.(recoveryTrack);
      if (fallback) {
        await fallback;
        return;
      }
      if (ctx.isInterruptedPlaybackRequest(error)) return;
      ctx.playbackError.value = error.message;
    } finally {
      if (playRequestId === ctx.playTrackRequest) {
        ctx.loading.value = false;
        ctx.buffering.value = false;
      }
    }
  };

  ctx.describeStreamFailure = async function describeStreamFailure(url) {
    if (!url) return '';

    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          Range: 'bytes=0-4095'
        },
        cache: 'no-store'
      });
      const contentType = response.headers.get('content-type') || 'unknown content type';
      const contentRange = response.headers.get('content-range');
      const detail = `${response.status} ${response.statusText || ''}`.trim();

      if (!response.ok && response.status !== 206) {
        const body = await response.text().catch(() => '');
        const message = body ? `: ${body.slice(0, 160)}` : '';
        return `Stream returned ${detail} (${contentType})${message}`;
      }

      return `Stream returned ${detail} (${contentType}${contentRange ? `, ${contentRange}` : ''})`;
    } catch (error) {
      return `Stream check failed: ${error.message}`;
    }
  };

  installPlaybackCollectionQueue(ctx);
}
