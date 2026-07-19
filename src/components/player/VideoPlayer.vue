<script>
import { computed, onBeforeUnmount, ref } from 'vue';

export default {
  name: 'VideoPlayer',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const playerRef = ref(null);
    const miniPosition = ref(null);
    const miniSize = ref(null);
    const dragging = ref(false);
    const resizing = ref(false);
    let dragState = null;
    let resizeState = null;

    const miniAspectRatio = 16 / 9;

    function playerBottomEdge() {
      const playerBarTop = document.querySelector('.player-bar')?.getBoundingClientRect().top;
      return Number.isFinite(playerBarTop) ? playerBarTop : window.innerHeight;
    }

    function availableBounds(width, height) {
      return {
        minX: 8,
        minY: 56,
        maxX: Math.max(8, window.innerWidth - width - 8),
        maxY: Math.max(56, playerBottomEdge() - height - 8)
      };
    }

    function clampPosition(x, y, width, height) {
      const bounds = availableBounds(width, height);
      return {
        x: Math.min(bounds.maxX, Math.max(bounds.minX, x)),
        y: Math.min(bounds.maxY, Math.max(bounds.minY, y))
      };
    }

    function onVideoDragStart(event) {
      if (!props.app.videoPlayerMinimized.value || event.button !== 0 || event.target.closest('button')) return;

      const player = playerRef.value;
      const rect = player?.getBoundingClientRect();
      if (!player || !rect) return;

      event.preventDefault();
      player.setPointerCapture(event.pointerId);
      dragging.value = true;
      miniPosition.value = { x: rect.left, y: rect.top };
      dragState = {
        pointerId: event.pointerId,
        offsetX: event.clientX - rect.left,
        offsetY: event.clientY - rect.top,
        width: rect.width,
        height: rect.height
      };
    }

    function onVideoDrag(event) {
      if (!dragState || event.pointerId !== dragState.pointerId) return;
      miniPosition.value = clampPosition(
        event.clientX - dragState.offsetX,
        event.clientY - dragState.offsetY,
        dragState.width,
        dragState.height
      );
    }

    function onVideoDragEnd(event) {
      if (!dragState || event.pointerId !== dragState.pointerId) return;
      if (playerRef.value?.hasPointerCapture(event.pointerId)) {
        playerRef.value.releasePointerCapture(event.pointerId);
      }
      dragging.value = false;
      dragState = null;
    }

    function onVideoResizeStart(event) {
      if (!props.app.videoPlayerMinimized.value || event.button !== 0) return;

      const player = playerRef.value;
      const rect = player?.getBoundingClientRect();
      if (!player || !rect) return;

      event.preventDefault();
      event.currentTarget.setPointerCapture(event.pointerId);
      resizing.value = true;
      miniPosition.value = { x: rect.left, y: rect.top };
      miniSize.value = { width: rect.width, height: rect.height };
      resizeState = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        startWidth: rect.width,
        startHeight: rect.height
      };
    }

    function onVideoResize(event) {
      if (!resizeState || event.pointerId !== resizeState.pointerId) return;

      const horizontalWidth = resizeState.startWidth + event.clientX - resizeState.startX;
      const verticalWidth = (resizeState.startHeight + event.clientY - resizeState.startY) * miniAspectRatio;
      const horizontalDelta = Math.abs(horizontalWidth - resizeState.startWidth);
      const verticalDelta = Math.abs(verticalWidth - resizeState.startWidth);
      const requestedWidth = horizontalDelta >= verticalDelta ? horizontalWidth : verticalWidth;
      const maxWidth = Math.max(160, Math.min(
        window.innerWidth - miniPosition.value.x - 8,
        (playerBottomEdge() - miniPosition.value.y - 8) * miniAspectRatio
      ));
      const minWidth = Math.min(240, maxWidth);
      const width = Math.min(maxWidth, Math.max(minWidth, requestedWidth));

      miniSize.value = { width, height: width / miniAspectRatio };
    }

    function onVideoResizeEnd(event) {
      if (!resizeState || event.pointerId !== resizeState.pointerId) return;
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
      resizing.value = false;
      resizeState = null;
    }

    function keepMiniPlayerOnScreen() {
      const player = playerRef.value;
      if (!props.app.videoPlayerMinimized.value || !miniPosition.value || !player) return;
      const rect = player.getBoundingClientRect();
      const maxWidth = Math.max(160, Math.min(
        window.innerWidth - 16,
        (playerBottomEdge() - 64) * miniAspectRatio
      ));
      const width = Math.min(rect.width, maxWidth);
      const height = width / miniAspectRatio;

      if (miniSize.value) miniSize.value = { width, height };
      miniPosition.value = clampPosition(
        miniPosition.value.x,
        miniPosition.value.y,
        width,
        height
      );
    }

    window.addEventListener('resize', keepMiniPlayerOnScreen);
    onBeforeUnmount(() => window.removeEventListener('resize', keepMiniPlayerOnScreen));

    const videoPlayerStyle = computed(() => ({
      ...props.app.playerBarStyle.value,
      ...(props.app.videoPlayerMinimized.value && miniPosition.value
        ? {
            left: `${miniPosition.value.x}px`,
            top: `${miniPosition.value.y}px`,
            right: 'auto',
            bottom: 'auto'
          }
        : {}),
      ...(props.app.videoPlayerMinimized.value && miniSize.value
        ? {
            width: `${miniSize.value.width}px`,
            height: `${miniSize.value.height}px`
          }
        : {})
    }));

    return {
      ...props.app,
      playerRef,
      dragging,
      resizing,
      videoPlayerStyle,
      onVideoDragStart,
      onVideoDrag,
      onVideoDragEnd,
      onVideoResizeStart,
      onVideoResize,
      onVideoResizeEnd
    };
  }
};
</script>

<template>
  <Transition name="video-player">
      <section
        ref="playerRef"
        v-show="showVideoPlayer"
        class="video-player"
        :class="{
          'video-player--mini': videoPlayerMinimized,
          'video-player--dragging': dragging,
          'video-player--resizing': resizing
        }"
        :style="videoPlayerStyle"
        @pointerdown="onVideoDragStart"
        @pointermove="onVideoDrag"
        @pointerup="onVideoDragEnd"
        @pointercancel="onVideoDragEnd"
      >
        <video
          ref="videoRef"
          class="video-player__media"
          :poster="activeTrack?.thumbnail || nowArtworkImage"
          crossorigin="anonymous"
          playsinline
          preload="auto"
          @timeupdate="onAudioTime"
          @loadedmetadata="onAudioLoaded"
          @waiting="onAudioWaiting"
          @playing="onAudioPlaying"
          @canplay="onAudioCanPlay"
          @play="onAudioPlay"
          @pause="onAudioPause"
          @error="onAudioError"
          @ended="onAudioEnded"
        />
        <audio ref="videoAudioRef" class="video-player__audio" crossorigin="anonymous" preload="auto" />
        <div class="video-player__chrome">
          <div class="video-player__copy">
            <strong class="explicit-title">
              <span class="explicit-title__text">{{ activeTrack?.title }}</span>
              <ExplicitBadge :explicit="activeTrack?.explicit" />
            </strong>
            <span>{{ activeTrackIsLive ? 'Live' : activeArtist || itemMeta(activeTrack || {}) }}</span>
          </div>
          <div class="video-player__actions">
            <q-btn
              v-if="videoPlayerMinimized"
              flat
              round
              dense
              icon="fullscreen"
              title="Expand video"
              @click="expandVideoPlayer"
            />
            <q-btn
              v-else
              flat
              round
              dense
              icon="picture_in_picture_alt"
              title="Minimize video"
              @click="minimizeVideoPlayer"
            />
          </div>
        </div>
        <span
          v-if="videoPlayerMinimized"
          class="video-player__resize-handle"
          role="separator"
          aria-label="Resize video"
          @pointerdown.stop="onVideoResizeStart"
          @pointermove.stop="onVideoResize"
          @pointerup.stop="onVideoResizeEnd"
          @pointercancel.stop="onVideoResizeEnd"
        />
      </section>
    </Transition>
</template>
