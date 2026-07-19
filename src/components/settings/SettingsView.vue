<script>
import AudioEngineSection from './AudioEngineSection.vue';
import BackupRestoreSection from './BackupRestoreSection.vue';
import DiagnosticsSection from './DiagnosticsSection.vue';
import LastfmSection from './LastfmSection.vue';
import SongCacheSection from './SongCacheSection.vue';
import SetupGuideSection from './SetupGuideSection.vue';
import ArtistPacksSection from './ArtistPacksSection.vue';

export default {
  name: 'SettingsView',
  components: { AudioEngineSection, BackupRestoreSection, DiagnosticsSection, LastfmSection, SongCacheSection, SetupGuideSection, ArtistPacksSection },
  props: { app: { type: Object, required: true } },
  setup(props) {
    return { ...props.app, app: props.app };
  }
};
</script>

<template>
  <main class="settings-view">
    <nav class="settings-index" aria-label="Settings sections">
      <a href="#settings-setup">
        <q-icon name="checklist" />
        <span>Setup</span>
      </a>
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
      <SetupGuideSection :app="app" />

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

        <div class="settings-row settings-row--options" :class="{ 'settings-row--disabled': !discordRpcEnabled }">
          <div class="settings-row__copy">
            <label id="settings-discord-name-label">Card name</label>
            <p>Choose the name shown inside the full Discord activity card.</p>
          </div>
          <div class="settings-option-group" role="group" aria-labelledby="settings-discord-name-label">
            <button
              v-for="option in discordRpcActivityNameOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': discordRpcActivityName === option.value }"
              :aria-pressed="discordRpcActivityName === option.value"
              :disabled="!discordRpcEnabled"
              @click="discordRpcActivityName = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <LastfmSection :app="app" />

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
