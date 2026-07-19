import './animatedArtworkPreview.css';
import { watch } from 'vue';

const LOOKUP_RETRY_LIMIT = 20;
const LOOKUP_RETRY_MS = 300;

function albumKey(app, item) {
  return app.itemBrowseId?.(item) || item?.browseId || `${item?.title || ''}:${item?.artist || ''}`;
}

function isAlbumItem(app, item) {
  return Boolean(item?.title && app.isAlbumItem?.(item));
}

function sectionForElement(app, sectionElement) {
  const headerText = sectionElement.querySelector('.section-header h2')?.textContent?.trim();
  if (!headerText) return null;
  return (app.browseDetailSections?.value || []).find((section) => section.title === headerText) || null;
}

async function detailForAlbum(app, item) {
  const browsePayload = item?.browsePayload ? { ...item.browsePayload } : {};
  const browseId = browsePayload.browseId || app.itemBrowseId?.(item);
  if (!browseId) return null;

  return app.emitWithReply('music:album', { ...browsePayload, browseId });
}

async function artworkFromAlbumDetail(app, detail, fallbackArtist) {
  if (!detail?.tracks?.length || !app.albumArtworkLookupTargets || !app.fetchAlbumEnhancedArtwork) {
    return null;
  }

  const detailArtist = detail.artist || detail.subtitle || fallbackArtist;
  for (const candidate of app.albumArtworkLookupTargets(detail)) {
    try {
      const artwork = await app.fetchAlbumEnhancedArtwork(candidate.target, detail, detailArtist);
      if (artwork?.videoUrl) return artwork;
    } catch {
      // Keep trying other tracks from the album before giving up.
    }
  }
  return null;
}

async function resolveAnimatedArtwork(app, item, fallbackArtist) {
  let artwork = null;

  if (app.fetchMatchingEnhancedArtwork) {
    try {
      artwork = await app.fetchMatchingEnhancedArtwork(item, fallbackArtist);
    } catch {
      artwork = null;
    }
  }

  if (!artwork?.videoUrl) {
    try {
      const detail = await detailForAlbum(app, item);
      artwork = await artworkFromAlbumDetail(app, detail, fallbackArtist);
    } catch {
      artwork = null;
    }
  }

  if (!artwork?.videoUrl) return null;

  return {
    poster: artwork.static || item.thumbnail || '',
    videoUrl: artwork.videoUrl
  };
}

function ensureVideo(card, artElement, artwork) {
  let video = card.querySelector(':scope .custom-artist-animated-artwork');
  if (!video) {
    video = document.createElement('video');
    video.className = 'custom-artist-animated-artwork';
    video.muted = true;
    video.loop = true;
    video.playsInline = true;
    video.preload = 'metadata';
    video.setAttribute('aria-hidden', 'true');
    artElement.classList.add('custom-artist-animated-artwork-host');
    artElement.append(video);
  }

  if (video.src !== artwork.videoUrl) {
    video.poster = artwork.poster;
    video.src = artwork.videoUrl;
  }

  return video;
}

function stopCardVideo(card) {
  const video = card.querySelector(':scope .custom-artist-animated-artwork');
  if (!video) return;
  video.classList.remove('is-visible');
  video.pause();
  try {
    video.currentTime = 0;
  } catch {
    // Some browsers reject seeking before enough metadata is loaded.
  }
}

export function attachAnimatedArtworkPreviews(app, { artistId, artistName }) {
  const listeners = [];
  const cache = new Map();
  let retryTimer = null;
  let attempts = 0;
  let activeCard = null;
  let disposed = false;

  async function start(card, item) {
    const artElement = card.querySelector(':scope .media-card__art, :scope .media-card__art--empty');
    const key = albumKey(app, item);
    if (!artElement || !key) return;

    activeCard = card;
    card.classList.add('media-card--animated-artwork-loading');

    try {
      if (!cache.has(key)) {
        cache.set(key, await resolveAnimatedArtwork(app, item, artistName));
      }

      const artwork = cache.get(key);
      if (!artwork?.videoUrl || activeCard !== card) return;

      const video = ensureVideo(card, artElement, artwork);
      const didPlay = await video.play().then(() => true).catch(() => false);
      if (!didPlay) return;
      if (activeCard !== card) {
        stopCardVideo(card);
        return;
      }

      card.classList.add('media-card--animated-artwork');
      video.classList.add('is-visible');
    } catch {
      if (key) cache.set(key, null);
    } finally {
      card.classList.remove('media-card--animated-artwork-loading');
    }
  }

  function stop(card) {
    if (activeCard === card) activeCard = null;
    stopCardVideo(card);
  }

  function detach(removeVideos = false) {
    activeCard = null;
    for (const { card, onEnter, onLeave, onFocus, onBlur } of listeners) {
      card.removeEventListener('mouseenter', onEnter);
      card.removeEventListener('mouseleave', onLeave);
      card.removeEventListener('focus', onFocus);
      card.removeEventListener('blur', onBlur);
      stopCardVideo(card);
      if (removeVideos) card.querySelector(':scope .custom-artist-animated-artwork')?.remove();
    }
    listeners.length = 0;
  }

  function attach() {
    if (disposed) return;
    clearTimeout(retryTimer);
    detach();

    const page = document.querySelector(`.detail-page--artist[data-artist-id="${artistId}"]`);
    if (!page) {
      if (attempts++ < LOOKUP_RETRY_LIMIT) retryTimer = setTimeout(attach, LOOKUP_RETRY_MS);
      return;
    }

    const sections = page.querySelectorAll('.shelf-section');
    for (const sectionElement of sections) {
      const section = sectionForElement(app, sectionElement);
      const items = section?.items || [];
      if (!items.length) continue;

      const cards = sectionElement.querySelectorAll('.media-rail > .media-card');
      cards.forEach((card, index) => {
        const item = items[index];
        if (!isAlbumItem(app, item)) return;

        const onEnter = () => start(card, item);
        const onLeave = () => stop(card);
        const onFocus = () => start(card, item);
        const onBlur = () => stop(card);

        card.addEventListener('mouseenter', onEnter);
        card.addEventListener('mouseleave', onLeave);
        card.addEventListener('focus', onFocus);
        card.addEventListener('blur', onBlur);
        listeners.push({ card, onEnter, onLeave, onFocus, onBlur });
      });
    }

    if (!listeners.length && attempts++ < LOOKUP_RETRY_LIMIT) {
      retryTimer = setTimeout(attach, LOOKUP_RETRY_MS);
    }
  }

  const sectionWatcher = watch(app.browseDetailSections, () => {
    attempts = 0;
    clearTimeout(retryTimer);
    retryTimer = setTimeout(attach, 0);
  }, { flush: 'post' });

  attach();

  return () => {
    disposed = true;
    sectionWatcher();
    clearTimeout(retryTimer);
    activeCard = null;
    detach(true);
  };
}
