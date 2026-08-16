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
import AudioEngineSection from './AudioEngineSection.vue';
import BackupRestoreSection from './BackupRestoreSection.vue';
import DiagnosticsSection from './DiagnosticsSection.vue';
import LastfmSection from './LastfmSection.vue';
import SpotifySection from './SpotifySection.vue';
import SongCacheSection from './SongCacheSection.vue';
import ArtistPacksSection from './ArtistPacksSection.vue';
import OrchardAccountSection from './OrchardAccountSection.vue';
import { computed, ref, watch } from 'vue';

export default {
  name: 'SettingsView',
  components: { AudioEngineSection, BackupRestoreSection, DiagnosticsSection, LastfmSection, SpotifySection, SongCacheSection, ArtistPacksSection, OrchardAccountSection },
  props: { app: { type: Object, required: true } },
  setup(props) {
    const layoutPresetDescription = computed(() => {
      const active = props.app.layoutPresetOptions
        .find((option) => option.value === props.app.layoutPreset.value);
      return active?.description || '';
    });
    const uiScaleDraft = ref(props.app.uiScale.value);
    watch(props.app.uiScale, (scale) => {
      uiScaleDraft.value = scale;
    });
    const applyUiScale = (scale) => {
      props.app.uiScale.value = scale;
    };

    return { ...props.app, app: props.app, layoutPresetDescription, uiScaleDraft, applyUiScale };
  }
};
</script>

<template>
  <main class="settings-view">
    <nav class="settings-index" aria-label="Settings sections">
      <a href="#settings-playback">
        <q-icon name="play_circle_outline" />
        <span>Playback</span>
      </a>
      <a href="#settings-audio-engine">
        <q-icon name="equalizer" />
        <span>Audio Engine</span>
      </a>
      <a href="#settings-song-cache">
        <q-icon name="offline_pin" />
        <span>Song Cache</span>
      </a>
      <a href="#settings-appearance">
        <q-icon name="palette" />
        <span>Appearance</span>
      </a>
      <a href="#settings-integrations">
        <q-icon name="extension" />
        <span>Integrations</span>
      </a>
      <a href="#settings-connect">
        <q-icon name="phonelink" />
        <span>Connect</span>
      </a>
      <a href="#settings-application">
        <q-icon name="info_outline" />
        <span>Application</span>
      </a>
      <a href="#settings-diagnostics">
        <q-icon name="fact_check" />
        <span>Diagnostics</span>
      </a>
      <a href="#settings-backup">
        <q-icon name="archive" />
        <span>Backup</span>
      </a>
    </nav>

    <div class="settings-content">
      <section id="settings-playback" class="settings-section" aria-labelledby="settings-playback-title">
        <div class="settings-section__heading">
          <h2 id="settings-playback-title">Playback</h2>
          <p>Control how Orchard moves between tracks.</p>
        </div>

        <div class="settings-row">
          <div class="settings-row__copy">
            <label for="settings-autoplay">Autoplay</label>
            <p>Keep the queue going with related music.</p>
          </div>
          <q-toggle id="settings-autoplay" v-model="autoplayEnabled" color="primary" aria-label="Autoplay" />
        </div>

        <div class="settings-row">
          <div class="settings-row__copy">
            <label for="settings-save-playback-state">Save queue and current song</label>
            <p>Restore the last queue and song when Orchard starts.</p>
          </div>
          <q-toggle id="settings-save-playback-state" v-model="playbackStatePersistenceEnabled" color="primary" aria-label="Save queue and current song" />
        </div>

        <div class="settings-row settings-row--options">
          <div class="settings-row__copy">
            <label id="settings-queue-layout-label">Queue style</label>
            <p>Up next lists only what is still queued. Continuous also shows what already played, with the current song in place.</p>
          </div>
          <div class="settings-option-group" role="group" aria-labelledby="settings-queue-layout-label">
            <button
              v-for="option in queueLayoutOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': queueLayout === option.value }"
              :aria-pressed="queueLayout === option.value"
              @click="queueLayout = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <div class="settings-row">
          <div class="settings-row__copy">
            <label for="settings-crossfade">Crossfade</label>
            <p>Blend the end of one track into the next.</p>
          </div>
          <q-toggle id="settings-crossfade" v-model="crossfadeEnabled" color="primary" aria-label="Crossfade" />
        </div>

        <div class="settings-row settings-row--options" :class="{ 'settings-row--disabled': !crossfadeEnabled }">
          <div class="settings-row__copy">
            <label id="settings-crossfade-mode-label">Crossfade mode</label>
            <p>Smart listens for outros and avoids awkward speech, live, and quiet-track blends.</p>
          </div>
          <div class="settings-option-group" role="group" aria-labelledby="settings-crossfade-mode-label">
            <button
              v-for="option in crossfadeModeOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': crossfadeMode === option.value }"
              :aria-pressed="crossfadeMode === option.value"
              :disabled="!crossfadeEnabled"
              @click="crossfadeMode = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <div class="settings-row settings-row--slider" :class="{ 'settings-row--disabled': !crossfadeEnabled }">
          <div class="settings-row__copy">
            <label for="settings-crossfade-length">Crossfade length</label>
            <p>How long the transition between tracks lasts.</p>
          </div>
          <div class="settings-slider">
            <q-slider
              id="settings-crossfade-length"
              v-model="crossfadeSeconds"
              :min="1"
              :max="12"
              :step="1"
              :disable="!crossfadeEnabled"
              color="primary"
              aria-label="Crossfade length"
            />
            <output for="settings-crossfade-length">{{ crossfadeSeconds }} seconds</output>
          </div>
        </div>

        <div id="settings-sleep-timer" class="settings-row settings-row--sleep-timer">
          <div class="settings-row__copy">
            <label id="settings-sleep-timer-label">Sleep timer</label>
            <p aria-live="polite">{{ sleepTimerSummary }}</p>
          </div>
          <div class="sleep-timer-options" role="group" aria-labelledby="settings-sleep-timer-label">
            <button
              v-for="option in sleepTimerOptions"
              :key="option.value"
              type="button"
              class="sleep-timer-option"
              :class="{ 'sleep-timer-option--active': sleepTimerMode === option.value }"
              :aria-pressed="sleepTimerMode === option.value"
              @click="startSleepTimer(option.value)"
            >
              {{ option.label }}
            </button>
            <button
              type="button"
              class="sleep-timer-option sleep-timer-option--track"
              :class="{ 'sleep-timer-option--active': sleepTimerMode === 'end-track' }"
              :aria-pressed="sleepTimerMode === 'end-track'"
              :disabled="!activeTrack"
              @click="startSleepTimer('end-track')"
            >
              End of song
            </button>
            <button
              v-if="sleepTimerActive"
              type="button"
              class="sleep-timer-option sleep-timer-option--cancel"
              @click="cancelSleepTimer"
            >
              Cancel
            </button>
          </div>
        </div>
      </section>

      <SongCacheSection :app="app" />
      <ArtistPacksSection :app="app" />

      <AudioEngineSection :app="app" />

      <section id="settings-appearance" class="settings-section" aria-labelledby="settings-appearance-title">
        <div class="settings-section__heading">
          <h2 id="settings-appearance-title">Appearance</h2>
          <p>Choose how artwork shapes the listening view.</p>
        </div>

        <div class="settings-row settings-row--slider">
          <div class="settings-row__copy">
            <label for="settings-ui-scale">Text and interface size</label>
            <p>Scale Orchard for readability without changing your display settings.</p>
          </div>
          <div class="settings-slider">
            <q-slider id="settings-ui-scale" v-model="uiScaleDraft" :min="0.85" :max="1.5" :step="0.05" color="primary" aria-label="Text and interface size" @change="applyUiScale" />
            <output for="settings-ui-scale">{{ Math.round(uiScaleDraft * 100) }}%</output>
          </div>
        </div>

        <div class="settings-row settings-row--options">
          <div class="settings-row__copy">
            <label id="settings-layout-preset-label">Interface design</label>
            <p>{{ layoutPresetDescription }}</p>
          </div>
          <div class="settings-option-group" role="group" aria-labelledby="settings-layout-preset-label">
            <button
              v-for="option in layoutPresetOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': layoutPreset === option.value }"
              :aria-pressed="layoutPreset === option.value"
              @click="layoutPreset = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <div class="settings-row settings-row--options">
          <div class="settings-row__copy">
            <label id="settings-graphics-mode-label">Graphics mode</label>
            <p id="settings-graphics-mode-description">
              Automatic uses Electron defaults. Integrated GPU prefers lower-power graphics. Changes require a restart.
            </p>
            <p v-if="graphicsModePlatform === 'linux' && graphicsModeIntegratedSupported">
              On Linux, Orchard selects the detected integrated adapter directly.
            </p>
            <p v-else-if="graphicsModeStatusReady && !graphicsModeIntegratedSupported">
              Integrated GPU selection is unavailable on this platform.
            </p>
            <p v-if="graphicsModeMessage" aria-live="polite">{{ graphicsModeMessage }}</p>
          </div>
          <div
            class="settings-option-group"
            role="group"
            aria-labelledby="settings-graphics-mode-label"
            aria-describedby="settings-graphics-mode-description"
          >
            <button
              v-for="option in graphicsModeOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': graphicsMode === option.value }"
              :aria-pressed="graphicsMode === option.value"
              :disabled="option.value === 'integrated' && graphicsModeStatusReady && !graphicsModeIntegratedSupported"
              @click="graphicsMode = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <div v-if="graphicsModeRestartRequired" class="settings-action-row">
          <div class="settings-row__copy">
            <span>Restart required</span>
            <p>Restart Orchard to apply {{ graphicsModeOptions.find((option) => option.value === graphicsMode)?.label || 'the selected graphics mode' }}.</p>
          </div>
          <button type="button" class="settings-button" @click="restartOrchard">
            <q-icon name="restart_alt" />
            Restart Orchard
          </button>
        </div>

        <div class="settings-row">
          <div class="settings-row__copy">
            <label for="settings-immersive">Immersive backgrounds</label>
            <p>Use the current artwork to tint and animate the app background.</p>
          </div>
          <q-toggle id="settings-immersive" v-model="immersiveBackgroundsEnabled" color="primary" aria-label="Immersive backgrounds" />
        </div>

        <div class="settings-row settings-row--options" :class="{ 'settings-row--disabled': !immersiveBackgroundsEnabled }">
          <div class="settings-row__copy">
            <label id="settings-background-intensity-label">Background intensity</label>
            <p>Choose how strongly the artwork fills the app.</p>
          </div>
          <div class="settings-option-group" role="group" aria-labelledby="settings-background-intensity-label">
            <button
              v-for="option in immersiveBackgroundIntensityOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': immersiveBackgroundIntensity === option.value }"
              :aria-pressed="immersiveBackgroundIntensity === option.value"
              :disabled="!immersiveBackgroundsEnabled"
              @click="immersiveBackgroundIntensity = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <div class="settings-row settings-row--options" :class="{ 'settings-row--disabled': !immersiveBackgroundsEnabled }">
          <div class="settings-row__copy">
            <label id="settings-background-motion-label">Background motion</label>
            <p>Freeze the artwork wash without turning off its color.</p>
          </div>
          <div class="settings-option-group" role="group" aria-labelledby="settings-background-motion-label">
            <button
              v-for="option in immersiveBackgroundMotionOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': immersiveBackgroundMotion === option.value }"
              :aria-pressed="immersiveBackgroundMotion === option.value"
              :disabled="!immersiveBackgroundsEnabled"
              @click="immersiveBackgroundMotion = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <div class="settings-row settings-row--options">
          <div class="settings-row__copy">
            <label id="settings-accent-source-label">Accent color</label>
            <p>Color controls and highlights from the artwork, Orchard, or your own choice.</p>
          </div>
          <div class="settings-option-group" role="group" aria-labelledby="settings-accent-source-label">
            <button
              v-for="option in accentColorSourceOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': accentColorSource === option.value }"
              :aria-pressed="accentColorSource === option.value"
              @click="accentColorSource = option.value"
            >
              {{ option.label }}
            </button>
            <label v-if="accentColorSource === 'custom'" class="settings-color-control" for="settings-custom-accent">
              <span>Choose color</span>
              <input id="settings-custom-accent" v-model="customAccentColor" type="color" aria-label="Custom accent color" />
            </label>
          </div>
        </div>

        <div class="settings-row settings-row--options">
          <div class="settings-row__copy">
            <label id="settings-theme-label">Theme</label>
            <p>Use Orchard dark, true black OLED, or follow the operating system.</p>
          </div>
          <div class="settings-option-group" role="group" aria-labelledby="settings-theme-label">
            <button
              v-for="option in themePreferenceOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': themePreference === option.value }"
              :aria-pressed="themePreference === option.value"
              @click="themePreference = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>
      </section>

      <section id="settings-integrations" class="settings-section" aria-labelledby="settings-integrations-title">
        <div class="settings-section__heading">
          <h2 id="settings-integrations-title">Integrations</h2>
          <p>Manage what Orchard shares with other desktop apps.</p>
        </div>

        <div class="settings-row">
          <div class="settings-row__copy">
            <label for="settings-discord">Discord Rich Presence</label>
            <p>Show the current track and artwork on your Discord profile.</p>
          </div>
          <q-toggle id="settings-discord" v-model="discordRpcEnabled" color="primary" aria-label="Discord Rich Presence" />
        </div>

        <LastfmSection :app="app" />
        <SpotifySection :app="app" />
        <OrchardAccountSection :app="app" />

        <div class="settings-row">
          <div class="settings-row__copy">
            <label for="settings-youtube-history">Send listening history to YouTube</label>
            <p>Tracks played in Orchard are added to the signed-in YouTube Music history.</p>
          </div>
          <q-toggle id="settings-youtube-history" v-model="youtubeHistoryEnabled" color="primary" aria-label="Send listening history to YouTube" />
        </div>
      </section>

      <section id="settings-connect" class="settings-section" aria-labelledby="settings-connect-title">
        <div class="settings-section__heading">
          <h2 id="settings-connect-title">Orchard Connect</h2>
          <p>Pair a phone on your LAN and approve it before it can control playback.</p>
        </div>

        <div class="settings-connect">
          <details class="settings-connect__qr-panel">
            <summary>
              <q-icon name="qr_code_2" />
              <span>Show camera QR</span>
            </summary>
            <div class="settings-connect__qr" v-html="orchardConnect.qrSvg"></div>
          </details>
          <div class="settings-connect__copy">
            <span>Pairing link</span>
            <p>{{ orchardConnect.serverUrl || 'Waiting for bridge connection.' }}</p>
            <div class="settings-connect__actions">
              <button type="button" class="settings-button" :disabled="!socket?.connected" @click="loadOrchardConnectInfo({ refresh: true })">
                <q-icon name="qr_code_2" />
                New QR
              </button>
              <button type="button" class="settings-button" :disabled="!orchardConnect.pairUrl" @click="copyOrchardConnectLink">
                <q-icon name="content_copy" />
                Copy app link
              </button>
              <button type="button" class="settings-button" :disabled="!orchardConnect.webPairUrl" @click="copyOrchardConnectWebLink">
                <q-icon name="content_copy" />
                Copy camera link
              </button>
            </div>
            <p v-if="orchardConnectPairingMessage" class="settings-connect__message">{{ orchardConnectPairingMessage }}</p>
            <details v-if="orchardConnect.altWebPairUrls?.length" class="settings-connect__alternates">
              <summary>Phone can't reach this address?</summary>
              <p>
                This machine has more than one network address. If the QR code does not connect, open one
                of these on the phone instead.
              </p>
              <button
                v-for="url in orchardConnect.altWebPairUrls"
                :key="url"
                type="button"
                class="settings-button settings-connect__alternate"
                @click="copyOrchardConnectAltLink(url)"
              >
                <q-icon name="content_copy" />
                {{ url }}
              </button>
            </details>
          </div>
        </div>

        <div v-if="orchardConnect.pending.length" class="settings-connect-list">
          <div
            v-for="request in orchardConnect.pending"
            :key="request.id"
            class="settings-action-row settings-action-row--connect"
          >
            <div class="settings-row__copy">
              <span>{{ request.name }}</span>
              <p>Approve this phone before it can control Orchard.</p>
            </div>
            <div class="settings-connect__actions">
              <button type="button" class="settings-button" @click="approveOrchardConnectPairing(request.id)">Approve</button>
              <button type="button" class="settings-link-button settings-link-button--danger" @click="rejectOrchardConnectPairing(request.id)">Reject</button>
            </div>
          </div>
        </div>

        <div class="settings-connect-list">
          <div
            v-for="device in orchardConnect.devices"
            :key="device.id"
            class="settings-action-row settings-action-row--connect"
          >
            <div class="settings-row__copy">
              <span>{{ device.name }}</span>
              <p>{{ device.connected ? 'Connected now' : 'Not connected' }}</p>
            </div>
            <button type="button" class="settings-link-button settings-link-button--danger" @click="revokeOrchardConnectDevice(device.id)">
              Revoke
            </button>
          </div>
          <div v-if="!orchardConnect.devices.length && !orchardConnect.pending.length" class="settings-connect-empty">
            No phones paired yet.
          </div>
        </div>
      </section>

      <section id="settings-application" class="settings-section" aria-labelledby="settings-application-title">
        <div class="settings-section__heading">
          <h2 id="settings-application-title">Application</h2>
          <p>Orchard {{ currentReleaseLabel }}</p>
        </div>

        <div class="settings-row">
          <div class="settings-row__copy">
            <label for="settings-close-to-tray">Close to tray</label>
            <p>Closing the window keeps Orchard playing in the tray instead of quitting. Quit from the tray menu.</p>
          </div>
          <q-toggle id="settings-close-to-tray" v-model="closeToTrayEnabled" color="primary" aria-label="Close to tray" />
        </div>

        <div class="settings-action-row">
          <div class="settings-row__copy">
            <span>Updates</span>
            <p>{{ updateState.content?.message || updateState.message || 'Check for app and artist page updates.' }}</p>
          </div>
          <button type="button" class="settings-button" :disabled="updateState.status === 'checking'" @click="openUpdateDialog({ check: true, checkContent: true })">
            <q-icon name="system_update_alt" />
            Updates
          </button>
        </div>

        <div class="settings-row">
          <div class="settings-row__copy">
            <label for="settings-beta-channel">Beta channel</label>
            <p>Get beta builds from GitHub releases instead of the regular channel. Beta builds may be less stable.</p>
          </div>
          <q-toggle id="settings-beta-channel" v-model="updateChannelBetaToggle" color="primary" aria-label="Beta channel" />
        </div>

        <div class="settings-actions">
          <button type="button" class="settings-link-button" @click="openChangelog">
            <q-icon name="new_releases" />
            Release notes
          </button>
          <button type="button" class="settings-link-button" @click="aboutDialogOpen = true">
            <q-icon name="info_outline" />
            About Orchard
          </button>
          <button type="button" class="settings-link-button settings-link-button--danger" @click="resetUserPreferences">
            <q-icon name="restart_alt" />
            Restore defaults
          </button>
        </div>
      </section>

      <DiagnosticsSection :app="app" />
      <BackupRestoreSection :app="app" />
    </div>
  </main>
</template>
