<script>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

export default {
  name: 'FullscreenPlayer',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const app = props.app;
    const closeButtonRef = ref(null);
    const fullscreenArtworkSrc = ref(app.fullscreenArtworkImage.value || app.nowArtworkImage.value);

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
      closeButtonRef,
      fullscreenArtworkSrc,
      keepFullscreenArtworkVideoPlaying,
      onFullscreenArtworkError,
      playFullscreenArtworkVideo,
      restartFullscreenArtworkVideo
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
    <div class="fullscreen-player__backdrop" aria-hidden="true" />
    <div class="fullscreen-player__shade" aria-hidden="true" />

    <header class="fullscreen-player__header">
      <div class="fullscreen-player__brand">
        <img :src="orchardLogoUrl" alt="" />
        <span>Orchard</span>
      </div>
      <button
        ref="closeButtonRef"
        type="button"
        class="fullscreen-player__close"
        title="Exit fullscreen player"
        aria-label="Exit fullscreen player"
        @click="closeFullscreenPlayer"
      >
        <q-icon name="close_fullscreen" />
        <span>Exit</span>
      </button>
    </header>

    <main class="fullscreen-player__stage">
      <div class="fullscreen-player__left">
        <div class="fullscreen-player__artwork">
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
            :src="fullscreenArtworkSrc"
            :alt="`${activeTrack?.title || 'Current track'} artwork`"
            @error="onFullscreenArtworkError"
          />
          <div v-else class="fullscreen-player__artwork-empty">
            <q-icon name="music_note" />
          </div>
        </div>

        <div class="fullscreen-player__track-copy">
          <div>
            <strong>{{ activeTrack?.title || 'Ready' }}</strong>
            <ExplicitBadge :explicit="activeTrack?.explicit" />
          </div>
          <span>
            <b>{{ activeArtist || 'Orchard' }}</b>
            <template v-if="activeTrack?.album"> · {{ activeTrack.album }}</template>
          </span>
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
              <span v-else class="fullscreen-player__pause" aria-label="Instrumental break">•••</span>
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

      <aside class="fullscreen-player__queue" aria-label="Up next">
        <header class="fullscreen-player__queue-header">
          <strong>Up Next</strong>
          <button v-if="queue.length" type="button" @click="clearQueue">Clear</button>
        </header>

        <div class="fullscreen-player__queue-list">
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
        <span>{{ formatTime(displayedTime) }}</span>
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
        <span>{{ durationLabel }}</span>
      </div>

      <div class="fullscreen-player__transport-row">
        <div aria-hidden="true" />
        <div class="fullscreen-player__buttons">
          <q-btn
            flat
            round
            icon="shuffle"
            :color="shuffleEnabled ? 'primary' : undefined"
            :title="shuffleEnabled ? 'Shuffle on' : 'Shuffle off'"
            :aria-label="shuffleEnabled ? 'Turn shuffle off' : 'Turn shuffle on'"
            @click="toggleShuffle"
          />
          <q-btn flat round icon="skip_previous" :disable="!activeTrack || buffering" aria-label="Previous" @click="playPrevious" />
          <q-btn
            round
            color="primary"
            size="lg"
            :loading="buffering"
            :disable="!activeTrack"
            :icon="isPlaying ? 'pause' : 'play_arrow'"
            :aria-label="isPlaying ? 'Pause' : 'Play'"
            @click="togglePlayback"
          />
          <q-btn
            flat
            round
            icon="skip_next"
            :disable="(!queue.length && (!activeTrack || repeatMode === 'off')) || buffering"
            aria-label="Next"
            @click="playNext({ skipRepeatOne: true })"
          />
          <q-btn
            flat
            round
            :icon="repeatMode === 'one' ? 'repeat_one' : 'repeat'"
            :color="repeatMode !== 'off' ? 'primary' : undefined"
            :title="repeatModeTitle()"
            :aria-label="repeatModeTitle()"
            @click="cycleRepeatMode"
          />
        </div>

        <div class="fullscreen-player__volume">
          <q-icon :name="volume === 0 ? 'volume_off' : 'volume_up'" />
          <q-slider v-model="volume" :min="0" :max="1" :step="0.01" color="primary" aria-label="Volume" />
        </div>
      </div>
    </footer>
  </section>
</template>
