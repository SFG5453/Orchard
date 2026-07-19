import { playlistArtworkDetection } from './playlistArtwork.js';
import {
  artworkAlbumIdUrl,
  artworkSearchUrl,
  normalizeArtworkProviderResponse
} from './artworkProviders.js';

export function installArtworkService(ctx) {
  ctx.normalizedArtworkText = function normalizedArtworkText(value = '') {
    return String(value || '').trim().replace(/\s+/g, ' ').toLowerCase();
  };

  ctx.comparableArtworkText = function comparableArtworkText(value = '') {
    return ctx.normalizedArtworkText(value)
      .replace(/&/g, ' and ')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  };

  ctx.artworkTextMatches = function artworkTextMatches(left = '', right = '') {
    const normalizedLeft = ctx.comparableArtworkText(left);
    const normalizedRight = ctx.comparableArtworkText(right);
    return Boolean(normalizedLeft && normalizedRight && normalizedLeft === normalizedRight);
  };

  ctx.artworkArtistMatches = function artworkArtistMatches(left = '', right = '') {
    const normalizedLeft = ctx.comparableArtworkText(left);
    const normalizedRight = ctx.comparableArtworkText(right);
    if (!normalizedLeft || !normalizedRight) return true;
    return normalizedLeft === normalizedRight ||
      normalizedLeft.includes(normalizedRight) ||
      normalizedRight.includes(normalizedLeft);
  };

  ctx.enhancedArtworkMatches = function enhancedArtworkMatches(data, target, fallbackArtist = '') {
    if (!data || !target?.title) return false;
    if (!ctx.artworkTextMatches(data.name, target.title)) return false;
    return ctx.artworkArtistMatches(data.artist, ctx.artworkArtist(target, fallbackArtist));
  };

  ctx.artworkArtist = function artworkArtist(track, fallbackArtist = '') {
    return track?.artists?.join(', ') || track?.artist || fallbackArtist || ctx.activeArtist.value || '';
  };

  ctx.artworkLookupKey = function artworkLookupKey(track, fallbackArtist = '') {
    const title = ctx.normalizedArtworkText(track?.title);
    const artist = ctx.normalizedArtworkText(ctx.artworkArtist(track, fallbackArtist));
    return title && artist ? `${title}::${artist}` : '';
  };

  ctx.albumDetailArtworkLookupKey = function albumDetailArtworkLookupKey(detail) {
    if (detail?.kind !== 'album') return '';
    const title = ctx.normalizedArtworkText(detail.title);
    const artist = ctx.normalizedArtworkText(detail.artist || detail.subtitle || '');
    const id = ctx.normalizedArtworkText(detail.browseId || detail.futureAlbumId || '');
    return title && artist ? `album:${title}::${artist}::${id}` : '';
  };

  ctx.albumArtworkLookupTarget = function albumArtworkLookupTarget(detail) {
    if (detail?.kind !== 'album') return null;

    const firstTrack = detail.tracks?.find((track) => track?.title);
    return {
      ...firstTrack,
      title: detail.title || firstTrack?.title,
      album: detail.title || firstTrack?.album || '',
      artist: firstTrack?.artist || detail.artist || detail.subtitle || ''
    };
  };

  ctx.albumArtworkTrackTarget = function albumArtworkTrackTarget(track, detail) {
    return {
      ...track,
      title: track.title,
      artist: track.artist || detail.artist || detail.subtitle || '',
      artists: track.artists?.length ? track.artists : [track.artist || detail.artist || detail.subtitle].filter(Boolean)
    };
  };

  ctx.albumArtworkLookupTargets = function albumArtworkLookupTargets(detail) {
    const albumTarget = ctx.albumArtworkLookupTarget(detail);
    if (!albumTarget) return [];

    const seen = new Set([ctx.artworkLookupKey(albumTarget, detail?.artist || detail?.subtitle || '')]);
    const trackTargets = (detail.tracks || [])
      .filter((track) => track?.title && !ctx.artworkTextMatches(track.title, detail.title))
      .map((track) => ctx.albumArtworkTrackTarget(track, detail))
      .filter((track) => {
        const key = ctx.artworkLookupKey(track, detail?.artist || detail?.subtitle || '');
        if (!key || seen.has(key)) return false;
        seen.add(key);
        return true;
      });

    return [
      { target: albumTarget, kind: 'album' },
      ...trackTargets.map((target) => ({ target, kind: 'album-track' }))
    ];
  };

  ctx.itunesCollectionIsSingleOrEp = function itunesCollectionIsSingleOrEp(item = {}) {
    return /(?:^|[^\w])(single|ep)(?:[^\w]|$)/i.test(`${item.collectionName || ''} ${item.collectionCensoredName || ''}`);
  };

  ctx.itunesCollectionBelongsToAlbum = function itunesCollectionBelongsToAlbum(item, detail) {
    return ctx.artworkTextMatches(item.collectionName || item.collectionCensoredName || '', detail?.title || '');
  };

  ctx.itunesCollectionMatchesDetailAlbum = function itunesCollectionMatchesDetailAlbum(item, detail) {
    return ctx.itunesCollectionBelongsToAlbum(item, detail) &&
      !ctx.itunesCollectionIsSingleOrEp(item) &&
      Number(item.trackCount || 0) > 3;
  };

  ctx.itunesAlbumLookup = async function itunesAlbumLookup(albumId) {
    const id = String(albumId || '').trim();
    if (!id) return null;
    if (ctx.itunesAlbumLookupCache.has(id)) return ctx.itunesAlbumLookupCache.get(id);

    try {
      const album = await ctx.emitWithReply('music:itunes-album', { albumId: id });
      ctx.itunesAlbumLookupCache.set(id, album || null);
      return album || null;
    } catch {
      ctx.itunesAlbumLookupCache.set(id, null);
      return null;
    }
  };

  ctx.formatAlbumReleaseDate = function formatAlbumReleaseDate(value = '') {
    const date = new Date(value);
    if (!value || Number.isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat('en-US', {
      month: 'long',
      day: 'numeric',
      year: 'numeric',
      timeZone: 'UTC'
    }).format(date);
  };

  ctx.loadAlbumReleaseDate = async function loadAlbumReleaseDate(detail, artwork) {
    if (detail?.kind !== 'album' || detail.releaseDateText || !artwork?.albumId) return;
    const detailKey = ctx.albumDetailArtworkLookupKey(detail);
    const album = await ctx.itunesAlbumLookup(artwork.albumId);
    const releaseDate = album?.releaseDate || '';
    const releaseDateText = ctx.formatAlbumReleaseDate(releaseDate);
    if (!releaseDateText || !ctx.itunesCollectionMatchesDetailAlbum(album, detail)) return;
    if (ctx.albumDetailArtworkLookupKey(ctx.browseDetail.value) !== detailKey) return;

    ctx.browseDetail.value.releaseDate = releaseDate;
    ctx.browseDetail.value.releaseDateText = releaseDateText;
    ctx.browseDetail.value.year = releaseDate.match(/\b[12][0-9]{3}\b/)?.[0] || ctx.browseDetail.value.year;
  };

  ctx.enhancedArtworkBelongsToAlbum = async function enhancedArtworkBelongsToAlbum(artwork, detail) {
    const album = await ctx.itunesAlbumLookup(artwork?.albumId);
    return Boolean(album && ctx.itunesCollectionMatchesDetailAlbum(album, detail));
  };

  ctx.enhancedArtworkHasMotion = function enhancedArtworkHasMotion(data) {
    return Boolean(data?.videoUrl || data?.animated);
  };

  ctx.parseHlsAttributes = function parseHlsAttributes(line = '') {
    const attributes = {};
    const body = line.slice(line.indexOf(':') + 1);
    const matches = body.matchAll(/([A-Z0-9-]+)=("[^"]*"|[^,]*)/g);

    for (const match of matches) {
      attributes[match[1]] = match[2].replace(/^"|"$/g, '');
    }

    return attributes;
  };

  ctx.directMp4FromHlsUrl = function directMp4FromHlsUrl(value = '') {
    return value.replace(/(-?)\.m3u8(\?.*)?$/i, (_, existingDash, query = '') => `${existingDash || '-'}.mp4${query}`);
  };

  ctx.hlsUrlFromLine = function hlsUrlFromLine(line, baseUrl) {
    try {
      return new URL(line.trim(), baseUrl).toString();
    } catch {
      return '';
    }
  };

  ctx.preferredAnimatedArtworkVideo = function preferredAnimatedArtworkVideo(manifestText, manifestUrl) {
    const variants = [];
    let pendingVariant = null;

    for (const rawLine of manifestText.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line) continue;

      if (line.startsWith('#EXT-X-STREAM-INF:')) {
        pendingVariant = ctx.parseHlsAttributes(line);
        continue;
      }

      if (line.startsWith('#')) continue;

      if (pendingVariant) {
        const resolution = pendingVariant.RESOLUTION?.match(/^(\d+)x(\d+)$/);
        variants.push({
          url: ctx.hlsUrlFromLine(line, manifestUrl),
          codecs: pendingVariant.CODECS || '',
          width: resolution ? Number(resolution[1]) : 0,
          height: resolution ? Number(resolution[2]) : 0,
          bandwidth: Number(pendingVariant.BANDWIDTH || pendingVariant['AVERAGE-BANDWIDTH'] || 0)
        });
        pendingVariant = null;
      }
    }

    const avcVariants = variants
      .filter((variant) => variant.url && /(^|,)avc1\./i.test(variant.codecs))
      .sort((a, b) => {
        const aFits = a.width <= 768 && a.height <= 768;
        const bFits = b.width <= 768 && b.height <= 768;
        if (aFits !== bFits) return aFits ? -1 : 1;
        if (aFits && bFits && a.width !== b.width) return b.width - a.width;
        if (!aFits && !bFits && a.width !== b.width) return a.width - b.width;
        return a.bandwidth - b.bandwidth;
      });

    return avcVariants[0] ? ctx.directMp4FromHlsUrl(avcVariants[0].url) : '';
  };

  ctx.resolveAnimatedArtworkVideo = async function resolveAnimatedArtworkVideo(data) {
    if (data?.animated) {
      try {
        const response = await fetch(data.animated, { cache: 'force-cache' });
        if (response.ok) {
          const videoUrl = ctx.preferredAnimatedArtworkVideo(await response.text(), data.animated);
          if (videoUrl) return videoUrl;
        }
      } catch {
        // Fall back to the API-provided direct video below.
      }
    }

    return data?.videoUrl || '';
  };

  ctx.normalizeEnhancedArtwork = async function normalizeEnhancedArtwork(data) {
    if (!data?.static && !data?.videoUrl && !data?.animated) return null;

    return {
      name: data.name || '',
      artist: data.artist || '',
      albumId: data.albumId || '',
      static: data.static || '',
      animated: data.animated || '',
      videoUrl: await ctx.resolveAnimatedArtworkVideo(data)
    };
  };

  ctx.normalizeMatchingEnhancedArtwork = async function normalizeMatchingEnhancedArtwork(data, target, fallbackArtist = '') {
    if (!ctx.enhancedArtworkMatches(data, target, fallbackArtist)) return null;
    return ctx.normalizeEnhancedArtwork(data);
  };

  ctx.fetchArtworkFromProviders = async function fetchArtworkFromProviders(buildUrl, normalize) {
    for (const provider of ctx.artworkApiProviders) {
      const url = buildUrl(provider);
      if (!url) continue;

      try {
        const response = await fetch(url, { cache: 'force-cache' });
        if (!response.ok) continue;
        const artwork = await normalize(provider, await response.json());
        if (artwork) return artwork;
      } catch (error) {
        console.warn(`Artwork provider ${provider.id} failed`, error);
      }
    }

    return null;
  };

  ctx.fetchMatchingEnhancedArtwork = async function fetchMatchingEnhancedArtwork(target, fallbackArtist = '') {
    const artist = ctx.artworkArtist(target, fallbackArtist);
    return ctx.fetchArtworkFromProviders(
      (provider) => artworkSearchUrl(provider, target, artist),
      async (provider, data) => ctx.normalizeMatchingEnhancedArtwork(
        normalizeArtworkProviderResponse(provider, data, target, artist),
        target,
        fallbackArtist
      )
    );
  };

  ctx.fetchAlbumIdEnhancedArtwork = async function fetchAlbumIdEnhancedArtwork(albumId) {
    const id = String(albumId || '').trim();
    if (!id) return null;

    return ctx.fetchArtworkFromProviders(
      (provider) => artworkAlbumIdUrl(provider, id),
      async (provider, data) => ctx.normalizeEnhancedArtwork(
        normalizeArtworkProviderResponse(provider, data, null, '', id)
      )
    );
  };

  ctx.playlistArtworkCollageLookupKey = function playlistArtworkCollageLookupKey(detail) {
    const detection = playlistArtworkDetection(detail);
    if (!detection.canUseGeneratedCover) return '';

    return detection.seedTracks
      .map((track) => ctx.artworkLookupKey(track, detail?.artist || detail?.subtitle || '') || track.id)
      .join('|');
  };

  ctx.fetchPlaylistArtworkTile = async function fetchPlaylistArtworkTile(track, fallbackArtist = '') {
    const key = ctx.artworkLookupKey(track, fallbackArtist);
    let artwork = null;

    try {
      if (key && ctx.artworkCache.has(key)) {
        artwork = ctx.artworkCache.get(key);
      } else if (key) {
        artwork = await ctx.fetchMatchingEnhancedArtwork(track, fallbackArtist);
        ctx.artworkCache.set(key, artwork);
      }
    } catch {
      if (key) ctx.artworkCache.set(key, null);
    }

    const videoUrl = artwork?.videoUrl || '';

    return {
      id: track.id,
      title: track.title || '',
      image: track.thumbnail || '',
      poster: videoUrl ? artwork?.static || track.thumbnail || '' : track.thumbnail || '',
      videoUrl
    };
  };

  ctx.loadPlaylistArtworkCollage = async function loadPlaylistArtworkCollage(detail) {
    const detection = playlistArtworkDetection(detail);
    const key = ctx.playlistArtworkCollageLookupKey(detail);
    ctx.playlistArtworkCollageRequest += 1;
    const requestId = ctx.playlistArtworkCollageRequest;
    ctx.playlistArtworkCollage.value = [];

    if (!key) return;

    const tiles = await Promise.all(
      detection.seedTracks.map((track) =>
        ctx.fetchPlaylistArtworkTile(track, detail?.artist || detail?.subtitle || '')
      )
    );

    if (
      requestId === ctx.playlistArtworkCollageRequest &&
      ctx.playlistArtworkCollageLookupKey(ctx.browseDetail.value) === key
    ) {
      ctx.playlistArtworkCollage.value = tiles;
    }
  };

  ctx.normalizeAlbumEnhancedArtwork = async function normalizeAlbumEnhancedArtwork(data, target, detail, fallbackArtist = '') {
    if (ctx.enhancedArtworkMatches(data, target, fallbackArtist)) {
      return ctx.normalizeEnhancedArtwork(data);
    }

    if (
      !ctx.enhancedArtworkHasMotion(data) ||
      !ctx.artworkArtistMatches(data?.artist, ctx.artworkArtist(target, fallbackArtist)) ||
      !(await ctx.enhancedArtworkBelongsToAlbum(data, detail))
    ) {
      return null;
    }

    return ctx.normalizeEnhancedArtwork(data);
  };

  ctx.fetchAlbumEnhancedArtwork = async function fetchAlbumEnhancedArtwork(target, detail, fallbackArtist = '') {
    const artist = ctx.artworkArtist(target, fallbackArtist);
    return ctx.fetchArtworkFromProviders(
      (provider) => artworkSearchUrl(provider, target, artist),
      async (provider, data) => ctx.normalizeAlbumEnhancedArtwork(
        normalizeArtworkProviderResponse(provider, data, target, artist),
        target,
        detail,
        fallbackArtist
      )
    );
  };

  ctx.loadEnhancedArtwork = async function loadEnhancedArtwork(track) {
    const key = ctx.artworkLookupKey(track);
    ctx.artworkLookupRequest += 1;
    const requestId = ctx.artworkLookupRequest;
    ctx.enhancedArtwork.value = null;
    ctx.nowArtworkVideoFailed.value = false;

    if (!key) return;

    if (ctx.artworkCache.has(key)) {
      ctx.enhancedArtwork.value = ctx.artworkCache.get(key);
      return;
    }

    try {
      const artwork = await ctx.fetchMatchingEnhancedArtwork(track);
      ctx.artworkCache.set(key, artwork);

      if (requestId === ctx.artworkLookupRequest && ctx.artworkLookupKey(ctx.activeTrack.value) === key) {
        ctx.enhancedArtwork.value = artwork;
      }
    } catch {
      ctx.artworkCache.set(key, null);
    }
  };

  ctx.loadDetailEnhancedArtwork = async function loadDetailEnhancedArtwork(detail) {
    const key = ctx.albumDetailArtworkLookupKey(detail);
    ctx.detailArtworkLookupRequest += 1;
    const requestId = ctx.detailArtworkLookupRequest;
    ctx.detailEnhancedArtwork.value = null;
    ctx.detailArtworkVideoFailed.value = false;

    if (!key) return;

    if (ctx.artworkCache.has(key)) {
      const artwork = ctx.artworkCache.get(key);
      ctx.detailEnhancedArtwork.value = artwork;
      void ctx.loadAlbumReleaseDate(detail, artwork);
      return;
    }

    try {
      let albumStaticArtwork = null;
      let artwork = null;

      for (const candidate of ctx.albumArtworkLookupTargets(detail)) {
        const candidateArtwork = await ctx.fetchAlbumEnhancedArtwork(
          candidate.target,
          detail,
          detail?.artist || detail?.subtitle || ''
        );
        if (!candidateArtwork) continue;

        const hasAnimatedArtwork = Boolean(candidateArtwork.videoUrl || candidateArtwork.animated);
        if (candidate.kind === 'album-track' && (!hasAnimatedArtwork || !(await ctx.enhancedArtworkBelongsToAlbum(candidateArtwork, detail)))) {
          continue;
        }

        if (hasAnimatedArtwork) {
          artwork = candidateArtwork;
          break;
        }

        if (candidate.kind === 'album' && !albumStaticArtwork) {
          albumStaticArtwork = candidateArtwork;
        }
      }

      artwork = artwork || albumStaticArtwork;
      ctx.artworkCache.set(key, artwork);

      const currentKey = ctx.albumDetailArtworkLookupKey(ctx.browseDetail.value);

      if (requestId === ctx.detailArtworkLookupRequest && currentKey === key) {
        ctx.detailEnhancedArtwork.value = artwork;
        void ctx.loadAlbumReleaseDate(detail, artwork);
      }
    } catch {
      ctx.artworkCache.set(key, null);
    }
  };
}
