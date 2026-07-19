import { nextTick, ref } from 'vue';

const SOURCE_ZOOM = 3.1;
const DESTINATION_ZOOM = 2.65;
const ZOOM_IN_MS = 430;
const HOLD_MS = 100;
const ZOOM_OUT_MS = 620;
const SPOTLIGHT_MOVE_MS = 460;
const SPOTLIGHT_FADE_MS = 160;
const SPOTLIGHT_BORDER_PX = 4;
const EASE = 'cubic-bezier(0.2, 0, 0, 1)';
const DESTINATION_SELECTOR = '.custom-artist-page-art__image';
const STYLED_DESTINATION_SELECTOR = '.detail-page--artist[class*="detail-page--layout-"] .custom-artist-page-art__image';
const FALLBACK_ACCENT = '#66d8ff';

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function nextFrame() {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}

function reducedMotion() {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}

function elementCenter(element) {
  const rect = element?.getBoundingClientRect?.();
  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
  return {
    x: rect.left + rect.width / 2,
    y: rect.top + rect.height / 2
  };
}

function elementRect(element) {
  const rect = element?.getBoundingClientRect?.();
  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
  return rect;
}

function cameraStyle(center, scale, duration, originDuration = duration) {
  return {
    transformOrigin: `${center.x}px ${center.y}px`,
    transform: `scale(${scale})`,
    transition: [
      `transform ${duration}ms ${EASE}`,
      `transform-origin ${originDuration}ms ${EASE}`
    ].join(', ')
  };
}

function transitionStyle() {
  return [
    `left ${SPOTLIGHT_MOVE_MS}ms ${EASE}`,
    `top ${SPOTLIGHT_MOVE_MS}ms ${EASE}`,
    `width ${SPOTLIGHT_MOVE_MS}ms ${EASE}`,
    `height ${SPOTLIGHT_MOVE_MS}ms ${EASE}`,
    `border-radius ${SPOTLIGHT_MOVE_MS}ms ${EASE}`,
    `transform ${ZOOM_IN_MS}ms ${EASE}`
  ].join(', ');
}

function spotlightSize(sourceRect) {
  const viewportLimit = Math.min(window.innerWidth, window.innerHeight) * 0.34;
  return Math.round(Math.min(Math.max(sourceRect.width, sourceRect.height, 160), viewportLimit, 230));
}

function setFrameBox(frame, rect, borderRadius) {
  Object.assign(frame.style, {
    left: `${rect.left - SPOTLIGHT_BORDER_PX}px`,
    top: `${rect.top - SPOTLIGHT_BORDER_PX}px`,
    width: `${rect.width + SPOTLIGHT_BORDER_PX * 2}px`,
    height: `${rect.height + SPOTLIGHT_BORDER_PX * 2}px`,
    borderRadius
  });
}

function setSpotlightAccent(spotlight, accentColor) {
  const color = typeof accentColor === 'string' && accentColor.trim()
    ? accentColor.trim()
    : FALLBACK_ACCENT;
  spotlight?.root?.style.setProperty('--custom-artist-profile-accent', color);
}

async function bindSpotlightAccent(spotlight, accentColorPromise) {
  if (!accentColorPromise) return;
  setSpotlightAccent(spotlight, await accentColorPromise.catch(() => ''));
}

function createSpotlight(sourceElement, sourceRect, accentColor) {
  const sourceStyle = window.getComputedStyle(sourceElement);
  const sourceRadius = sourceStyle.borderRadius || '50%';
  const root = document.createElement('div');
  const frame = document.createElement('div');
  const art = document.createElement('div');
  const clone = sourceElement.cloneNode(true);

  root.className = 'custom-artist-profile-spotlight';
  frame.className = 'custom-artist-profile-spotlight__frame';
  art.className = 'custom-artist-profile-spotlight__art';
  clone.classList.add('custom-artist-profile-spotlight__clone');
  clone.setAttribute('aria-hidden', 'true');

  setSpotlightAccent({ root }, accentColor);
  frame.style.transition = transitionStyle();
  frame.style.transform = 'scale(1)';
  setFrameBox(frame, sourceRect, sourceRadius);
  art.appendChild(clone);
  frame.appendChild(art);
  root.appendChild(frame);
  document.body.appendChild(root);

  return { root, frame, sourceRadius };
}

async function moveSpotlightToCenter(spotlight, sourceRect) {
  if (!spotlight) return;

  const size = spotlightSize(sourceRect);
  const targetRect = {
    left: (window.innerWidth - size) / 2,
    top: (window.innerHeight - size) / 2,
    width: size,
    height: size
  };

  await nextFrame();
  spotlight.root.classList.add('custom-artist-profile-spotlight--active');
  setFrameBox(spotlight.frame, targetRect, spotlight.sourceRadius);
  await delay(SPOTLIGHT_MOVE_MS);
}

async function zoomSpotlightIn(spotlight) {
  if (!spotlight?.frame?.isConnected) return;
  spotlight.frame.style.transform = `scale(${SOURCE_ZOOM})`;
  await delay(ZOOM_IN_MS);
}

async function removeSpotlight(spotlight) {
  if (!spotlight?.root?.isConnected) return;
  spotlight.root.classList.add('custom-artist-profile-spotlight--leaving');
  await delay(SPOTLIGHT_FADE_MS);
  spotlight.root.remove();
}

async function waitForDestination(timeoutMs = 1400) {
  const startedAt = performance.now();
  let fallback = null;

  while (performance.now() - startedAt < timeoutMs) {
    const styled = document.querySelector(STYLED_DESTINATION_SELECTOR);
    if (styled && elementCenter(styled)) return styled;

    fallback = document.querySelector(DESTINATION_SELECTOR) || fallback;
    await nextFrame();
  }

  return fallback && elementCenter(fallback) ? fallback : null;
}

export function installCustomArtistProfileTransition(ctx) {
  ctx.customArtistProfileCameraActive = ref(false);
  ctx.customArtistProfileCameraStyle = ref({});

  ctx.openCustomArtistProfileCameraTransition = async function openCustomArtistProfileCameraTransition({
    sourceElement,
    accentColor,
    accentColorPromise,
    open
  } = {}) {
    if (ctx.customArtistPagesEnabled?.value === false) {
      await open?.();
      return;
    }

    const sourceRect = typeof window === 'undefined' || typeof document === 'undefined' || reducedMotion()
      ? null
      : elementRect(sourceElement);

    if (!sourceRect) {
      await open?.();
      return;
    }

    const spotlight = createSpotlight(sourceElement, sourceRect, accentColor);
    bindSpotlightAccent(spotlight, accentColorPromise);

    try {
      await moveSpotlightToCenter(spotlight, sourceRect);
      await open?.();
      await nextTick();

      const destination = await waitForDestination();
      const destinationCenter = elementCenter(destination);
      if (!destinationCenter) return;

      ctx.customArtistProfileCameraActive.value = true;
      ctx.customArtistProfileCameraStyle.value = {
        transformOrigin: `${destinationCenter.x}px ${destinationCenter.y}px`,
        transform: `scale(${DESTINATION_ZOOM})`,
        transition: 'none'
      };

      await nextFrame();
      await zoomSpotlightIn(spotlight);
      await delay(HOLD_MS);
      await removeSpotlight(spotlight);
      await nextFrame();
      ctx.customArtistProfileCameraStyle.value = cameraStyle(destinationCenter, 1, ZOOM_OUT_MS);
      await delay(ZOOM_OUT_MS);
    } finally {
      await removeSpotlight(spotlight);
      ctx.customArtistProfileCameraActive.value = false;
      ctx.customArtistProfileCameraStyle.value = {};
    }
  };
}
