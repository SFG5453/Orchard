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

// The proxy mode is owned by the main process, which applies it to the session
// before any window loads. The renderer only reads it back and asks for changes.
import { computed, ref } from 'vue';

export function installNetworkActions(ctx) {
  ctx.proxyMode = ref('system');
  ctx.proxyModeMessage = ref('');
  ctx.proxyModeAvailable = Boolean(window.orchardNetwork?.setProxyMode);

  ctx.loadProxyMode = async function loadProxyMode() {
    if (!window.orchardNetwork?.getProxyMode) return;
    const mode = await window.orchardNetwork.getProxyMode();
    if (mode) ctx.proxyMode.value = mode;
  };

  ctx.setProxyMode = async function setProxyMode(mode) {
    if (!window.orchardNetwork?.setProxyMode) return;
    try {
      ctx.proxyMode.value = await window.orchardNetwork.setProxyMode(mode);
      // Images already refused by the dead proxy stay broken until they are
      // requested again, so say so rather than leaving the listener staring at
      // the same empty artwork wondering whether the setting took.
      ctx.proxyModeMessage.value = ctx.proxyMode.value === 'direct'
        ? 'Orchard now ignores the system proxy. Restart Orchard if album art is still missing.'
        : 'Orchard now follows the system proxy.';
    } catch (error) {
      ctx.proxyModeMessage.value = `Could not change the proxy setting. ${error?.message || error}`;
    }
  };

  ctx.proxyBypassToggle = computed({
    get: () => ctx.proxyMode.value === 'direct',
    set: (value) => { void ctx.setProxyMode(value ? 'direct' : 'system'); }
  });
}
