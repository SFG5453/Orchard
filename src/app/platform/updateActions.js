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
    if (status === 'downloaded' || status === 'external-downloaded') return 'system_update_alt';
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
    if (status === 'external-available') return 'Package available';
    if (status === 'external-downloading') return 'Downloading package';
    if (status === 'external-downloaded') return 'Saved to Downloads';
    if (status === 'current') return 'Up to date';
    if (status === 'error') return 'Update failed';
    return 'Ready';
  });

  ctx.updateCanInstall = computed(() => ctx.updateState.value?.status === 'downloaded');

  ctx.updateCanCheck = computed(() => !['checking', 'downloading', 'available', 'external-downloading'].includes(ctx.updateState.value?.status));

  ctx.updateChannelIsBeta = computed(() => ctx.updateState.value?.channel === 'beta');

  ctx.externalUpdateCanDownload = computed(() => (
    Boolean(ctx.updateState.value?.external) &&
    Boolean(ctx.updateState.value?.downloadAvailable) &&
    !['checking', 'external-downloading'].includes(ctx.updateState.value?.status)
  ));

  ctx.externalUpdateCanReveal = computed(() => (
    ctx.updateState.value?.status === 'external-downloaded' &&
    Boolean(ctx.updateState.value?.downloadedFile)
  ));

  ctx.updateChannelBetaToggle = computed({
    get: () => ctx.updateChannelIsBeta.value,
    set: (value) => { void ctx.setUpdateChannel(value ? 'beta' : 'stable'); }
  });

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

  ctx.setUpdateChannel = async function setUpdateChannel(channel) {
    if (!window.orchardUpdates?.setChannel) return;

    try {
      ctx.syncUpdateState(await window.orchardUpdates.setChannel(channel));
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

  ctx.downloadExternalUpdate = async function downloadExternalUpdate() {
    if (!window.orchardUpdates?.downloadExternal) return;

    try {
      ctx.syncUpdateState(await window.orchardUpdates.downloadExternal());
    } catch (error) {
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.revealExternalUpdate = async function revealExternalUpdate() {
    if (!window.orchardUpdates?.revealExternal) return;

    try {
      ctx.syncUpdateState(await window.orchardUpdates.revealExternal());
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
