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

function desktopBridge() {
  return typeof window === 'undefined' ? null : window.orchardDesktopControls;
}

export function installDesktopActions(ctx) {
  ctx.syncDesktopControlsState = function syncDesktopControlsState() {
    const bridge = desktopBridge();
    if (!bridge) return;

    try {
      bridge.setState(ctx.systemMediaPayload())?.catch?.(() => {});
    } catch {
      // Desktop controls are a convenience layer; playback should never wait on them.
    }
  };

  ctx.toggleCompactWindow = async function toggleCompactWindow() {
    const bridge = desktopBridge();
    if (!bridge) return;

    try {
      ctx.compactWindow.value = Boolean(await bridge.toggleCompact());
    } catch {
      // Ignore unavailable host window controls.
    }
  };

  ctx.bindDesktopControls = async function bindDesktopControls() {
    const bridge = desktopBridge();
    if (!bridge) return;

    ctx.desktopControlsUnsubscribe = bridge.onCompactState?.((compact) => {
      ctx.compactWindow.value = compact;
    }) || null;

    try {
      ctx.compactWindow.value = Boolean(await bridge.getCompactState());
    } catch {
      ctx.compactWindow.value = false;
    }

    ctx.syncDesktopControlsState();
  };

  ctx.clearDesktopControls = function clearDesktopControls() {
    ctx.desktopControlsUnsubscribe?.();
    ctx.desktopControlsUnsubscribe = null;
  };
}
