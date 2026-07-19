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
