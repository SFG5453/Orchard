<!--
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
-->

<script>
import { copyTextToClipboard } from '../../app/platform/clipboardText.js';

export default {
  name: 'LegacyInstallDialog',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return {
      ...props.app,
      copyLegacyInstallCommand: () => copyTextToClipboard(props.app.legacyInstallState.value?.removal?.command || '')
    };
  }
};
</script>

<template>
  <q-dialog v-model="legacyInstallDialogOpen" persistent aria-label="Remove the old Orchard">
    <q-card class="update-dialog">
      <header class="update-dialog__header">
        <div>
          <div class="update-dialog__title">Remove the old Orchard</div>
          <div class="update-dialog__subtitle">Orchard now updates itself</div>
        </div>
      </header>

      <div class="update-dialog__body">
        <p>
          Orchard is running from its own package service, but the version you installed before it
          is still on this computer. Leaving it there means the old shortcut keeps opening a copy
          that no longer receives updates.
        </p>

        <p v-if="legacyInstallInstructions">{{ legacyInstallInstructions }}</p>

        <div v-if="legacyInstallState.removal?.mode === 'command'" class="legacy-install-dialog__command">
          <code>{{ legacyInstallState.removal.command }}</code>
          <button type="button" class="update-dialog__button" @click="copyLegacyInstallCommand">Copy</button>
        </div>

        <div v-if="legacyInstallState.location" class="legacy-install-dialog__location">
          Found at {{ legacyInstallState.location }}
        </div>

        <div v-if="legacyInstallState.error" class="update-dialog__status update-dialog__status--error">
          <q-icon name="warning" />
          <div><strong>{{ legacyInstallState.error }}</strong></div>
        </div>
      </div>

      <footer class="update-dialog__actions">
        <button type="button" class="update-dialog__button" @click="dismissLegacyInstall">
          {{ legacyInstallState.removal?.mode === 'command' ? 'Done' : 'Keep it for now' }}
        </button>
        <button
          v-if="legacyInstallActionLabel"
          type="button"
          class="update-dialog__button update-dialog__button--primary"
          @click="removeLegacyInstall"
        >
          {{ legacyInstallActionLabel }}
        </button>
      </footer>
    </q-card>
  </q-dialog>
</template>
