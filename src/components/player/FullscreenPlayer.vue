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
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createVolumeWheelHandler } from '../../app/playback/volumeWheel.js';
import { bitrateLabel } from '../../app/playback/trackQuality.js';

export default {
  name: 'FullscreenPlayer',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const app = props.app;
    const closeButtonRef = ref(null);
    const fullscreenArtworkSrc = ref(app.fullscreenArtworkImage.value || app.nowArtworkImage.value);
    const onVolumeWheel = createVolumeWheelHandler(app.volume);

    const volumeIcon = computed(() => {
      const vol = app.volume.value;
      if (vol === 0) return 'volume_off';
      if (vol < 0.3) return 'volume_mute';
      if (vol < 0.7) return 'volume_down';
      return 'volume_up';
    });

    let savedVolume = 0.85;
    const toggleMute = () => {
      if (app.volume.value > 0) {
        savedVolume = app.volume.value;
        app.volume.value = 0;
      } else {
        app.volume.value = savedVolume || 0.85;
      }
    };

    const queueCount = computed(() => {
      if (app.continuousQueueEnabled?.value) {
        return app.continuousQueuePreview?.value?.length || 0;
      }
      return app.queuePreview?.value?.length || app.queue?.value?.length || 0;
    });

    function onFullscreenKeydown(event) {
      if (event.key === 'Escape') void app.closeFullscreenPlayer();
    }

    function playFullscreenArtworkVideo() {
      if (!app.isPlaying.value) return;
      app.playArtworkVideo(app.fullscreenArtworkVideoRef, app.nowArtworkVideoFailed);
    }

    function restartFullscreenArtworkVideo() {
      if (!app.isPlaying.value) return;
      app.restartArtworkVideo(app.fullscreenArtworkVideoRef, app.nowArtworkVideoFailed);
    }

    function keepFullscreenArtworkVideoPlaying() {
      if (!app.isPlaying.value) return;
      app.keepArtworkVideoPlaying(app.fullscreenArtworkVideoRef, app.nowArtworkVideoFailed);
    }

    function onFullscreenArtworkError() {
      const fallback = app.nowArtworkImage.value || app.activeTrack.value?.thumbnail || '';
      if (fallback && fullscreenArtworkSrc.value !== fallback) fullscreenArtworkSrc.value = fallback;
    }

    watch(app.fullscreenArtworkImage, (image) => {
      fullscreenArtworkSrc.value = image || app.nowArtworkImage.value || '';
    });

    watch(app.isPlaying, (playing) => {
      if (playing) playFullscreenArtworkVideo();
      else app.fullscreenArtworkVideoRef.value?.pause();
    });

    onMounted(() => {
      closeButtonRef.value?.focus();
      window.addEventListener('keydown', onFullscreenKeydown);
      void app.scrollActiveLyric();
    });

    onBeforeUnmount(() => {
      window.removeEventListener('keydown', onFullscreenKeydown);
    });

    return {
      ...app,
      bitrateLabel,
      closeButtonRef,
      fullscreenArtworkSrc,
      keepFullscreenArtworkVideoPlaying,
      onFullscreenArtworkError,
      onVolumeWheel,
      playFullscreenArtworkVideo,
      queueCount,
      restartFullscreenArtworkVideo,
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
    :class="{ 'fullscreen-player--immersive': immersiveBackgroundsEnabled && (immersiveArtworkImage || immersiveArtworkVideo) }"
    :style="fullscreenPlayerStyle"
    role="dialog"
    aria-modal="true"
    aria-label="Fullscreen player"
  >
    <div class="fullscreen-player__ambient" aria-hidden="true">
      <div class="fullscreen-player__ambient-glow fullscreen-player__ambient-glow--primary" />
      <div class="fullscreen-player__ambient-glow fullscreen-player__ambient-glow--secondary" />
    </div>
    <div class="fullscreen-player__backdrop" aria-hidden="true" />
    <div class="fullscreen-player__shade" aria-hidden="true" />

    <header class="fullscreen-player__header">
      <div class="fullscreen-player__brand">
        <img :src="orchardLogoUrl" alt="" />
        <div class="fullscreen-player__brand-text">
          <span class="fullscreen-player__brand-context">NOW PLAYING</span>
          <span class="fullscreen-player__brand-title">Orchard</span>
        </div>
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
        <span>Exit</span>
        <kbd class="fullscreen-player__shortcut-badge">Esc</kbd>
      </button>
    </header>

    <main class="fullscreen-player__stage">
      <div class="fullscreen-player__left">
        <div class="fullscreen-player__artwork" :class="{ 'fullscreen-player__artwork--playing': isPlaying }">
          <transition name="artwork-fade">
            <video
              v-if="nowArtworkVideo"
              ref="fullscreenArtworkVideoRef"
              :key="nowArtworkVideo"
              :src="nowArtworkVideo"
              :poster="fullscreenArtworkImage || nowArtworkImage || activeTrack?.thumbnail"
              :autoplay="isPlaying"
              muted
              loop
              playsinline
              preload="auto"
              aria-hidden="true"
              @canplay="playFullscreenArtworkVideo"
              @pause="keepFullscreenArtworkVideoPlaying"
              @ended="restartFullscreenArtworkVideo"
              @stalled="keepFullscreenArtworkVideoPlaying"
              @waiting="keepFullscreenArtworkVideoPlaying"
              @error="onNowArtworkVideoError"
            />
            <img
              v-else-if="fullscreenArtworkSrc"
              :key="fullscreenArtworkSrc"
              :src="fullscreenArtworkSrc"
              :alt="`${activeTrack?.title || 'Current track'} artwork`"
              @error="onFullscreenArtworkError"
            />
            <div v-else key="empty" class="fullscreen-player__artwork-empty">
              <q-icon name="music_note" />
            </div>
          </transition>
        </div>

        <div class="fullscreen-player__track-copy">
          <div class="fullscreen-player__track-title-wrap">
            <strong :title="activeTrack?.title || 'Ready'">{{ activeTrack?.title || 'Ready' }}</strong>
            <ExplicitBadge :explicit="activeTrack?.explicit" />
          </div>
          <div class="fullscreen-player__track-sub-wrap">
            <button
              v-if="activeArtist"
              type="button"
              class="fullscreen-player__artist-link"
              :disabled="!canOpenActiveTrackArtist()"
              :title="`View artist: ${activeArtist}`"
              @click="openTrackArtist"
            >
              {{ activeArtist }}
            </button>
            <span v-else class="fullscreen-player__artist-name">Orchard</span>
            <template v-if="activeTrack?.album">
              <span class="fullscreen-player__dot" aria-hidden="true">·</span>
              <button
                type="button"
                class="fullscreen-player__album-link"
                :title="`View album: ${activeTrack.album}`"
                @click="openTrackAlbum"
              >
                {{ activeTrack.album }}
              </button>
            </template>
          </div>

          <!-- Quick Action Bar -->
          <div v-if="activeTrack" class="fullscreen-player__track-actions">
            <button
              type="button"
              class="fullscreen-player__action-btn"
              :class="{ 'fullscreen-player__action-btn--active': isActiveTrackLiked }"
              :disabled="!canToggleActiveTrackLike || activeTrackLikePending"
              :title="isActiveTrackLiked ? 'Remove from Liked Songs' : 'Add to Liked Songs'"
              :aria-label="isActiveTrackLiked ? 'Remove from Liked Songs' : 'Add to Liked Songs'"
              @click="toggleActiveTrackLike"
            >
              <q-icon :name="isActiveTrackLiked ? 'star' : 'star_border'" />
            </button>
            <button
              type="button"
              class="fullscreen-player__action-btn"
              title="Song actions"
              aria-label="Song actions"
              @click="openSongActionMenu(activeTrack, $event)"
            >
              <q-icon name="more_horiz" />
            </button>
            <span v-if="bitrateLabel(activeTrack)" class="fullscreen-player__badge" title="Audio Bitrate">
              {{ bitrateLabel(activeTrack) }} kbps
            </span>
          </div>
        </div>
      </div>

      <section class="fullscreen-player__lyrics" aria-label="Lyrics">
        <div v-if="lyricsState.status === 'loading'" class="fullscreen-player__lyrics-message" aria-live="polite">
          <q-spinner-dots size="38px" />
          <span>Loading lyrics</span>
        </div>

        <div
          v-else-if="lyricsState.status === 'ready'"
          class="fullscreen-player__lyrics-scroll"
          :class="{ 'fullscreen-player__lyrics-scroll--synced': lyricsState.mode === 'synced' }"
          @scroll.passive="onLyricsUserScroll"
          @wheel.passive="onLyricsUserScrollStart"
          @touchstart.passive="onLyricsUserScrollStart"
          @pointerdown="onLyricsPointerdown"
        >
          <div v-if="lyricsState.mode !== 'synced'" class="fullscreen-player__unsynced-message">
            <q-icon name="info" />
            <span>These lyrics aren't synced to the music.</span>
          </div>
          <template v-for="item in lyricDisplayItems" :key="`fullscreen-${item.key}`">
            <button
              v-if="item.type === 'line' && item.canSeek"
              type="button"
              class="lyrics-line lyrics-line--button"
              :class="{
                'lyrics-line--active': item.active,
                'lyrics-line--word-synced': item.words?.length || item.adlibs?.length,
                'lyrics-line--alternate-agent': item.agentLane === 'alternate'
              }"
              @click="seekToLyric(item)"
            >
              <span v-if="item.words?.length" class="lyrics-line__words">
                <span
                  v-for="word in item.words"
                  :key="word.key"
                  class="lyrics-word"
                  :class="`lyrics-word--${word.state}`"
                  :style="{ '--word-progress': word.progress }"
                >{{ word.text }}</span>
              </span>
              <span v-else>{{ item.text }}</span>
              <span v-if="item.adlibs?.length" class="lyrics-line__adlibs">
                <span
                  v-for="word in item.adlibs"
                  :key="word.key"
                  class="lyrics-word"
                  :class="`lyrics-word--${word.state}`"
                  :style="{ '--word-progress': word.progress }"
                >{{ word.text }}</span>
              </span>
            </button>

            <div
              v-else
              class="lyrics-line"
              :class="{
                'lyrics-line--active': item.type === 'line' && item.active,
                'lyrics-line--word-synced': item.type === 'line' && (item.words?.length || item.adlibs?.length),
                'lyrics-line--alternate-agent': item.type === 'line' && item.agentLane === 'alternate',
                'lyrics-pause': item.type === 'pause',
                'lyrics-pause--active': item.type === 'pause' && item.active
              }"
            >
              <template v-if="item.type === 'line'">
                <span v-if="item.words?.length" class="lyrics-line__words">
                  <span
                    v-for="word in item.words"
                    :key="word.key"
                    class="lyrics-word"
                    :class="`lyrics-word--${word.state}`"
                    :style="{ '--word-progress': word.progress }"
                  >{{ word.text }}</span>
                </span>
                <span v-else>{{ item.text }}</span>
                <span v-if="item.adlibs?.length" class="lyrics-line__adlibs">
                  <span
                    v-for="word in item.adlibs"
                    :key="word.key"
                    class="lyrics-word"
                    :class="`lyrics-word--${word.state}`"
                    :style="{ '--word-progress': word.progress }"
                  >{{ word.text }}</span>
                </span>
              </template>
              <span v-else class="fullscreen-player__pause" aria-label="Instrumental break">
                <span class="fullscreen-player__pause-dot" />
                <span class="fullscreen-player__pause-dot" />
                <span class="fullscreen-player__pause-dot" />
              </span>
            </div>
          </template>
        </div>

        <div v-else class="fullscreen-player__lyrics-message">
          <q-icon name="lyrics" />
          <span>Lyrics unavailable</span>
        </div>

        <div class="fullscreen-player__lyrics-label" aria-hidden="true">
          <q-icon name="lyrics" />
          <span>Lyrics</span>
        </div>
      </section>

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
              aria-label="Add queue to playlist"
              @click="openQueuePlaylistDialog"
            >
              <q-icon name="playlist_add" size="14px" />
              <span>Save</span>
            </button>
            <button
              v-if="queue.length"
              type="button"
              class="fullscreen-player__queue-btn"
              title="Clear queue"
              aria-label="Clear queue"
              @click="clearQueue"
            >
              Clear
            </button>
          </div>
        </header>

        <div v-if="continuousQueueEnabled" class="fullscreen-player__queue-list">
          <div
            v-for="entry in continuousQueuePreview"
            :key="`fullscreen-queue-${entry.key}`"
            class="fullscreen-player__queue-item"
            :class="`fullscreen-player__queue-item--${entry.section}`"
            role="button"
            tabindex="0"
            :aria-current="entry.section === 'current' ? 'true' : undefined"
            @click="playContinuousQueueEntry(entry)"
            @keydown.enter.prevent="playContinuousQueueEntry(entry)"
            @keydown.space.prevent="playContinuousQueueEntry(entry)"
          >
            <img v-if="entry.track.thumbnail" :src="entry.track.thumbnail" alt="" />
            <span v-else class="fullscreen-player__queue-cover">
              <q-icon name="music_note" />
            </span>
            <span class="fullscreen-player__queue-copy">
              <strong>{{ entry.track.title }}</strong>
              <span>{{ entry.track.artist || entry.track.artists?.join(', ') || entry.track.album || 'Orchard' }}</span>
            </span>
            <span class="fullscreen-player__queue-time">{{ entry.track.duration || '' }}</span>
            <button
              v-if="entry.section !== 'current'"
              type="button"
              class="fullscreen-player__queue-remove"
              :aria-label="`Remove ${entry.track.title} from the queue`"
              title="Remove from queue"
              @click.stop="removeContinuousQueueEntry(entry)"
              @keydown.stop
            >
              <q-icon name="close" />
            </button>
            <span v-else class="fullscreen-player__queue-now-playing" aria-hidden="true">
              <span class="fullscreen-player__eq-bar" />
              <span class="fullscreen-player__eq-bar" />
              <span class="fullscreen-player__eq-bar" />
            </span>
          </div>

          <div v-if="!continuousQueuePreview.length" class="fullscreen-player__queue-empty">
            The queue is empty.
          </div>
        </div>

        <div v-else class="fullscreen-player__queue-list">
          <div
            v-for="(item, index) in queuePreview"
            :key="`fullscreen-queue-${item.id}-${index}`"
            class="fullscreen-player__queue-item"
            role="button"
            tabindex="0"
            @click="playTrack(item, { queueSource: queue })"
            @keydown.enter.prevent="playTrack(item, { queueSource: queue })"
            @keydown.space.prevent="playTrack(item, { queueSource: queue })"
          >
            <img v-if="item.thumbnail" :src="item.thumbnail" alt="" />
            <span v-else class="fullscreen-player__queue-cover">
              <q-icon name="music_note" />
            </span>
            <span class="fullscreen-player__queue-copy">
              <strong>{{ item.title }}</strong>
              <span>{{ item.artist || item.artists?.join(', ') || item.album || 'Orchard' }}</span>
            </span>
            <span class="fullscreen-player__queue-time">{{ item.duration || '' }}</span>
            <button
              type="button"
              class="fullscreen-player__queue-remove"
              :aria-label="`Remove ${item.title} from queue`"
              title="Remove from queue"
              @click.stop="removeQueueTrack(index)"
              @keydown.stop
            >
              <q-icon name="close" />
            </button>
          </div>

          <div v-if="!queue.length" class="fullscreen-player__queue-empty">
            The queue is empty.
          </div>
        </div>

        <label class="fullscreen-player__autoplay">
          <span>Autoplay</span>
          <q-toggle v-model="autoplayEnabled" color="primary" size="sm" aria-label="Autoplay" />
        </label>
      </aside>
    </main>

    <footer class="fullscreen-player__transport">
      <div class="fullscreen-player__progress">
        <span class="fullscreen-player__time fullscreen-player__time--current">{{ formatTime(displayedTime) }}</span>
        <div class="progress-slider" :style="crossfadeProgressStyle">
          <q-slider
            v-model="seekPosition"
            :min="0"
            :max="duration || 1"
            :step="1"
            color="primary"
            :disable="activeTrackIsLive"
            aria-label="Song progress"
            @update:model-value="onSeekPositionChange"
            @change="seek"
            @pan="onSeekPan"
          />
        </div>
        <span class="fullscreen-player__time fullscreen-player__time--duration">{{ durationLabel }}</span>
      </div>

      <div class="fullscreen-player__transport-row">
        <div class="fullscreen-player__transport-meta">
          <div v-if="activeTrack" class="fullscreen-player__mini-info">
            <button
              type="button"
              class="fullscreen-player__mini-title"
              :title="activeTrack.title"
              @click="openTrackAlbum"
            >
              {{ activeTrack.title }}
            </button>
            <span class="fullscreen-player__mini-artist">{{ activeArtist || 'Orchard' }}</span>
          </div>
        </div>

        <div class="fullscreen-player__buttons">
          <q-btn
            flat
            round
            icon="shuffle"
            class="fullscreen-player__ctrl-btn"
            :class="{ 'fullscreen-player__ctrl-btn--active': shuffleEnabled }"
            :color="shuffleEnabled ? 'primary' : undefined"
            :title="shuffleEnabled ? 'Shuffle on' : 'Shuffle off'"
            :aria-label="shuffleEnabled ? 'Turn shuffle off' : 'Turn shuffle on'"
            @click="toggleShuffle"
          />
          <q-btn
            flat
            round
            icon="skip_previous"
            class="fullscreen-player__ctrl-btn fullscreen-player__ctrl-btn--skip"
            :disable="!activeTrack || buffering"
            aria-label="Previous track"
            title="Previous"
            @click="playPrevious"
          />
          <q-btn
            round
            color="primary"
            size="lg"
            class="fullscreen-player__ctrl-play"
            :loading="buffering"
            :disable="!activeTrack"
            :icon="isPlaying ? 'pause' : 'play_arrow'"
            :aria-label="isPlaying ? 'Pause' : 'Play'"
            :title="isPlaying ? 'Pause' : 'Play'"
            @click="togglePlayback"
          />
          <q-btn
            flat
            round
            icon="skip_next"
            class="fullscreen-player__ctrl-btn fullscreen-player__ctrl-btn--skip"
            :disable="(!queue.length && (!activeTrack || repeatMode === 'off')) || buffering"
            aria-label="Next track"
            title="Next"
            @click="playNext({ skipRepeatOne: true })"
          />
          <q-btn
            flat
            round
            class="fullscreen-player__ctrl-btn"
            :class="{ 'fullscreen-player__ctrl-btn--active': repeatMode !== 'off' }"
            :icon="repeatMode === 'one' ? 'repeat_one' : 'repeat'"
            :color="repeatMode !== 'off' ? 'primary' : undefined"
            :title="repeatModeTitle()"
            :aria-label="repeatModeTitle()"
            @click="cycleRepeatMode"
          />
        </div>

        <div class="fullscreen-player__volume" title="Scroll to change volume" @wheel.prevent="onVolumeWheel">
          <button
            type="button"
            class="fullscreen-player__volume-btn"
            :title="volume === 0 ? 'Unmute' : 'Mute'"
            :aria-label="volume === 0 ? 'Unmute' : 'Mute'"
            @click="toggleMute"
          >
            <q-icon :name="volumeIcon" />
          </button>
          <q-slider v-model="volume" :min="0" :max="1" :step="0.01" color="primary" aria-label="Volume" />
        </div>
      </div>
    </footer>
  </section>
</template>
