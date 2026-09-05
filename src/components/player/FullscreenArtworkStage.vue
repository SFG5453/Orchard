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
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { getArtworkPalette } from '../animated-background/useArtworkPalette.js';

export default {
  name: 'FullscreenArtworkStage',
  emits: ['palette'],
  props: {
    app: { type: Object, required: true },
    mix: { type: Object, required: true }
  },
  setup(props, { emit }) {
    const restingSource = ref(
      props.app.fullscreenArtworkImage.value ||
      props.app.nowArtworkImage.value ||
      props.app.activeTrack.value?.thumbnail ||
      ''
    );
    const failedSources = new Set();

    const outgoingSource = computed(() => {
      const source = props.mix.visible
        ? props.mix.from?.artwork
        : restingSource.value;
      return props.app.highResolutionArtworkImage(source || restingSource.value);
    });
    const incomingSource = computed(() => (
      props.mix.visible
        ? props.app.highResolutionArtworkImage(props.mix.to?.artwork || '')
        : ''
    ));
    const videoVisible = computed(() => Boolean(
      !props.mix.visible && props.app.nowArtworkVideo.value
    ));

    watch(props.app.fullscreenArtworkImage, (source) => {
      restingSource.value = source || props.app.nowArtworkImage.value || props.app.activeTrack.value?.thumbnail || '';
    });

    function setArtworkVideoRef(element) {
      props.app.fullscreenArtworkVideoRef.value = element || null;
    }

    function playArtworkVideo() {
      if (!props.app.isPlaying.value) return;
      props.app.playArtworkVideo(props.app.fullscreenArtworkVideoRef, props.app.nowArtworkVideoFailed);
    }

    function keepArtworkVideoPlaying() {
      if (!props.app.isPlaying.value) return;
      props.app.keepArtworkVideoPlaying(props.app.fullscreenArtworkVideoRef, props.app.nowArtworkVideoFailed);
    }

    function restartArtworkVideo() {
      if (!props.app.isPlaying.value) return;
      props.app.restartArtworkVideo(props.app.fullscreenArtworkVideoRef, props.app.nowArtworkVideoFailed);
    }

    async function samplePalette(event) {
      const image = event.currentTarget;
      const url = image.getAttribute('src') || image.currentSrc || image.src || '';
      if (!url || failedSources.has(url)) return;
      const palette = await getArtworkPalette(url, image);
      emit('palette', { palette, url });
    }

    function useArtworkFallback(event, incoming = false) {
      const image = event.currentTarget;
      failedSources.add(image.currentSrc || image.src || '');
      if (incoming) return;
      const fallback = props.app.nowArtworkImage.value || props.app.activeTrack.value?.thumbnail || '';
      if (fallback && image.src !== fallback) restingSource.value = fallback;
    }

    watch(props.app.isPlaying, (playing) => {
      if (playing) playArtworkVideo();
      else props.app.fullscreenArtworkVideoRef.value?.pause();
    });

    onBeforeUnmount(() => {
      props.app.fullscreenArtworkVideoRef.value = null;
    });

    return {
      incomingSource,
      keepArtworkVideoPlaying,
      outgoingSource,
      playArtworkVideo,
      restartArtworkVideo,
      samplePalette,
      setArtworkVideoRef,
      useArtworkFallback,
      videoVisible
    };
  }
};
</script>

<template>
  <div class="fullscreen-player__artwork-stage" :data-mix-phase="mix.visible ? mix.phase : 'idle'">
    <Transition name="fullscreen-up-next">
      <div v-if="mix.visible" :key="mix.id" class="fullscreen-player__mix-caption">
        <span>{{ mix.phase === 'preparing' ? 'Up next' : mix.styleLabel }}</span>
        <strong>{{ mix.to.title }}</strong>
        <small>{{ mix.to.artist }}</small>
      </div>
    </Transition>

    <div class="fullscreen-player__artwork">
      <div class="fullscreen-player__artwork-layer fullscreen-player__artwork-layer--outgoing">
        <img
          v-if="outgoingSource"
          :key="outgoingSource"
          :src="outgoingSource"
          :alt="`${mix.visible ? mix.from.title : app.activeTrack.value?.title || 'Current track'} artwork`"
          @load="samplePalette"
          @error="useArtworkFallback($event)"
        />
        <div v-else class="fullscreen-player__artwork-empty">
          <q-icon name="music_note" />
        </div>
        <video
          v-if="videoVisible"
          :ref="setArtworkVideoRef"
          :key="app.nowArtworkVideo.value"
          :src="app.nowArtworkVideo.value"
          :poster="outgoingSource"
          :autoplay="app.isPlaying.value"
          muted
          loop
          playsinline
          preload="auto"
          aria-hidden="true"
          @canplay="playArtworkVideo"
          @pause="keepArtworkVideoPlaying"
          @ended="restartArtworkVideo"
          @stalled="keepArtworkVideoPlaying"
          @waiting="keepArtworkVideoPlaying"
          @error="app.onNowArtworkVideoError"
        />
      </div>

      <div
        v-if="mix.visible && incomingSource"
        class="fullscreen-player__artwork-layer fullscreen-player__artwork-layer--incoming"
      >
        <img
          :key="incomingSource"
          :src="incomingSource"
          :alt="`${mix.to.title || 'Incoming track'} artwork`"
          @load="samplePalette"
          @error="useArtworkFallback($event, true)"
        />
      </div>
    </div>
  </div>
</template>
