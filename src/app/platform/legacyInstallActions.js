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

export function installLegacyInstallActions(ctx) {
  ctx.legacyInstallPresent = computed(() => ctx.legacyInstallState.value?.status === 'present');

  ctx.legacyInstallActionLabel = computed(() => {
    const mode = ctx.legacyInstallState.value?.removal?.mode;
    if (mode === 'uninstaller') return 'Open uninstaller';
    if (mode === 'reveal') return 'Show in file manager';
    return '';
  });

  ctx.legacyInstallInstructions = computed(() => {
    const state = ctx.legacyInstallState.value;
    if (state?.removal?.mode === 'uninstaller') {
      return 'Orchard will open Windows’ uninstaller for the old version. Nothing is removed until you confirm it there.';
    }
    if (state?.removal?.mode === 'command') {
      return 'This copy belongs to your package manager, so remove it with the command below rather than deleting the files.';
    }
    if (state?.removal?.mode === 'reveal') {
      return 'Orchard will show the old copy in your file manager so you can move it to the trash yourself.';
    }
    return '';
  });

  ctx.syncLegacyInstallState = function syncLegacyInstallState(state) {
    if (!state || typeof state !== 'object') return;
    ctx.legacyInstallState.value = { ...ctx.legacyInstallState.value, ...state };
  };

  ctx.loadLegacyInstallNotice = async function loadLegacyInstallNotice({ force = false } = {}) {
    const bridge = window.orchardLegacyInstall;
    if (!bridge) return;

    try {
      ctx.syncLegacyInstallState(force ? await bridge.refresh() : await bridge.getState());
    } catch (error) {
      ctx.syncLegacyInstallState({ status: 'error', error: error.message || String(error) });
      return;
    }

    if (ctx.legacyInstallPresent.value) ctx.legacyInstallDialogOpen.value = true;
  };

  ctx.removeLegacyInstall = async function removeLegacyInstall() {
    if (!window.orchardLegacyInstall?.remove) return;

    try {
      ctx.syncLegacyInstallState(await window.orchardLegacyInstall.remove());
    } catch (error) {
      ctx.syncLegacyInstallState({ status: 'error', error: error.message || String(error) });
      return;
    }

    if (!ctx.legacyInstallPresent.value) ctx.legacyInstallDialogOpen.value = false;
  };

  ctx.dismissLegacyInstall = async function dismissLegacyInstall() {
    ctx.legacyInstallDialogOpen.value = false;
    try {
      ctx.syncLegacyInstallState(await window.orchardLegacyInstall?.dismiss());
    } catch {
      // The prompt reappears next launch if the dismissal could not be stored.
    }
  };
}
