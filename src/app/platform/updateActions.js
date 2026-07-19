import { computed } from 'vue';
import { refreshCustomArtistIndex } from '../appearance/customArtistPacks.js';

export function installUpdateActions(ctx) {
  ctx.updateBannerMessage = computed(() => {
    const state = ctx.updateState.value;
    if (!state || ['idle', 'disabled', 'current'].includes(state.status)) return '';
    if (state.status === 'error') return state.error ? `${state.message} ${state.error}` : state.message;
    return state.message || '';
  });

  ctx.updateBannerIcon = computed(() => {
    const status = ctx.updateState.value?.status;
    if (status === 'downloaded') return 'system_update_alt';
    if (status === 'error') return 'warning';
    return 'sync';
  });

  ctx.updateProgressPercent = computed(() => {
    const percent = Number(ctx.updateState.value?.progress?.percent || 0);
    if (!Number.isFinite(percent) || percent <= 0) return 0;
    return Math.max(0, Math.min(100, Math.round(percent)));
  });

  ctx.updateStatusLabel = computed(() => {
    const status = ctx.updateState.value?.status || 'idle';
    if (status === 'disabled') return 'Updates unavailable';
    if (status === 'checking') return 'Checking';
    if (status === 'available') return 'Downloading';
    if (status === 'downloading') return 'Downloading';
    if (status === 'downloaded') return 'Ready to install';
    if (status === 'current') return 'Up to date';
    if (status === 'error') return 'Update failed';
    return 'Ready';
  });

  ctx.updateCanInstall = computed(() => ctx.updateState.value?.status === 'downloaded');

  ctx.updateCanCheck = computed(() => !['checking', 'downloading', 'available'].includes(ctx.updateState.value?.status));

  ctx.contentUpdateStatusLabel = computed(() => {
    const status = ctx.updateState.value?.content?.status || 'idle';
    if (status === 'checking') return 'Checking';
    if (status === 'downloading') return 'Downloading';
    if (status === 'current') return 'Up to date';
    if (status === 'error') return 'Update failed';
    return 'Ready';
  });

  ctx.contentUpdateCanCheck = computed(() => !['checking', 'downloading'].includes(ctx.updateState.value?.content?.status));

  ctx.setContentUpdateError = function setContentUpdateError(message, error = '') {
    ctx.updateState.value = {
      ...ctx.updateState.value,
      content: {
        ...ctx.updateState.value.content,
        status: 'error',
        message,
        error
      }
    };
  };

  ctx.syncUpdateState = function syncUpdateState(state) {
    if (!state || typeof state !== 'object') return;
    ctx.updateState.value = {
      ...ctx.updateState.value,
      ...state,
      content: {
        ...ctx.updateState.value.content,
        ...(state.content || {})
      }
    };
  };

  ctx.checkForUpdates = async function checkForUpdates() {
    if (!window.orchardUpdates?.check) return;

    try {
      ctx.syncUpdateState(await window.orchardUpdates.check());
    } catch (error) {
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.installUpdate = async function installUpdate() {
    if (!window.orchardUpdates?.install) return;

    try {
      ctx.syncUpdateState(await window.orchardUpdates.install());
    } catch (error) {
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.checkContentUpdates = async function checkContentUpdates(options = {}) {
    if (!window.orchardUpdates?.checkContent) {
      ctx.setContentUpdateError('Artist page updates are unavailable.', 'The Electron update bridge is not loaded.');
      return;
    }

    try {
      ctx.syncUpdateState(await window.orchardUpdates.checkContent(options));
      await refreshCustomArtistIndex();
    } catch (error) {
      ctx.setContentUpdateError('Artist page update failed.', error.message || String(error));
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.importArtistPack = async function importArtistPack() {
    if (!window.orchardUpdates?.importArtistPack) {
      ctx.setContentUpdateError('Artist page imports are unavailable.', 'The Electron update bridge is not loaded.');
      return;
    }

    try {
      ctx.syncUpdateState(await window.orchardUpdates.importArtistPack());
      await refreshCustomArtistIndex();
    } catch (error) {
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.openUpdateDialog = function openUpdateDialog(options = {}) {
    ctx.updateDialogOpen.value = true;
    if (options.check && ctx.updateCanCheck.value) void ctx.checkForUpdates();
    if (options.checkContent && ctx.contentUpdateCanCheck.value) {
      void ctx.checkContentUpdates({ force: Boolean(ctx.updateState.value?.dev) });
    }
  };

  ctx.bindUpdateEvents = async function bindUpdateEvents() {
    if (!window.orchardUpdates) {
      ctx.setContentUpdateError('Artist page updates are unavailable.', 'The Electron update bridge is not loaded.');
      return false;
    }

    ctx.updateUnsubscribe?.();
    ctx.updateUnsubscribe = window.orchardUpdates.onState(ctx.syncUpdateState);

    try {
      ctx.syncUpdateState(await window.orchardUpdates.getState());
      return true;
    } catch {
      ctx.setContentUpdateError('Artist page update state is unavailable.', 'Could not read the Electron update bridge state.');
      return false;
    }
  };

  ctx.ensureOfficialArtistPages = async function ensureOfficialArtistPages() {
    if (ctx.customArtistPagesEnabled?.value === false) return;
    if (!window.orchardUpdates?.getUserArtistPacks) return;

    try {
      const index = await window.orchardUpdates.getUserArtistPacks();
      if (Object.keys(index?.artists || {}).length) {
        await refreshCustomArtistIndex();
        return;
      }
    } catch {
      // A follow-up content check will report any bridge or install error.
    }

    await ctx.checkContentUpdates({ force: Boolean(ctx.updateState.value?.dev) });
  };
}
