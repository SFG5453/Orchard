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
