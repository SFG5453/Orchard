import { attachAlbumHoverPreviews } from './albumHoverPreview.js';
import { attachAnimatedArtworkPreviews } from './animatedArtworkPreview.js';
import { attachKeyEasterEgg } from './keyEasterEgg.js';

const PAGE_RETRY_LIMIT = 20;
const PAGE_RETRY_MS = 300;

function assetFor(config, key) {
  return config.assets?.[key] || '';
}

function setIfChanged(target, key, value) {
  const original = target[key];
  target[key] = value;
  return () => {
    if (target[key] === value) target[key] = original;
  };
}

function bannerWithResolvedAssets(config) {
  const banner = config.banner;
  if (!banner || typeof banner !== 'object') return null;

  const tiles = Array.isArray(banner.tiles)
    ? banner.tiles.map((tile) => ({
      ...tile,
      image: tile.image || config.assets?.[tile.assetKey] || config.assets?.[tile.asset] || ''
    }))
    : [];

  return {
    ...banner,
    tiles
  };
}

function applyThemeVariables(config) {
  const variables = config.theme?.cssVariables || {};
  const previous = new Map();
  let retryTimer = null;
  let attempts = 0;
  let page = null;

  function apply() {
    page = document.querySelector(`.detail-page--artist[data-artist-id="${config.artistId}"]`);
    if (!page) {
      if (attempts++ < PAGE_RETRY_LIMIT) retryTimer = setTimeout(apply, PAGE_RETRY_MS);
      return;
    }

    for (const [name, value] of Object.entries(variables)) {
      previous.set(name, page.style.getPropertyValue(name));
      page.style.setProperty(name, value);
    }
  }

  apply();

  return () => {
    clearTimeout(retryTimer);
    if (!page) return;
    for (const [name, value] of previous.entries()) {
      if (value) {
        page.style.setProperty(name, value);
      } else {
        page.style.removeProperty(name);
      }
    }
  };
}

export function setupConfiguredArtist(app, config, assets) {
  const detail = app.browseDetail.value;
  const cleanupStyles = attachStyles(config);
  const cleanupDetail = [
    setIfChanged(detail, 'title', config.displayName || config.artistName),
    setIfChanged(detail, 'thumbnail', assetFor(config, 'thumbnail')),
    setIfChanged(detail, 'customLayout', config.layout),
    setIfChanged(detail, 'customBanner', bannerWithResolvedAssets(config)),
    setIfChanged(detail, 'customProfileArtwork', assetFor(config, 'profile')),
    setIfChanged(detail, 'customHeroArtwork', assetFor(config, 'hero')),
    setIfChanged(detail, 'customImmersiveArtwork', assetFor(config, 'immersive')),
    setIfChanged(detail, 'highlightWords', config.features?.highlightWords || []),
    setIfChanged(detail, 'hasEasterEgg', !!config.features?.keyEasterEgg),
    setIfChanged(detail, 'easterEggKeys', config.features?.keyEasterEgg?.keys || null)
  ];

  const cleanupTheme = applyThemeVariables(config);
  const cleanupPreviews = config.features?.hoverPreviews
    ? attachAlbumHoverPreviews(app, {
      artistId: config.artistId,
      previews: config.previews || {}
    })
    : () => {};
  const cleanupAnimatedArtwork = config.features?.animatedArtwork
    ? attachAnimatedArtworkPreviews(app, {
      artistId: config.artistId,
      artistName: config.artistName
    })
    : () => {};
  const cleanupKeyEasterEgg = config.features?.keyEasterEgg
    ? attachKeyEasterEgg(app, config)
    : () => {};
  const cleanupIdlePreview = app.setCustomArtistIdlePreview?.(config) || (() => {});

  return () => {
    cleanupIdlePreview();
    cleanupKeyEasterEgg();
    cleanupPreviews();
    cleanupAnimatedArtwork();
    cleanupStyles();
    cleanupTheme();
    if (app.browseDetail.value === detail) {
      cleanupDetail.forEach((cleanup) => cleanup());
    }
  };
}

function attachStyles(config) {
  const links = [];
  const styleElements = [];

  if (config.styleText) {
    const style = document.createElement('style');
    style.dataset.customArtistStyle = config.artistId;
    style.textContent = config.styleText;
    document.head.append(style);
    styleElements.push(style);
  }

  for (const href of config.styles || []) {
    if (!href || document.querySelector(`link[data-custom-artist-style="${config.artistId}"][href="${href}"]`)) continue;

    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    link.dataset.customArtistStyle = config.artistId;
    document.head.append(link);
    links.push(link);
  }

  return () => {
    for (const link of links) link.remove();
    for (const style of styleElements) style.remove();
  };
}
