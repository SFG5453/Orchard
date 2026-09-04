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
import { computed } from 'vue';
import CompactSettingsMenu from '../controls/CompactSettingsMenu.vue';
import { createVolumeWheelHandler } from '../../app/playback/volumeWheel.js';
import { bitrateLabel } from '../../app/playback/trackQuality.js';

/*
 * Canopy's titlebar. Unlike Grove, this bar owns the transport and the
 * now-playing readout, which is why the Canopy preset drops the bottom player
 * bar entirely - every control that lived down there is either up here or one
 * click into the overflow menu.
 */
export default {
  name: 'CanopyTitlebar',
  components: { CompactSettingsMenu },
  props: { app: { type: Object, required: true } },
  setup(props) {
    const volumeIcon = computed(() => {
      const value = props.app.volume.value;
      if (value === 0) return 'volume_off';
      if (value < 0.3) return 'volume_mute';
      if (value < 0.7) return 'volume_down';
      return 'volume_up';
    });

    const onVolumeWheel = createVolumeWheelHandler(props.app.volume);

    const outputDeviceLabel = computed(() => {
      if (props.app.audioOutputLoading?.value) return 'Loading...';
      const activeId = props.app.audioEngineConfig.value?.outputDeviceId || 'default';
      return props.app.audioOutputDevices.value
        ?.find((device) => device.deviceId === activeId)?.label || 'System default';
    });

    function handleListeningPartyClick() {
      if (props.app.listeningParty.value?.status === 'connected') {
        props.app.rightPanelMode.value = 'party';
      } else {
        props.app.listeningPartyDialogOpen.value = true;
      }
    }


    return {
      ...props.app,
      app: props.app,
      bitrateLabel,
      handleListeningPartyClick,
      onVolumeWheel,
      outputDeviceLabel,
      volumeIcon
    };
  }
};
</script>

<template>
  <header class="canopy-bar" :class="{ 'canopy-bar--native': nativeTitlebar }" data-tauri-drag-region="deep">
    <div class="canopy-bar__brand">
      <img class="canopy-bar__mark" :src="orchardLogoUrl" alt="Orchard" />
    </div>

    <div class="canopy-bar__transport">
      <q-btn
        flat
        round
        dense
        icon="shuffle"
        class="canopy-bar__button"
        :color="shuffleEnabled ? 'primary' : undefined"
        :disable="!queue.length"
        :title="shuffleEnabled ? 'Shuffle on' : 'Shuffle off'"
        aria-label="Shuffle"
        @click="toggleShuffle"
      />
      <q-btn
        flat
        round
        dense
        icon="skip_previous"
        class="canopy-bar__button"
        :disable="!activeTrack || buffering"
        aria-label="Previous song"
        @click="playPrevious"
      />
      <q-btn
        flat
        round
        dense
        class="canopy-bar__button canopy-bar__button--play"
        :loading="buffering"
        :disable="!activeTrack"
        :icon="isPlaying ? 'pause' : 'play_arrow'"
        :aria-label="isPlaying ? 'Pause playback' : 'Start playback'"
        @click="togglePlayback"
      />
      <q-btn
        flat
        round
        dense
        icon="skip_next"
        class="canopy-bar__button"
        :disable="(!queue.length && (!activeTrack || repeatMode === 'off')) || buffering"
        aria-label="Next song"
        @click="playNext({ skipRepeatOne: true })"
      />
      <q-btn
        flat
        round
        dense
        class="canopy-bar__button"
        :icon="repeatMode === 'one' ? 'repeat_one' : 'repeat'"
        :color="repeatMode !== 'off' ? 'primary' : undefined"
        :title="repeatModeTitle()"
        aria-label="Repeat mode"
        @click="cycleRepeatMode"
      />
    </div>

    <!-- The readout: artwork, two lines of copy, and a scrub track along the bottom. -->
    <div class="canopy-readout" :class="{ 'canopy-readout--empty': !activeTrack }">
      <button
        type="button"
        class="canopy-readout__art"
        :disabled="!activeTrack"
        :aria-label="activeTrack ? `Open ${activeTrack.album || activeTrack.title}` : 'Album artwork'"
        @click="openTrackAlbum"
        @contextmenu="shareActiveTrackSongLink"
      >
        <q-img v-if="activeTrack?.thumbnail" :src="activeTrack.thumbnail" class="canopy-readout__image" />
        <span v-else class="canopy-readout__image canopy-readout__image--empty">
          <q-icon name="music_note" />
        </span>
      </button>

      <div class="canopy-readout__copy">
        <div class="canopy-readout__title-row">
          <button
            type="button"
            class="canopy-readout__title canopy-readout__link"
            :disabled="!activeTrack"
            :aria-label="activeTrack ? `Open ${activeTrack.album || activeTrack.title}` : 'Nothing playing'"
            @click="openTrackAlbum"
            @keydown="onSongActionKeydown($event, activeTrack, browseDetail)"
            @contextmenu="shareActiveTrackSongLink"
          >
            {{ activeTrack?.title || 'Nothing playing' }}
          </button>
          <ExplicitBadge :explicit="activeTrack?.explicit" />
        </div>

        <div class="canopy-readout__meta">
          <template v-if="activeTrack">
            <button
              type="button"
              class="canopy-readout__link"
              :disabled="!canOpenActiveTrackArtist()"
              :aria-label="activeArtist ? `Open artist page for ${activeArtist}` : 'Artist'"
              @click="openTrackArtist"
              @contextmenu="shareActiveTrackSongLink"
            >
              {{ activeArtist || 'Artist' }}
            </button>
            <span class="canopy-readout__dash" aria-hidden="true">&mdash;</span>
            <button
              type="button"
              class="canopy-readout__link"
              :disabled="!activeTrack.album"
              :aria-label="activeTrack.album ? `Open ${activeTrack.album}` : 'Album'"
              @click="openTrackAlbum"
              @contextmenu="shareActiveTrackSongLink"
            >
              {{ activeTrack.album || 'Unknown Album' }}
            </button>
            <span v-if="bitrateLabel(activeTrack)" class="canopy-readout__bitrate">
              {{ bitrateLabel(activeTrack) }} kbps
            </span>
          </template>
          <span v-else>Choose a song to begin</span>
        </div>

      </div>

      <!-- Song actions live inside the readout: they act on the track it shows,
           and out in the bar they were an ungrouped button between clusters. -->
      <div class="canopy-readout__actions">
        <q-btn
          flat
          round
          dense
          size="sm"
          class="canopy-bar__button"
          :icon="isActiveTrackLiked ? 'star' : 'star_border'"
          :loading="activeTrackLikePending"
          :disable="!canToggleActiveTrackLike"
          :title="isActiveTrackLiked ? 'Remove from Liked Songs' : 'Add to Liked Songs'"
          :aria-label="isActiveTrackLiked ? 'Remove from Liked Songs' : 'Add to Liked Songs'"
          @click="toggleActiveTrackLike"
        />
        <q-btn
          flat
          round
          dense
          size="sm"
          icon="more_horiz"
          class="canopy-bar__button"
          :disable="!activeTrack"
          title="Song actions"
          aria-label="Song actions"
          @click="openSongActionMenu(activeTrack, $event)"
        />
      </div>

      <!-- Scrubber sits along the bottom of the entire readout -->
      <div class="canopy-readout__progress" :style="crossfadeProgressStyle">
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
    </div>

    <div class="canopy-bar__utility">
      <!-- The slider is a flyout rather than 104px of permanent bar furniture.
           It is absolutely positioned so revealing it never reflows the bar. -->
      <div class="canopy-bar__volume" @wheel.prevent="onVolumeWheel">
        <q-btn
          flat
          round
          dense
          :icon="volumeIcon"
          class="canopy-bar__button"
          title="Volume (scroll to change)"
          aria-label="Volume"
        />
        <div class="canopy-bar__volume-flyout">
          <q-slider
            v-model="volume"
            :min="0"
            :max="1"
            :step="0.01"
            color="primary"
            class="canopy-bar__volume-slider"
            aria-label="Volume level"
          />
        </div>
      </div>

      <q-btn
        flat
        round
        dense
        icon="open_in_full"
        class="canopy-bar__button"
        :disable="!activeTrack"
        title="Fullscreen player"
        aria-label="Open fullscreen player"
        @click="openFullscreenPlayer"
      />

      <q-btn
        flat
        round
        dense
        icon="queue_music"
        class="canopy-bar__button player-right-panel-button"
        title="Queue"
        aria-label="Open queue panel"
        @click="openRightPanel('queue')"
      />

      <q-btn
        flat
        round
        dense
        icon="lyrics"
        class="canopy-bar__button player-right-panel-button"
        :disable="!activeTrack"
        title="Lyrics"
        aria-label="Open lyrics panel"
        @click="openRightPanel('lyrics')"
      />

      <!-- Everything the bottom bar used to hold that does not earn a permanent slot. -->
      <q-btn flat round dense icon="tune" class="canopy-bar__button" title="Playback options" aria-label="Playback options">
        <q-menu dark anchor="bottom right" self="top right" class="player-popup-menu canopy-overflow-menu">
          <q-list dark style="min-width: 236px">
            <q-item-label header>Output</q-item-label>
            <q-item clickable>
              <q-item-section>{{ outputDeviceLabel }}</q-item-section>
              <q-item-section side>
                <q-icon name="chevron_right" />
              </q-item-section>
              <q-menu dark anchor="top end" self="top start">
                <q-list dark style="min-width: 180px">
                  <q-item v-if="audioOutputLoading" disable>
                    <q-item-section class="text-grey text-caption">Loading devices...</q-item-section>
                  </q-item>
                  <q-item v-else-if="!audioOutputDevices || audioOutputDevices.length === 0" disable>
                    <q-item-section class="text-grey text-caption">No output devices found</q-item-section>
                  </q-item>
                  <q-item
                    v-for="device in audioOutputDevices"
                    v-else
                    :key="device.deviceId"
                    v-close-popup
                    clickable
                    :active="audioEngineConfig.outputDeviceId === device.deviceId"
                    @click="audioEngineConfig.outputDeviceId = device.deviceId"
                  >
                    <q-item-section>{{ device.label }}</q-item-section>
                  </q-item>
                </q-list>
              </q-menu>
            </q-item>

            <q-separator dark />

            <q-item v-close-popup clickable @click="handleListeningPartyClick">
              <q-item-section avatar>
                <q-icon :name="listeningParty.status === 'connected' ? 'groups' : 'group_add'" />
              </q-item-section>
              <q-item-section>Listening party</q-item-section>
            </q-item>
            <q-item v-close-popup clickable @click="openSleepTimerSettings">
              <q-item-section avatar>
                <q-icon name="bedtime" />
              </q-item-section>
              <q-item-section>{{ sleepTimerActive ? `Sleep timer: ${sleepTimerStatus}` : 'Sleep timer' }}</q-item-section>
            </q-item>

          </q-list>
        </q-menu>
      </q-btn>

      <q-btn flat round dense icon="menu" class="canopy-bar__button" title="Quick settings" aria-label="Quick settings">
        <q-menu anchor="bottom right" self="top right" class="compact-settings-menu-popup player-popup-menu">
          <CompactSettingsMenu :app="app" />
        </q-menu>
      </q-btn>
    </div>

    <div v-if="!nativeTitlebar" class="canopy-bar__controls">
      <button type="button" title="Minimize" aria-label="Minimize" @click="minimizeWindow">
        <q-icon name="remove" />
      </button>
      <button type="button" title="Maximize" aria-label="Maximize" @click="toggleMaximizeWindow">
        <q-icon name="crop_square" />
      </button>
      <button type="button" title="Close" aria-label="Close" class="canopy-bar__close" @click="closeWindow">
        <q-icon name="close" />
      </button>
    </div>
  </header>
</template>

<style src="../../styles/layout-canopy.css"></style>
