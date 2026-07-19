<script>
import { computed, onMounted } from 'vue';
import CompactSettingsMenu from '../controls/CompactSettingsMenu.vue';

export default {
  name: 'PlayerBar',
  components: {
    CompactSettingsMenu
  },
  props: { app: { type: Object, required: true } },
  setup(props) {
    const bitrateLabel = (track) => {
      const value = Number(track?.bitrate || 0);
      if (!Number.isFinite(value) || value <= 0) return '';
      return String(Math.round(value >= 1000 ? value / 1000 : value));
    };

    const currentOutputDeviceLabel = computed(() => {
      if (props.app.audioOutputLoading?.value) return 'Loading...';
      const activeId = props.app.audioEngineConfig.value?.outputDeviceId || 'default';
      const dev = props.app.audioOutputDevices.value?.find(d => d.deviceId === activeId);
      return dev ? dev.label : 'System default';
    });

    const volumeIcon = computed(() => {
      const vol = props.app.volume.value;
      if (vol === 0) return 'volume_off';
      if (vol < 0.3) return 'volume_mute';
      if (vol < 0.7) return 'volume_down';
      return 'volume_up';
    });

    let savedVolume = 0.85;
    const toggleMute = () => {
      if (props.app.volume.value > 0) {
        savedVolume = props.app.volume.value;
        props.app.volume.value = 0;
      } else {
        props.app.volume.value = savedVolume || 0.85;
      }
    };

    const handleListeningPartyClick = () => {
      if (props.app.listeningParty.value?.status === 'connected') {
        props.app.rightPanelMode.value = 'party';
      } else {
        props.app.listeningPartyDialogOpen.value = true;
      }
    };

    onMounted(() => {
      props.app.loadAudioOutputDevices?.();
    });

    return {
      ...props.app,
      app: props.app,
      bitrateLabel,
      currentOutputDeviceLabel,
      volumeIcon,
      toggleMute,
      handleListeningPartyClick,
      sleepTimerActive: props.app.sleepTimerActive,
      sleepTimerStatus: props.app.sleepTimerStatus,
      openSleepTimerSettings: props.app.openSleepTimerSettings,
      openFullscreenPlayer: props.app.openFullscreenPlayer,
      audioOutputLoading: props.app.audioOutputLoading
    };
  }
};
</script>

<template>
  <q-footer
    class="player-bar"
    :class="{ 'player-bar--empty': !activeTrack }"
    :style="playerBarStyle"
    :aria-label="activeTrack ? `Player: ${activeTrack.title}` : 'Player: nothing playing'"
  >
    <!-- Left Section: Track / Art Area -->
    <div class="player-section player-section--left">
      <button
        type="button"
        class="mini-cover-button"
        :title="activeTrack ? `Open ${activeTrack.album || activeTrack.title}` : 'Album'"
        :aria-label="activeTrack ? `Open ${activeTrack.album || activeTrack.title}` : 'Album artwork'"
        :disabled="!activeTrack"
        @click="openTrackAlbum"
        @keydown="onSongActionKeydown($event, activeTrack, browseDetail)"
        @contextmenu="shareActiveTrackSongLink"
      >
        <q-img v-if="activeTrack?.thumbnail" :src="activeTrack.thumbnail" class="mini-cover" />
        <span v-else class="mini-cover mini-cover--empty">
          <q-icon name="music_note" />
        </span>
      </button>

      <div class="player-track__info">
        <div class="player-track__title-row">
          <button
            type="button"
            class="mini-title mini-link"
            :disabled="!activeTrack"
            :aria-label="activeTrack ? `Open ${activeTrack.album || activeTrack.title}` : 'Nothing playing'"
            @click="openTrackAlbum"
            @keydown="onSongActionKeydown($event, activeTrack, browseDetail)"
            @contextmenu="shareActiveTrackSongLink"
          >
            {{ activeTrack?.title || 'Nothing playing' }}
          </button>
          <ExplicitBadge :explicit="activeTrack?.explicit" />

          <!-- YouTube Liked Songs Button -->
          <q-btn
            v-if="activeTrack"
            flat
            round
            dense
            size="sm"
            class="track-action-btn"
            :icon="isActiveTrackLiked ? 'star' : 'star_border'"
            :loading="activeTrackLikePending"
            :disable="!canToggleActiveTrackLike"
            :title="isActiveTrackLiked ? 'Remove from Liked Songs' : 'Add to Liked Songs'"
            :aria-label="isActiveTrackLiked ? 'Remove from Liked Songs' : 'Add to Liked Songs'"
            @click="toggleActiveTrackLike"
          />
          <!-- More / Context Menu Button -->
          <q-btn
            v-if="activeTrack"
            flat
            round
            dense
            size="sm"
            class="track-action-btn"
            icon="more_horiz"
            title="Song Actions"
            aria-label="Song Actions"
            @click="openSongActionMenu(activeTrack, $event)"
          />
        </div>

        <button
          v-if="activeTrack"
          type="button"
          class="mini-subtitle mini-link artist-link"
          :disabled="!canOpenActiveTrackArtist()"
          :aria-label="activeArtist ? `Open artist page for ${activeArtist}` : 'Artist'"
          @click="openTrackArtist"
          @keydown="onSongActionKeydown($event, activeTrack, browseDetail)"
          @contextmenu="shareActiveTrackSongLink"
        >
          {{ activeArtist || 'Artist' }}
        </button>
        <div v-else class="mini-subtitle">Choose a song to begin</div>

        <!-- Album Row -->
        <div v-if="activeTrack" class="mini-album-row">
          <q-icon name="album" size="14px" />
          <span class="mini-album-name">{{ activeTrack.album || 'Unknown Album' }}</span>
        </div>

        <!-- Quality Pills Row -->
        <div v-if="activeTrack" class="quality-pills-row">
          <span v-if="bitrateLabel(activeTrack)" class="quality-pill format-pill">
            {{ bitrateLabel(activeTrack) }} kbps
          </span>
          <span class="quality-pill duration-pill">{{ durationLabel }}</span>
        </div>
      </div>
    </div>

    <!-- Vertical Separator -->
    <div class="player-separator" />

    <!-- Center Section: Transport / Progress Area -->
    <div class="player-section player-section--center">
      <div class="transport-buttons">
        <!-- Shuffle -->
        <q-btn
          flat
          round
          dense
          class="player-control player-control--secondary"
          icon="shuffle"
          :color="shuffleEnabled ? 'primary' : undefined"
          :disable="!queue.length"
          :title="shuffleEnabled ? 'Shuffle on' : 'Shuffle off'"
          aria-label="Shuffle"
          @click="toggleShuffle"
        />

        <!-- Skip Previous -->
        <q-btn
          flat
          round
          dense
          class="player-control"
          icon="skip_previous"
          :disable="!activeTrack || buffering"
          aria-label="Previous song"
          @click="playPrevious"
        />

        <!-- Play / Pause (Large center button) -->
        <q-btn
          round
          color="primary"
          class="player-control--play"
          :loading="buffering"
          :disable="!activeTrack"
          :icon="isPlaying ? 'pause' : 'play_arrow'"
          :aria-label="isPlaying ? 'Pause playback' : 'Start playback'"
          @click="togglePlayback"
        />

        <!-- Skip Next -->
        <q-btn
          flat
          round
          dense
          class="player-control"
          icon="skip_next"
          :disable="(!queue.length && (!activeTrack || repeatMode === 'off')) || buffering"
          aria-label="Next song"
          @click="playNext({ skipRepeatOne: true })"
        />

        <!-- Repeat -->
        <q-btn
          flat
          round
          dense
          class="player-control player-control--secondary"
          :icon="repeatMode === 'one' ? 'repeat_one' : 'repeat'"
          :color="repeatMode !== 'off' ? 'primary' : undefined"
          :title="repeatModeTitle()"
          aria-label="Repeat mode"
          @click="cycleRepeatMode"
        />

      </div>

      <!-- Progress Slider -->
      <div class="progress-wrap">
        <span class="progress-time">{{ formatTime(displayedTime) }}</span>
        <div class="progress-slider" :style="crossfadeProgressStyle">
          <q-slider
            v-model="seekPosition"
            :min="0"
            :max="duration || 1"
            :step="1"
            color="primary"
            :disable="!activeTrack || activeTrackIsLive"
            aria-label="Playback position"
            @update:model-value="onSeekPositionChange"
            @change="seek"
            @pan="onSeekPan"
          />
        </div>
        <span class="progress-time">{{ durationLabel }}</span>
      </div>
    </div>

    <!-- Vertical Separator -->
    <div class="player-separator" />

    <!-- Right Section: Output / Party / Volume / Settings Area -->
    <div class="player-section player-section--right">
      <div class="right-top-row">
        <!-- Output Device Selection Pill -->
        <q-btn flat dense class="output-device-pill" aria-label="Select output device">
          <q-icon name="headphones" size="16px" class="q-mr-xs" />
          <span class="output-device-label">{{ currentOutputDeviceLabel }}</span>
          <q-icon name="expand_more" size="16px" class="q-ml-xs" />
          <q-menu dark anchor="bottom right" self="top right" class="player-popup-menu">
            <q-list dark style="min-width: 180px">
              <q-item v-if="audioOutputLoading" disable>
                <q-item-section class="text-grey text-caption">Loading devices...</q-item-section>
              </q-item>
              <q-item v-else-if="!audioOutputDevices || audioOutputDevices.length === 0" disable>
                <q-item-section class="text-grey text-caption">No output devices found</q-item-section>
              </q-item>
              <template v-else>
                <q-item
                  v-for="device in audioOutputDevices"
                  :key="device.deviceId"
                  clickable
                  v-close-popup
                  :active="audioEngineConfig.outputDeviceId === device.deviceId"
                  @click="audioEngineConfig.outputDeviceId = device.deviceId"
                >
                  <q-item-section>{{ device.label }}</q-item-section>
                </q-item>
              </template>
            </q-list>
          </q-menu>
        </q-btn>

        <!-- Listening Party Button -->
        <q-btn
          flat
          round
          dense
          class="player-control player-control--secondary"
          :icon="listeningParty.status === 'connected' ? 'groups' : 'group_add'"
          :color="listeningParty.status === 'connected' ? 'primary' : (listeningParty.status === 'connecting' ? 'warning' : undefined)"
          title="Listening Party"
          aria-label="Listening Party"
          @click="handleListeningPartyClick"
        >
          <q-badge v-if="listeningParty.status === 'connected' && listeningParty.peers?.length > 0" color="orange" floating transparent>
            {{ listeningParty.peers.length }}
          </q-badge>
        </q-btn>

      </div>

      <div class="right-bottom-row">
        <!-- Volume controls -->
        <div class="volume-wrap">
          <q-btn
            flat
            round
            dense
            class="volume-btn"
            :icon="volumeIcon"
            title="Mute/Unmute"
            aria-label="Mute or unmute volume"
            @click="toggleMute"
          />
          <q-slider
            v-model="volume"
            :min="0"
            :max="1"
            :step="0.01"
            color="primary"
            class="volume-slider"
            aria-label="Volume level"
          />
          <span class="volume-percentage">{{ Math.round(volume * 100) }}%</span>
        </div>

        <!-- Fullscreen Player -->
        <q-btn
          flat
          round
          dense
          class="player-control player-control--secondary player-fullscreen-button"
          icon="open_in_full"
          title="Fullscreen Player"
          aria-label="Open fullscreen player"
          :disable="!activeTrack"
          @click="openFullscreenPlayer"
        />

        <!-- Sleep Timer -->
        <q-btn
          flat
          round
          dense
          class="player-control player-control--secondary player-sleep-timer-button"
          :class="{ 'player-sleep-timer-button--active': sleepTimerActive }"
          icon="bedtime"
          :title="sleepTimerActive ? `Sleep Timer: ${sleepTimerStatus}` : 'Sleep Timer'"
          :aria-label="sleepTimerActive ? `Sleep timer: ${sleepTimerStatus}. Open timer settings` : 'Open sleep timer settings'"
          @click="openSleepTimerSettings"
        />

        <!-- Settings Button (opens the NEW compact settings menu) -->
        <q-btn
          flat
          round
          dense
          class="player-control player-control--secondary settings-menu-btn"
          icon="menu"
          title="Settings"
          aria-label="Quick Settings"
        >
          <q-menu anchor="top right" self="bottom right" class="compact-settings-menu-popup player-popup-menu">
            <CompactSettingsMenu :app="app" />
          </q-menu>
        </q-btn>
      </div>
    </div>
  </q-footer>
</template>
