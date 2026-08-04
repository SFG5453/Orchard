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
import { ref, onMounted } from 'vue';

export default {
  name: 'SpotifySection',
  props: { app: { type: Object, required: true } },
  setup() {
    const spotifyState = ref({ status: 'disconnected', hasSpdc: false, secureStorage: false });
    const spotifyMessage = ref('');
    const showManualInput = ref(false);
    const manualSpdc = ref('');

    const loadStatus = async () => {
      if (!window.orchardSpotify) return;
      try {
        spotifyState.value = await window.orchardSpotify.status();
      } catch (err) {
        console.warn('Failed to load Spotify status', err);
      }
    };

    const connectSpotify = async () => {
      if (!window.orchardSpotify) return;
      spotifyMessage.value = '';
      try {
        spotifyState.value = await window.orchardSpotify.connect();
        spotifyMessage.value = spotifyState.value.hasSpdc ? 'Connected to Spotify.' : 'Login closed.';
      } catch (err) {
        spotifyMessage.value = err?.message || 'Login failed.';
      }
    };

    const disconnectSpotify = async () => {
      if (!window.orchardSpotify) return;
      spotifyMessage.value = '';
      try {
        spotifyState.value = await window.orchardSpotify.disconnect();
        spotifyMessage.value = 'Disconnected from Spotify.';
      } catch (err) {
        spotifyMessage.value = err?.message || 'Disconnect failed.';
      }
    };

    const saveManualSpdc = async () => {
      if (!window.orchardSpotify || !manualSpdc.value.trim()) return;
      spotifyMessage.value = '';
      try {
        spotifyState.value = await window.orchardSpotify.saveSpdc(manualSpdc.value.trim());
        spotifyMessage.value = spotifyState.value.hasSpdc ? 'Spotify sp_dc cookie saved.' : 'Invalid cookie.';
        manualSpdc.value = '';
        showManualInput.value = false;
      } catch (err) {
        spotifyMessage.value = err?.message || 'Failed to save cookie.';
      }
    };

    onMounted(() => {
      loadStatus();
    });

    return {
      spotifyState,
      spotifyMessage,
      showManualInput,
      manualSpdc,
      connectSpotify,
      disconnectSpotify,
      saveManualSpdc
    };
  }
};
</script>

<template>
  <div class="settings-row settings-row--options">
    <div class="settings-row__copy">
      <label>Spotify Canvas integration</label>
      <p v-if="spotifyState.status === 'connected'">
        Connected to Spotify. Canvas animated artwork (.mp4) will be fetched when available.
      </p>
      <p v-else>
        Log into Spotify or supply your <code>sp_dc</code> cookie to fetch Spotify Canvas videos when other providers miss.
      </p>
      <small v-if="spotifyMessage" class="settings-status-message">{{ spotifyMessage }}</small>
    </div>
    <div class="settings-actions spotify-actions">
      <template v-if="spotifyState.status === 'connected'">
        <button
          type="button"
          class="settings-link-button settings-link-button--danger"
          @click="disconnectSpotify"
        >
          Disconnect
        </button>
      </template>
      <template v-else>
        <button
          type="button"
          class="settings-button"
          @click="connectSpotify"
        >
          Log in to Spotify
        </button>
        <button
          type="button"
          class="settings-link-button"
          @click="showManualInput = !showManualInput"
        >
          {{ showManualInput ? 'Cancel' : 'Enter Cookie' }}
        </button>
      </template>
    </div>
  </div>

  <div v-if="showManualInput && spotifyState.status !== 'connected'" class="settings-manual-cookie-row">
    <input
      v-model="manualSpdc"
      type="password"
      class="settings-input"
      placeholder="Paste sp_dc cookie string here..."
      @keyup.enter="saveManualSpdc"
    />
    <button
      type="button"
      class="settings-button"
      :disabled="!manualSpdc.trim()"
      @click="saveManualSpdc"
    >
      Save
    </button>
  </div>
</template>

<style scoped>
.settings-status-message {
  display: block;
  margin-top: 5px;
  color: #929a94;
  font-size: 10px;
  line-height: 1.4;
}

.spotify-actions {
  justify-content: flex-end;
  gap: 8px;
  padding-top: 0;
}

.settings-manual-cookie-row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 6px;
}

.settings-input {
  flex: 1;
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  color: #fff;
  padding: 6px 10px;
  font-size: 12px;
}
</style>
