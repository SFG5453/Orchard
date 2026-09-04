const PIXEL_DELTA = 0;
const LINE_DELTA = 1;
const PAGE_DELTA = 2;
const LINE_HEIGHT_PX = 40;
const MIN_DISCRETE_DELTA_PX = 32;
const CHROMIUM_WHEEL_STEP_PX = 100;

function finiteNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

export function normalizedTauriWheelDelta(event, viewportHeight = 800) {
  const rawDelta = finiteNumber(event?.deltaY);
  if (!rawDelta || event?.ctrlKey || Math.abs(finiteNumber(event?.deltaX)) > Math.abs(rawDelta)) return 0;

  const mode = finiteNumber(event?.deltaMode);
  if (mode === PIXEL_DELTA && Math.abs(rawDelta) < MIN_DISCRETE_DELTA_PX) {
    // Preserve high-resolution touchpad input. WebKitGTK already reports those
    // gestures in useful pixel increments and changing them makes precision
    // scrolling feel twitchy.
    return 0;
  }

  const pixelDelta = mode === LINE_DELTA
    ? rawDelta * LINE_HEIGHT_PX
    : mode === PAGE_DELTA
      ? rawDelta * Math.max(1, finiteNumber(viewportHeight))
      : rawDelta;
  return Math.sign(pixelDelta) * Math.max(Math.abs(pixelDelta), CHROMIUM_WHEEL_STEP_PX);
}

function eventPath(event) {
  if (typeof event?.composedPath === 'function') return event.composedPath();
  const path = [];
  let element = event?.target;
  while (element) {
    path.push(element);
    element = element.parentElement;
  }
  return path;
}

function canScrollVertically(element, delta, getStyle) {
  if (!element || typeof element.scrollTop !== 'number') return false;
  const maxScrollTop = finiteNumber(element.scrollHeight) - finiteNumber(element.clientHeight);
  if (maxScrollTop <= 1) return false;
  const overflow = String(getStyle(element)?.overflowY || '');
  if (!/^(auto|scroll|overlay)$/.test(overflow)) return false;
  return delta > 0
    ? element.scrollTop < maxScrollTop - 1
    : element.scrollTop > 1;
}

export function installTauriWheelNormalization(target = globalThis) {
  const document = target?.document;
  if (!document?.addEventListener || typeof target.getComputedStyle !== 'function') return () => {};

  const onWheel = (event) => {
    if (event.defaultPrevented) return;
    const delta = normalizedTauriWheelDelta(event, target.innerHeight);
    if (!delta) return;

    const scrollTarget = eventPath(event)
      .find((element) => canScrollVertically(element, delta, target.getComputedStyle));
    if (!scrollTarget) return;

    event.preventDefault();
    scrollTarget.scrollTop += delta;
  };

  // Bubble after component handlers so controls such as the volume slider can
  // claim their wheel gesture first.
  document.addEventListener('wheel', onWheel, { passive: false });
  return () => document.removeEventListener('wheel', onWheel);
}
