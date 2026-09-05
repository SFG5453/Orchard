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
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { createVolumeWheelHandler } from '../../app/playback/volumeWheel.js';
import { bitrateLabel } from '../../app/playback/trackQuality.js';
import { FALLBACK_ARTWORK_PALETTE } from '../animated-background/useArtworkPalette.js';
import FullscreenArtworkStage from './FullscreenArtworkStage.vue';
import FullscreenLyrics from './FullscreenLyrics.vue';
import FullscreenVirtualQueue from './FullscreenVirtualQueue.vue';
import {
  createFullscreenEnvironment,
  fullscreenArtworkMotion,
  fullscreenEnvironmentStyle,
  interpolateFullscreenEnvironment
} from './fullscreenPlayerVisuals.js';

export default {
  name: 'FullscreenPlayer',
  components: { FullscreenArtworkStage, FullscreenLyrics, FullscreenVirtualQueue },
  props: { app: { type: Object, required: true } },
  setup(props) {
    const app = props.app;
    const closeButtonRef = ref(null);
    const queueOpen = ref(false);
    const palettes = ref(new Map());
    const onVolumeWheel = createVolumeWheelHandler(app.volume);
    const mix = computed(() => app.smartCrossfadeMix.value);

    const queueCount = computed(() => {
      if (app.continuousQueueEnabled?.value) {
        return app.continuousQueue?.value?.length || 0;
      }
      return app.queue?.value?.length || 0;
    });

    const volumeIcon = computed(() => {
      const volume = app.volume.value;
      if (volume === 0) return 'volume_off';
      if (volume < 0.3) return 'volume_mute';
      if (volume < 0.7) return 'volume_down';
      return 'volume_up';
    });

    const outgoingArtwork = computed(() => app.highResolutionArtworkImage(
      mix.value.visible
        ? mix.value.from?.artwork
        : app.fullscreenArtworkImage.value || app.nowArtworkImage.value
    ));
    const incomingArtwork = computed(() => app.highResolutionArtworkImage(
      mix.value.visible ? mix.value.to?.artwork : ''
    ));

    const fallbackPalette = computed(() => {
      const accent = app.playerBarAccent.value;
      if (!accent?.rgb) return FALLBACK_ARTWORK_PALETTE;
      return {
        dominant: accent.deepRgb || accent.rgb,
        vibrant: accent.rgb,
        muted: accent.softRgb || accent.rgb,
        darkVibrant: accent.deepRgb || accent.rgb,
        darkMuted: (accent.deepRgb || accent.rgb).map((value) => value * 0.55)
      };
    });

    const beatPulse = computed(() => {
      if (!mix.value.visible || !['mix-start', 'active-mix', 'handoff'].includes(mix.value.phase)) return 0;
      const bpm = mix.value.progress >= mix.value.handoffProgress
        ? mix.value.toBpm || mix.value.fromBpm
        : mix.value.fromBpm || mix.value.toBpm;
      const beats = mix.value.transitionBeats ||
        ((mix.value.fadeDurationMs || 0) / 1000) * (Number(bpm) || 0) / 60;
      if (!Number.isFinite(beats) || beats <= 0) return 0;
      const beatPosition = (mix.value.progress * beats) % 1;
      return Math.exp(-beatPosition * 7);
    });

    const artworkMotion = computed(() => fullscreenArtworkMotion(mix.value, beatPulse.value));
    const fullscreenStyle = computed(() => {
      const fromPalette = palettes.value.get(outgoingArtwork.value) || fallbackPalette.value;
      const toPalette = palettes.value.get(incomingArtwork.value) || fromPalette;
      const fromEnvironment = createFullscreenEnvironment(fromPalette);
      const toEnvironment = createFullscreenEnvironment(toPalette);
      const environment = interpolateFullscreenEnvironment(
        fromEnvironment,
        toEnvironment,
        artworkMotion.value['--fs-palette-weight']
      );
      return {
        ...app.fullscreenPlayerStyle.value,
        ...fullscreenEnvironmentStyle(environment),
        ...artworkMotion.value
      };
    });

    const remainingLabel = computed(() => {
      if (app.activeTrackIsLive.value) return 'Live';
      return `-${app.formatTime(Math.max(0, app.duration.value - app.displayedTime.value))}`;
    });

    const mixStatus = computed(() => {
      if (!mix.value.visible) return '';
      if (mix.value.phase === 'preparing') return `Up next: ${mix.value.to.title}`;
      if (mix.value.phase === 'complete') return `Now playing ${mix.value.to.title}`;
      if (mix.value.phase === 'handoff') return `Handing off to ${mix.value.to.title}`;
      return `Smart Crossfade mixing ${mix.value.from.title} into ${mix.value.to.title}`;
    });

    let savedVolume = 0.85;
    function toggleMute() {
      if (app.volume.value > 0) {
        savedVolume = app.volume.value;
        app.volume.value = 0;
      } else {
        app.volume.value = savedVolume || 0.85;
      }
    }

    function onPalette({ palette, url }) {
      if (!url || !palette) return;
      const next = new Map(palettes.value);
      next.delete(url);
      next.set(url, palette);
      while (next.size > 6) next.delete(next.keys().next().value);
      palettes.value = next;
    }

    async function toggleLyrics() {
      queueOpen.value = false;
      app.fullscreenLyricsVisible.value = !app.fullscreenLyricsVisible.value;
      if (!app.fullscreenLyricsVisible.value) return;
      await nextTick();
      void app.scrollActiveLyric({ force: true });
      document.querySelector('.fullscreen-player__lyrics-scroll')?.focus?.({ preventScroll: true });
    }

    function openTrackAlbumFromFullscreen() {
      app.openTrackAlbum();
      void app.closeFullscreenPlayer();
    }

    function openTrackArtistFromFullscreen() {
      app.openTrackArtist();
      void app.closeFullscreenPlayer();
    }

    function onFullscreenKeydown(event) {
      if (event.key !== 'Escape') return;
      if (queueOpen.value) {
        queueOpen.value = false;
        return;
      }
      void app.closeFullscreenPlayer();
    }

    onMounted(() => {
      closeButtonRef.value?.focus({ preventScroll: true });
      window.addEventListener('keydown', onFullscreenKeydown);
      if (app.fullscreenLyricsVisible.value) void app.scrollActiveLyric();
    });

    onBeforeUnmount(() => {
      window.removeEventListener('keydown', onFullscreenKeydown);
    });

    return {
      ...app,
      bitrateLabel,
      closeButtonRef,
      fullscreenStyle,
      mix,
      mixStatus,
      onPalette,
      onVolumeWheel,
      openTrackAlbumFromFullscreen,
      openTrackArtistFromFullscreen,
      queueCount,
      queueOpen,
      remainingLabel,
      toggleLyrics,
      toggleMute,
      volumeIcon
    };
  }
};
</script>

<template>
  <section
    ref="fullscreenPlayerRef"
    class="fullscreen-player"
    :class="[
      `fullscreen-player--mix-${mix.visible ? mix.phase : 'idle'}`,
      {
        'fullscreen-player--immersive': immersiveBackgroundsEnabled && (immersiveArtworkImage || immersiveArtworkVideo),
        'fullscreen-player--lyrics-hidden': !fullscreenLyricsVisible
      }
    ]"
    :style="fullscreenStyle"
    role="dialog"
    aria-modal="true"
    aria-label="Fullscreen player"
  >
    <div class="fullscreen-player__color-field" aria-hidden="true">
      <i class="fullscreen-player__color-field-primary" />
      <i class="fullscreen-player__color-field-secondary" />
    </div>

    <header class="fullscreen-player__header">
      <div class="fullscreen-player__context" aria-hidden="true">
        <span>Orchard</span>
        <i />
        <span>Now Playing</span>
      </div>
      <button
        ref="closeButtonRef"
        type="button"
        class="fullscreen-player__close"
        title="Exit fullscreen player (Esc)"
        aria-label="Exit fullscreen player"
        @click="closeFullscreenPlayer"
      >
        <q-icon name="close_fullscreen" />
      </button>
    </header>

    <main class="fullscreen-player__stage">
      <section class="fullscreen-player__left" aria-label="Now playing controls">
        <FullscreenArtworkStage :app="app" :mix="mix" @palette="onPalette" />

        <div class="fullscreen-player__transport">
          <div class="fullscreen-player__progress">
            <span class="fullscreen-player__time">{{ formatTime(displayedTime) }}</span>
            <div class="progress-slider" :style="crossfadeProgressStyle">
              <q-slider
                v-model="seekPosition"
                :min="0"
                :max="duration || 1"
                :step="1"
                color="white"
                :disable="activeTrackIsLive"
                aria-label="Song progress"
                @update:model-value="onSeekPositionChange"
                @change="seek"
                @pan="onSeekPan"
              />
            </div>
            <span class="fullscreen-player__time fullscreen-player__time--duration" :title="durationLabel">
              {{ remainingLabel }}
            </span>
          </div>

          <div class="fullscreen-player__control-row">
            <div class="fullscreen-player__control-cluster fullscreen-player__control-cluster--start">
              <button
                type="button"
                class="fullscreen-player__icon-button"
                title="Song actions"
                aria-label="Song actions"
                @click="openSongActionMenu(activeTrack, $event)"
              >
                <q-icon name="more_horiz" />
              </button>
              <button
                type="button"
                class="fullscreen-player__icon-button"
                :class="{ 'fullscreen-player__icon-button--active': isActiveTrackLiked }"
                :disabled="!canToggleActiveTrackLike || activeTrackLikePending"
                :title="isActiveTrackLiked ? 'Remove from Liked Songs' : 'Add to Liked Songs'"
                :aria-label="isActiveTrackLiked ? 'Remove from Liked Songs' : 'Add to Liked Songs'"
                @click="toggleActiveTrackLike"
              >
                <q-icon :name="isActiveTrackLiked ? 'star' : 'star_border'" />
              </button>
              <button
                type="button"
                class="fullscreen-player__icon-button fullscreen-player__secondary-action"
                :class="{ 'fullscreen-player__icon-button--active': shuffleEnabled }"
                :title="shuffleEnabled ? 'Shuffle on' : 'Shuffle off'"
                :aria-label="shuffleEnabled ? 'Turn shuffle off' : 'Turn shuffle on'"
                @click="toggleShuffle"
              >
                <q-icon name="shuffle" />
              </button>
            </div>

            <div class="fullscreen-player__playback-buttons">
              <button
                type="button"
                class="fullscreen-player__skip-button"
                :disabled="!activeTrack || buffering"
                aria-label="Previous track"
                title="Previous"
                @click="playPrevious"
              >
                <q-icon name="skip_previous" />
              </button>
              <button
                type="button"
                class="fullscreen-player__play-button"
                :disabled="!activeTrack || buffering"
                :aria-label="isPlaying ? 'Pause' : 'Play'"
                :title="isPlaying ? 'Pause' : 'Play'"
                @click="togglePlayback"
              >
                <q-spinner v-if="buffering" size="22px" />
                <q-icon v-else :name="isPlaying ? 'pause' : 'play_arrow'" />
              </button>
              <button
                type="button"
                class="fullscreen-player__skip-button"
                :disabled="(!queue.length && (!activeTrack || repeatMode === 'off')) || buffering"
                aria-label="Next track"
                title="Next"
                @click="playNext({ skipRepeatOne: true })"
              >
                <q-icon name="skip_next" />
              </button>
            </div>

            <div class="fullscreen-player__control-cluster fullscreen-player__control-cluster--end">
              <button
                type="button"
                class="fullscreen-player__icon-button fullscreen-player__secondary-action"
                :class="{ 'fullscreen-player__icon-button--active': repeatMode !== 'off' }"
                :title="repeatModeTitle()"
                :aria-label="repeatModeTitle()"
                @click="cycleRepeatMode"
              >
                <q-icon :name="repeatMode === 'one' ? 'repeat_one' : 'repeat'" />
              </button>
              <button
                type="button"
                class="fullscreen-player__icon-button"
                :class="{ 'fullscreen-player__icon-button--active': fullscreenLyricsVisible }"
                :title="fullscreenLyricsVisible ? 'Hide lyrics' : 'Show lyrics'"
                :aria-label="fullscreenLyricsVisible ? 'Hide lyrics' : 'Show lyrics'"
                :aria-pressed="fullscreenLyricsVisible"
                @click="toggleLyrics"
              >
                <q-icon name="lyrics" />
              </button>
              <button
                type="button"
                class="fullscreen-player__icon-button fullscreen-player__queue-toggle"
                :class="{ 'fullscreen-player__icon-button--active': queueOpen }"
                title="Show queue"
                aria-label="Show queue"
                :aria-expanded="queueOpen"
                @click="queueOpen = !queueOpen"
              >
                <q-icon name="queue_music" />
                <span v-if="queueCount" class="fullscreen-player__queue-badge">{{ queueCount }}</span>
              </button>
            </div>
          </div>

          <div class="fullscreen-player__track-line">
            <div class="fullscreen-player__track-identity">
              <button type="button" :title="activeTrack?.title" @click="openTrackAlbumFromFullscreen">
                {{ activeTrack?.title || 'Ready' }}
              </button>
              <span aria-hidden="true">·</span>
              <button
                type="button"
                :disabled="!canOpenActiveTrackArtist()"
                :title="activeArtist ? `View artist: ${activeArtist}` : undefined"
                @click="openTrackArtistFromFullscreen"
              >
                {{ activeArtist || 'Orchard' }}
              </button>
              <ExplicitBadge :explicit="activeTrack?.explicit" />
              <small v-if="bitrateLabel(activeTrack)">{{ bitrateLabel(activeTrack) }} kbps</small>
            </div>
            <div class="fullscreen-player__volume" title="Scroll to change volume" @wheel.prevent="onVolumeWheel">
              <button
                type="button"
                :title="volume === 0 ? 'Unmute' : 'Mute'"
                :aria-label="volume === 0 ? 'Unmute' : 'Mute'"
                @click="toggleMute"
              >
                <q-icon :name="volumeIcon" />
              </button>
              <q-slider v-model="volume" :min="0" :max="1" :step="0.01" color="white" aria-label="Volume" />
            </div>
          </div>
        </div>
      </section>

      <Transition name="fullscreen-lyrics">
        <FullscreenLyrics v-if="fullscreenLyricsVisible" :app="app" :mix="mix" />
      </Transition>
    </main>

    <p class="fullscreen-player__sr-status" role="status" aria-live="polite">{{ mixStatus }}</p>

    <Transition name="fullscreen-queue">
      <div v-if="queueOpen" class="fullscreen-player__queue-scrim" @click.self="queueOpen = false">
        <aside class="fullscreen-player__queue" :aria-label="continuousQueueEnabled ? 'Queue' : 'Up next'">
          <header class="fullscreen-player__queue-header">
            <div class="fullscreen-player__queue-header-title">
              <strong>{{ continuousQueueEnabled ? 'Queue' : 'Up Next' }}</strong>
              <span v-if="queueCount" class="fullscreen-player__queue-count">{{ queueCount }}</span>
            </div>
            <div class="fullscreen-player__queue-header-actions">
              <button
                v-if="queueTracksForPlaylist().length"
                type="button"
                class="fullscreen-player__queue-btn"
                title="Add queue to playlist"
                @click="openQueuePlaylistDialog"
              >
                <q-icon name="playlist_add" size="15px" />
                <span>Save</span>
              </button>
              <button v-if="queue.length" type="button" class="fullscreen-player__queue-btn" @click="clearQueue">
                Clear
              </button>
              <button
                type="button"
                class="fullscreen-player__queue-btn fullscreen-player__queue-close"
                aria-label="Close queue"
                title="Close queue"
                @click="queueOpen = false"
              >
                <q-icon name="close" />
              </button>
            </div>
          </header>

          <div class="fullscreen-player__queue-list">
            <FullscreenVirtualQueue
              :app="app"
              :continuous="continuousQueueEnabled"
              :entries="continuousQueueEnabled ? continuousQueue : queue"
            />
          </div>

          <label class="fullscreen-player__autoplay">
            <span>Autoplay</span>
            <q-toggle v-model="autoplayEnabled" color="primary" size="sm" aria-label="Autoplay" />
          </label>
        </aside>
      </div>
    </Transition>
  </section>
</template>
