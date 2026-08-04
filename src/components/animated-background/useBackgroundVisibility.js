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

export function useBackgroundVisibility(target, { onVisibility, onReducedMotion }) {
  const motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
  let intersecting = true;
  let observer = null;

  function visible() {
    return !document.hidden && document.hasFocus() && intersecting;
  }

  function notifyVisibility() {
    onVisibility(visible());
  }

  function notifyReducedMotion() {
    onReducedMotion(motionQuery.matches);
  }

  function start() {
    // Page visibility covers minimized/hidden Electron windows; focus prevents
    // a background render loop from consuming GPU behind another application.
    window.addEventListener('focus', notifyVisibility);
    window.addEventListener('blur', notifyVisibility);
    window.addEventListener('pageshow', notifyVisibility);
    window.addEventListener('pagehide', notifyVisibility);
    document.addEventListener('visibilitychange', notifyVisibility);
    motionQuery.addEventListener?.('change', notifyReducedMotion);

    if ('IntersectionObserver' in window) {
      observer = new IntersectionObserver(([entry]) => {
        intersecting = entry?.isIntersecting !== false;
        notifyVisibility();
      });
      observer.observe(target);
    }

    notifyReducedMotion();
    notifyVisibility();
  }

  function destroy() {
    observer?.disconnect();
    observer = null;
    window.removeEventListener('focus', notifyVisibility);
    window.removeEventListener('blur', notifyVisibility);
    window.removeEventListener('pageshow', notifyVisibility);
    window.removeEventListener('pagehide', notifyVisibility);
    document.removeEventListener('visibilitychange', notifyVisibility);
    motionQuery.removeEventListener?.('change', notifyReducedMotion);
  }

  return { start, destroy };
}
