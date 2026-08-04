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

import { computed } from 'vue';

export function installMigrationActions(ctx) {
  ctx.migrationBannerMessage = computed(() => {
    const state = ctx.migrationState.value;
    const unsupported = 'This version is no longer receiving updates and is unsupported.';

    if (state.status === 'ready' && state.version) {
      return `${unsupported} Orchard ${state.version} is available.`;
    }
    if (state.status === 'error') {
      return `${unsupported} The current release could not be loaded.`;
    }
    return `${unsupported} Checking for the current Orchard release…`;
  });

  ctx.syncMigrationState = function syncMigrationState(state) {
    if (!state || typeof state !== 'object') return;
    ctx.migrationState.value = { ...ctx.migrationState.value, ...state };
  };

  ctx.loadMigrationNotice = async function loadMigrationNotice({ force = false } = {}) {
    const bridge = window.orchardMigration;
    if (!bridge) {
      ctx.syncMigrationState({
        status: 'error',
        error: 'The Electron migration bridge is unavailable.'
      });
      return;
    }

    ctx.syncMigrationState({ status: 'loading', error: '' });
    try {
      const state = force ? await bridge.refresh() : await bridge.getState();
      ctx.syncMigrationState(state);
    } catch (error) {
      ctx.syncMigrationState({ status: 'error', error: error.message || String(error) });
    }
  };

  ctx.downloadMigrationRelease = async function downloadMigrationRelease() {
    if (!window.orchardMigration?.download) return;

    ctx.syncMigrationState({ status: 'loading', error: '' });
    try {
      ctx.syncMigrationState(await window.orchardMigration.download());
    } catch (error) {
      ctx.syncMigrationState({ status: 'error', error: error.message || String(error) });
    }
  };
}
