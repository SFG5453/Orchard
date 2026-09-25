/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createAlbumVideoAmbientRenderer } from '../../app/appearance/albumVideoAmbientRenderer.js';
import { fetchCustomArtistConfig, cachedCustomArtistIndex } from '../../app/appearance/customArtistPacks.js';
import { albumWallBannerTiles } from '../../custom-artists/shared/albumWallBanner.js';
import { setupConfiguredArtist } from '../../custom-artists/shared/configuredArtist.js';
import { createPlaylistAnalysisRunner } from '../../app/playback/playlistAnalysisRunner.js';

const FALLBACK_ALBUM_EDGE = Object.freeze([39, 42, 40]);
const FALLBACK_ALBUM_SAMPLE = Object.freeze({
  zones: Object.freeze(Object.fromEntries([
    'left', 'right', 'top', 'bottom',
    'topLeft', 'topRight', 'bottomRight', 'bottomLeft'
  ].map((name) => [name, Object.freeze({ seam: FALLBACK_ALBUM_EDGE })]))),
  palette: Object.freeze({
    seam: FALLBACK_ALBUM_EDGE,
    accent: Object.freeze([154, 169, 158]),
    accentSoft: Object.freeze([186, 197, 189]),
    deep: Object.freeze([19, 22, 20]),
    ink: Object.freeze([8, 10, 9]),
    surface: Object.freeze([20, 24, 21]),
    surfaceRaised: Object.freeze([35, 42, 37]),
    onAccent: Object.freeze([8, 10, 9])
  })
});
const ALBUM_VIDEO_SAMPLE_INTERVAL_MS = 1200;
const ALBUM_VIDEO_SAMPLE_LONG_EDGE = 192;
const ALBUM_SHELL_ACCENT_ATTRIBUTE = 'data-album-artwork-accent';
const ALBUM_SHELL_ACCENT_PROPERTIES = Object.freeze([
  '--album-rail-left-rgb',
  '--album-rail-right-rgb',
  '--album-rail-seam-rgb',
  '--album-rail-accent-rgb',
  '--album-rail-accent-soft-rgb',
  '--album-rail-deep-rgb',
  '--album-rail-ink-rgb',
  '--album-rail-surface-rgb'
]);

function rgbCustomProperty(rgb, fallback = FALLBACK_ALBUM_EDGE) {
  const channels = Array.isArray(rgb) && rgb.length >= 3 ? rgb : fallback;
  return channels.slice(0, 3).map((value) => Math.max(0, Math.min(255, Math.round(Number(value) || 0)))).join(', ');
}

function blendRgb(from, to, amount) {
  const source = Array.isArray(from) && from.length >= 3 ? from : to;
  return to.slice(0, 3).map((value, index) =>
    Math.round(source[index] + ((value - source[index]) * amount))
  );
}

function blendArtworkSamples(from, to, amount) {
  if (!from?.zones || !from?.palette || !to?.zones || !to?.palette) return to;
  const zones = Object.fromEntries(Object.entries(to.zones).map(([name, target]) => {
    const source = from.zones[name] || target;
    return [name, {
      average: blendRgb(source.average, target.average, amount),
      median: blendRgb(source.median, target.median, amount),
      seam: blendRgb(source.seam, target.seam, amount)
    }];
  }));
  const palette = Object.fromEntries(Object.entries(to.palette).map(([name, target]) => [
    name,
    blendRgb(from.palette[name], target, amount)
  ]));
  return { ...to, zones, palette };
}

export function setupBrowseDetailView(props) {
  const detailPageRef = ref(null);
  const virtualPlaylistRef = ref(null);
  const analysisRunner = createPlaylistAnalysisRunner(props.app);
  const bestMixPromptOpen = ref(false);
  const bestMixPromptDetail = ref(null);
  const albumVideoAmbientHostRef = ref(null);
  const albumArtworkSample = ref(FALLBACK_ALBUM_SAMPLE);
  const albumStaticArtworkSample = ref(FALLBACK_ALBUM_SAMPLE);
  const albumPaletteSource = ref('static');
  let albumStaticPaletteRequestId = 0;
  let albumVideoSourceVersion = 0;
  let albumVideoFrameSampling = false;
  let albumVideoSampleCanvas = null;
  let blockedAlbumVideoSampleUrl = '';
  let lastAlbumVideoSampleAt = 0;
  let albumVideoAmbientRenderer = null;
  let albumVideoAmbientTarget = null;
  let albumVideoAmbientRequestId = 0;
  let albumShellAccentElement = null;

  const albumHeroArtworkImage = computed(() => {
    if (props.app.browseDetail.value?.kind !== 'album') return '';
    const image = props.app.detailArtworkImage?.value || props.app.browseDetail.value?.thumbnail || '';
    return props.app.highResolutionArtworkImage(image, 1200);
  });

  const albumPageStyle = computed(() => {
    if (props.app.browseDetail.value?.kind !== 'album') return {};
    const sample = albumArtworkSample.value || FALLBACK_ALBUM_SAMPLE;
    const zone = (name) => sample.zones?.[name]?.seam || sample.palette?.seam || FALLBACK_ALBUM_EDGE;
    const palette = sample.palette || FALLBACK_ALBUM_SAMPLE.palette;
    return {
      '--album-artwork-image': props.app.cssImageUrl(albumHeroArtworkImage.value),
      '--album-edge-left-rgb': rgbCustomProperty(zone('left')),
      '--album-edge-right-rgb': rgbCustomProperty(zone('right')),
      '--album-edge-top-rgb': rgbCustomProperty(zone('top')),
      '--album-edge-bottom-rgb': rgbCustomProperty(zone('bottom')),
      '--album-corner-top-left-rgb': rgbCustomProperty(zone('topLeft')),
      '--album-corner-top-right-rgb': rgbCustomProperty(zone('topRight')),
      '--album-corner-bottom-right-rgb': rgbCustomProperty(zone('bottomRight')),
      '--album-corner-bottom-left-rgb': rgbCustomProperty(zone('bottomLeft')),
      '--album-seam-rgb': rgbCustomProperty(palette.seam),
      '--album-accent-rgb': rgbCustomProperty(palette.accent),
      '--album-accent-soft-rgb': rgbCustomProperty(palette.accentSoft),
      '--album-deep-rgb': rgbCustomProperty(palette.deep),
      '--album-ink-rgb': rgbCustomProperty(palette.ink),
      '--album-surface-rgb': rgbCustomProperty(palette.surface),
      '--album-surface-raised-rgb': rgbCustomProperty(palette.surfaceRaised),
      '--album-on-accent-rgb': rgbCustomProperty(palette.onAccent, [8, 10, 9])
    };
  });

  const albumShellAccentEnabled = computed(() =>
    props.app.browseDetail.value?.kind === 'album' &&
    props.app.accentColorSource?.value === 'artwork'
  );

  const albumShellAccentStyle = computed(() => {
    const sample = albumStaticArtworkSample.value || FALLBACK_ALBUM_SAMPLE;
    const zone = (name) => sample.zones?.[name]?.seam || sample.palette?.seam || FALLBACK_ALBUM_EDGE;
    const palette = sample.palette || FALLBACK_ALBUM_SAMPLE.palette;
    return {
      '--album-rail-left-rgb': rgbCustomProperty(zone('left')),
      '--album-rail-right-rgb': rgbCustomProperty(zone('right')),
      '--album-rail-seam-rgb': rgbCustomProperty(palette.seam),
      '--album-rail-accent-rgb': rgbCustomProperty(palette.accent),
      '--album-rail-accent-soft-rgb': rgbCustomProperty(palette.accentSoft),
      '--album-rail-deep-rgb': rgbCustomProperty(palette.deep),
      '--album-rail-ink-rgb': rgbCustomProperty(palette.ink),
      '--album-rail-surface-rgb': rgbCustomProperty(palette.surface)
    };
  });

  function clearAlbumShellAccent() {
    if (!albumShellAccentElement) return;
    albumShellAccentElement.removeAttribute(ALBUM_SHELL_ACCENT_ATTRIBUTE);
    ALBUM_SHELL_ACCENT_PROPERTIES.forEach((property) => {
      albumShellAccentElement.style.removeProperty(property);
    });
    albumShellAccentElement = null;
  }

  function syncAlbumShellAccent() {
    const shell = detailPageRef.value?.closest('.app-shell') || null;
    if (!albumShellAccentEnabled.value || !shell) {
      clearAlbumShellAccent();
      return;
    }
    if (albumShellAccentElement && albumShellAccentElement !== shell) clearAlbumShellAccent();
    albumShellAccentElement = shell;
    Object.entries(albumShellAccentStyle.value).forEach(([property, value]) => {
      shell.style.setProperty(property, value);
    });
    shell.setAttribute(ALBUM_SHELL_ACCENT_ATTRIBUTE, '');
  }

  watch([albumShellAccentEnabled, albumShellAccentStyle], () => {
    void nextTick(syncAlbumShellAccent);
  }, { immediate: true });

  watch(albumHeroArtworkImage, async (artworkUrl) => {
    const requestId = ++albumStaticPaletteRequestId;
    albumStaticArtworkSample.value = FALLBACK_ALBUM_SAMPLE;
    if (albumPaletteSource.value !== 'video') albumArtworkSample.value = FALLBACK_ALBUM_SAMPLE;
    if (!artworkUrl || typeof window === 'undefined' || typeof window.orchardArtwork?.sampleColors !== 'function') return;

    try {
      const sample = await window.orchardArtwork.sampleColors(artworkUrl);
      if (requestId === albumStaticPaletteRequestId && sample?.zones && sample?.palette) {
        albumStaticArtworkSample.value = sample;
        if (albumPaletteSource.value !== 'video') albumArtworkSample.value = sample;
      }
    } catch (error) {
      console.warn('Album artwork edge sampling failed:', error?.message || error);
    }
  }, { immediate: true });

  watch(() => props.app.detailArtworkVideo?.value || '', () => {
    clearAlbumVideoAmbient();
    albumVideoSourceVersion += 1;
    albumVideoFrameSampling = false;
    blockedAlbumVideoSampleUrl = '';
    lastAlbumVideoSampleAt = 0;
    albumPaletteSource.value = 'static';
    albumArtworkSample.value = albumStaticArtworkSample.value;
  });

  function clearAlbumVideoAmbient() {
    albumVideoAmbientRequestId += 1;
    albumVideoAmbientRenderer?.destroy();
    albumVideoAmbientRenderer = null;
    albumVideoAmbientTarget = null;
  }

  async function startAlbumVideoAmbient(video) {
    if (
      props.app.browseDetail.value?.kind !== 'album' ||
      !video ||
      video.videoWidth < 1 ||
      video.videoHeight < 1 ||
      !albumVideoAmbientHostRef.value
    ) return;
    if (albumVideoAmbientTarget === video) return;

    clearAlbumVideoAmbient();
    albumVideoAmbientTarget = video;
    const host = albumVideoAmbientHostRef.value;
    const requestId = albumVideoAmbientRequestId;
    try {
      const renderer = await createAlbumVideoAmbientRenderer({ host, video });
      if (
        requestId !== albumVideoAmbientRequestId ||
        albumVideoAmbientTarget !== video ||
        albumVideoAmbientHostRef.value !== host
      ) {
        renderer.destroy();
        return;
      }
      albumVideoAmbientRenderer = renderer;
    } catch (error) {
      if (requestId === albumVideoAmbientRequestId) albumVideoAmbientTarget = null;
      console.warn('Album artwork live ambient rendering failed; keeping the sampled gradient:', error?.message || error);
    }
  }

  async function sampleAlbumArtworkVideoFrame(event, force = false) {
    if (props.app.browseDetail.value?.kind !== 'album') return;
    const video = event?.target || props.app.detailArtworkVideoRef?.value;
    const videoUrl = props.app.detailArtworkVideo?.value || '';
    if (
      !video ||
      !videoUrl ||
      video !== props.app.detailArtworkVideoRef?.value ||
      video.readyState < 2 ||
      video.videoWidth < 1 ||
      video.videoHeight < 1 ||
      albumVideoFrameSampling ||
      blockedAlbumVideoSampleUrl === videoUrl ||
      typeof window.orchardArtwork?.sampleFrameColors !== 'function'
    ) return;

    const now = performance.now();
    if (!force && now - lastAlbumVideoSampleAt < ALBUM_VIDEO_SAMPLE_INTERVAL_MS) return;
    lastAlbumVideoSampleAt = now;

    const scale = Math.min(1, ALBUM_VIDEO_SAMPLE_LONG_EDGE / Math.max(video.videoWidth, video.videoHeight));
    const width = Math.max(1, Math.round(video.videoWidth * scale));
    const height = Math.max(1, Math.round(video.videoHeight * scale));
    albumVideoSampleCanvas ||= document.createElement('canvas');
    albumVideoSampleCanvas.width = width;
    albumVideoSampleCanvas.height = height;

    let pixels;
    try {
      const context = albumVideoSampleCanvas.getContext('2d', { alpha: false, willReadFrequently: true });
      if (!context) return;
      context.drawImage(video, 0, 0, width, height);
      pixels = context.getImageData(0, 0, width, height).data;
    } catch (error) {
      blockedAlbumVideoSampleUrl = videoUrl;
      console.warn('Album artwork video frame could not be read; keeping the static artwork palette:', error?.message || error);
      return;
    }

    const sourceVersion = albumVideoSourceVersion;
    albumVideoFrameSampling = true;
    try {
      const sample = await window.orchardArtwork.sampleFrameColors({ width, height, data: pixels });
      if (
        sourceVersion === albumVideoSourceVersion &&
        videoUrl === (props.app.detailArtworkVideo?.value || '') &&
        sample?.zones &&
        sample?.palette
      ) {
        albumArtworkSample.value = blendArtworkSamples(
          albumArtworkSample.value,
          sample,
          albumPaletteSource.value === 'video' ? 0.34 : 0.64
        );
        albumPaletteSource.value = 'video';
      }
    } catch (error) {
      console.warn('Album artwork video palette sampling failed:', error?.message || error);
    } finally {
      if (sourceVersion === albumVideoSourceVersion) albumVideoFrameSampling = false;
    }
  }

  function onDetailArtworkVideoCanPlay(event) {
    props.app.playDetailArtworkVideo();
    void startAlbumVideoAmbient(event?.target);
    void sampleAlbumArtworkVideoFrame(event, true);
  }

  const albumHeroTypeLabel = computed(() => {
    const detail = props.app.browseDetail.value;
    if (detail?.kind !== 'album') return '';
    return props.app.albumTypeLabel(detail).split(/\s+-\s+/)[0].trim().toUpperCase();
  });

  const albumHeroYearLabel = computed(() => {
    const detail = props.app.browseDetail.value;
    if (detail?.kind !== 'album') return '';
    return String(detail.year || detail.releaseDateText || '').match(/\b[12][0-9]{3}\b/)?.[0] || '';
  });

  const bestMixUndownloadedTracks = computed(() => {
    const detail = bestMixPromptDetail.value;
    return (detail?.tracks || [])
      .map((track) => props.app.trackWithCollectionContext(track, detail))
      .filter((track) => props.app.isPlayableTrack(track) && !props.app.isTrackDownloaded(track));
  });

  const bestMixEstimatedDownloadMb = computed(() => {
    const seconds = bestMixUndownloadedTracks.value.reduce((total, track) =>
      total + (Number(track.durationSeconds) > 0 ? Number(track.durationSeconds) : 210), 0);
    return Math.max(1, Math.round(seconds * 20 / 1024));
  });

  async function runBestMixAnalysis(detail) {
    bestMixPromptOpen.value = false;
    if (!detail?.tracks?.length) return;
    await props.app.downloadTracks(detail.tracks, detail);
    await analysisRunner.analyzePlaylist(
      detail.tracks.map((track) => props.app.trackWithCollectionContext(track, detail))
    );
  }

  async function analyzeCurrentCollection(detail) {
    if (!detail?.tracks?.length || analysisRunner.isAnalyzing.value) return;
    const browseId = detail.browseId;
    if (browseId) props.app.downloadPreparingCollectionId.value = browseId;
    try {
      bestMixPromptDetail.value = await props.app.prepareDownloadCollection(detail);
    } finally {
      if (props.app.downloadPreparingCollectionId.value === browseId) {
        props.app.downloadPreparingCollectionId.value = '';
      }
    }

    if (bestMixUndownloadedTracks.value.length) {
      bestMixPromptOpen.value = true;
      return;
    }
    await runBestMixAnalysis(bestMixPromptDetail.value);
  }

  function scrollToCollectionTrack(index) {
    if (props.app.browseDetail.value?.kind === 'playlist') {
      virtualPlaylistRef.value?.scrollToIndex(index);
      return;
    }

    void nextTick(() => {
      detailPageRef.value
        ?.querySelector(`[data-collection-track-index="${index}"]`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }

  onMounted(() => {
    props.app.collectionQuickSearchScrollHandler = scrollToCollectionTrack;
    syncAlbumShellAccent();
  });

  const descriptionDialogOpen = ref(false);
  const descriptionDialogText = computed(() => {
    const detail = props.app.browseDetail.value;
    if (!detail) return '';
    return detail.description || (detail.kind === 'artist' ? detail.subtitle : '') || '';
  });
  const canOpenDescription = computed(() =>
    ['album', 'artist'].includes(props.app.browseDetail.value?.kind) &&
    Boolean(descriptionDialogText.value)
  );
  const descriptionDialogTitle = computed(() => {
    const detail = props.app.browseDetail.value;
    if (!detail) return 'Description';
    return detail.kind === 'artist' ? `About ${detail.title}` : `About ${detail.title}`;
  });
  const descriptionActionLabel = computed(() => {
    const kind = props.app.browseDetail.value?.kind;
    if (kind === 'artist') return 'Read bio';
    return kind === 'album' ? 'More' : 'Read full description';
  });

  const highlightWords = ref([]);

  function createHighlightedSegments(text) {
    if (!text) return [];
    const words = highlightWords.value;
    if (!words || !words.length) {
      return [{ text, highlight: false }];
    }

    const escapedWords = words.map((word) => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    const pattern = new RegExp(`(${escapedWords.join('|')})`, 'gi');

    return text
      .split(pattern)
      .map((part) => ({
        text: part,
        highlight: words.some((word) => word.toLowerCase() === part.toLowerCase())
      }))
      .filter((part) => part.text.length > 0);
  }

  const descriptionSegments = computed(() => {
    const detail = props.app.browseDetail.value;
    if (!detail) return [];
    return createHighlightedSegments(detail.description || '');
  });

  const subtitleSegments = computed(() => {
    const detail = props.app.browseDetail.value;
    if (!detail) return [];
    const text = detail.kind === 'artist' ? detail.subtitle : detail.artist || detail.subtitle;
    return createHighlightedSegments(text || '');
  });

  const descriptionDialogSegments = computed(() => createHighlightedSegments(descriptionDialogText.value));
  const artistGenreLabel = computed(() => {
    const detail = props.app.browseDetail.value;
    const genre = props.app.artistGenre?.value;
    if (!detail || detail.kind !== 'artist' || genre?.browseId !== detail.browseId || genre?.status !== 'ready') return '';
    return genre.genre || '';
  });

  function openDescriptionDialog() {
    if (canOpenDescription.value) descriptionDialogOpen.value = true;
  }

  let currentCustomArtistCleanup = null;
  const customArtistPagesEnabled = () => props.app.customArtistPagesEnabled?.value !== false;

  watch(
    () => props.app.browseDetail.value,
    (detail) => {
      if (detail?.offline) {
        props.app.resetArtistGenre?.(detail);
        return;
      }
      props.app.loadArtistGenre?.(detail);
      if (detail?.kind === 'artist') props.app.loadArtistSubscription?.(detail.browseId);
    },
    { immediate: true }
  );

  watch(() => [
    props.app.browseDetail.value?.browseId,
    props.app.browseDetail.value,
    customArtistPagesEnabled()
  ], async ([newId, detail, pagesEnabled]) => {
    if (currentCustomArtistCleanup) {
      currentCustomArtistCleanup();
      currentCustomArtistCleanup = null;
    }
    highlightWords.value = [];

    if (!pagesEnabled || !detail || detail.kind !== 'artist' || !newId || detail.offline) return;

    try {
      const config = await fetchCustomArtistConfig(newId);
      if (config && customArtistPagesEnabled() && props.app.browseDetail.value === detail) {
        highlightWords.value = config.features?.highlightWords || [];
        currentCustomArtistCleanup = setupConfiguredArtist(props.app, config);
      }
    } catch (error) {
      console.error(`Failed to load hosted custom artist ${newId}:`, error);
    }
  }, { immediate: true });

  onBeforeUnmount(() => {
    clearAlbumVideoAmbient();
    clearAlbumShellAccent();
    albumStaticPaletteRequestId += 1;
    albumVideoSourceVersion += 1;
    albumVideoSampleCanvas = null;
    if (props.app.collectionQuickSearchScrollHandler === scrollToCollectionTrack) {
      props.app.collectionQuickSearchScrollHandler = null;
    }
    if (currentCustomArtistCleanup) {
      currentCustomArtistCleanup();
      currentCustomArtistCleanup = null;
    }
  });

  const isCustomArtistPage = computed(() => {
    if (!customArtistPagesEnabled()) return false;
    const detail = props.app.browseDetail.value;
    if (!detail || detail.kind !== 'artist') return false;
    return Boolean(cachedCustomArtistIndex()?.artists?.[detail.browseId]);
  });

  const customArtistAlbumWallTiles = computed(() =>
    customArtistPagesEnabled()
      ? albumWallBannerTiles(props.app.browseDetail.value, props.app.mediaThumbnail)
      : []
  );

  return {
    ...props.app,
    app: props.app,
    canOpenDescription,
    descriptionActionLabel,
    descriptionDialogOpen,
    descriptionDialogText,
    descriptionDialogTitle,
    descriptionSegments,
    detailPageRef,
    albumHeroArtworkImage,
    albumPaletteSource,
    albumVideoAmbientHostRef,
    albumHeroTypeLabel,
    albumHeroYearLabel,
    albumPageStyle,
    onDetailArtworkVideoCanPlay,
    sampleAlbumArtworkVideoFrame,
    artistGenreLabel,
    artistSubscription: props.app.artistSubscription,
    descriptionDialogSegments,
    subtitleSegments,
    openDescriptionDialog,
    virtualPlaylistRef,
    isCustomArtistPage,
    customArtistAlbumWallTiles,
    bestMixEstimatedDownloadMb,
    bestMixPromptDetail,
    bestMixPromptOpen,
    bestMixUndownloadedTracks,
    isAnalyzingPlaylist: analysisRunner.isAnalyzing,
    playlistAnalysisProgress: analysisRunner.progress,
    playlistAnalysisStatus: analysisRunner.currentStatus,
    analyzeCurrentCollection,
    runBestMixAnalysis,
    cancelPlaylistAnalysis: analysisRunner.cancel
  };
}
