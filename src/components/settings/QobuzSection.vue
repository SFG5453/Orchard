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
import { onMounted, ref } from 'vue';

export default {
  name: 'QobuzSection',
  setup() {
    const state = ref({
      status: 'disconnected',
      enabled: false,
      quality: 'auto',
      secureStorage: false,
      persistent: false,
      lastError: ''
    });
    const message = ref('');
    const busy = ref(false);
    const qualities = [
      { value: 'auto', label: 'Best available' },
      { value: 'lossless', label: 'CD lossless' },
      { value: 'hires', label: 'Hi-Res' }
    ];

    async function loadStatus() {
      if (!window.orchardQobuz) return;
      try {
        state.value = await window.orchardQobuz.status();
      } catch (error) {
        message.value = error?.message || 'Could not load Qobuz status.';
      }
    }

    async function connect() {
      if (!window.orchardQobuz || busy.value) return;
      busy.value = true;
      message.value = '';
      try {
        state.value = await window.orchardQobuz.connect();
        if (state.value.status === 'connected') {
          message.value = state.value.persistent
            ? 'Qobuz is connected and enabled.'
            : 'Qobuz is connected for this session. Secure credential storage is unavailable.';
        } else {
          message.value = 'Login closed without connecting.';
        }
      } catch (error) {
        message.value = error?.message || 'Qobuz login failed.';
        await loadStatus();
      } finally {
        busy.value = false;
      }
    }

    async function disconnect() {
      if (!window.orchardQobuz || busy.value) return;
      busy.value = true;
      message.value = '';
      try {
        state.value = await window.orchardQobuz.disconnect();
        message.value = 'Qobuz disconnected and its local login data was cleared.';
      } catch (error) {
        message.value = error?.message || 'Could not disconnect Qobuz.';
      } finally {
        busy.value = false;
      }
    }

    async function setEnabled(value) {
      if (!window.orchardQobuz) return;
      state.value = await window.orchardQobuz.update({ enabled: value });
    }

    async function setQuality(value) {
      if (!window.orchardQobuz) return;
      state.value = await window.orchardQobuz.update({ quality: value });
    }

    onMounted(loadStatus);
    return { busy, connect, disconnect, message, qualities, setEnabled, setQuality, state };
  }
};
</script>

<template>
  <div class="settings-row settings-row--options qobuz-row">
    <div class="settings-row__copy">
      <label>Qobuz lossless playback</label>
      <p v-if="state.status === 'connected'">
        Use your Qobuz subscription as an optional lossless source. Orchard still uses YouTube Music
        for its catalog and safely falls back when it cannot identify the same recording.
      </p>
      <p v-else>
        Connect your own Qobuz subscription to add lossless and Hi-Res playback. This uses Qobuz's
        private web API and may need maintenance when its web player changes.
      </p>
      <small v-if="message || state.lastError" class="qobuz-message">
        {{ message || state.lastError }}
      </small>
    </div>
    <div class="settings-actions qobuz-actions">
      <template v-if="state.status === 'connected'">
        <q-toggle
          :model-value="state.enabled"
          color="primary"
          label="Enabled"
          aria-label="Use Qobuz for matching tracks"
          @update:model-value="setEnabled"
        />
        <button type="button" class="settings-link-button settings-link-button--danger" :disabled="busy" @click="disconnect">
          Disconnect
        </button>
      </template>
      <button v-else type="button" class="settings-button" :disabled="busy" @click="connect">
        {{ busy ? 'Connecting…' : 'Connect Qobuz' }}
      </button>
    </div>
  </div>

  <div v-if="state.status === 'connected'" class="settings-row settings-row--options qobuz-quality-row">
    <div class="settings-row__copy">
      <label id="settings-qobuz-quality">Qobuz quality</label>
      <p>Best available asks for up to 24-bit/192 kHz; Qobuz returns the best master your plan allows.</p>
    </div>
    <div class="settings-option-group" role="group" aria-labelledby="settings-qobuz-quality">
      <button
        v-for="option in qualities"
        :key="option.value"
        type="button"
        class="settings-option"
        :class="{ 'settings-option--active': state.quality === option.value }"
        :aria-pressed="state.quality === option.value"
        @click="setQuality(option.value)"
      >
        {{ option.label }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.qobuz-actions {
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 0;
}

.qobuz-message {
  display: block;
  margin-top: 5px;
  color: #aab2ac;
  font-size: 10px;
  line-height: 1.4;
}

.qobuz-quality-row {
  margin-top: -4px;
}
</style>
