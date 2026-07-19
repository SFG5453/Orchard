<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { gsap } from 'gsap';
import { PixiArtworkBackground } from './PixiArtworkBackground.js';
import { VideoArtworkBackground } from './VideoArtworkBackground.js';
import { useBackgroundVisibility } from './useBackgroundVisibility.js';

const props = defineProps({
  animatedArtworkUrl: { type: String, default: '' },
  artworkUrl: { type: String, default: '' },
  enabled: { type: Boolean, default: true },
  motionEnabled: { type: Boolean, default: true },
  playing: { type: Boolean, default: false }
});

const rootRef = ref(null);
const canvasRef = ref(null);
const videoRef = ref(null);
let pixiBackground = null;
let videoBackground = null;
let visibility = null;
let windowVisible = true;
let reducedMotion = false;
let mounted = false;

function playbackAllowed() {
  return props.enabled && props.playing && props.motionEnabled && windowVisible && !reducedMotion;
}

function revealVideo() {
  const video = videoRef.value;
  if (!video) return;
  gsap.killTweensOf(video);
  gsap.to(video, { opacity: 1, duration: reducedMotion ? 0 : 1.8, ease: 'sine.inOut' });
}

function hideVideo() {
  const video = videoRef.value;
  if (!video) return;
  gsap.killTweensOf(video);
  gsap.to(video, { opacity: 0, duration: reducedMotion ? 0 : 0.35, ease: 'sine.out' });
}

function syncPlayback() {
  if (!mounted) return;
  pixiBackground?.setEnabled(props.enabled);
  pixiBackground?.setPlaying(props.playing);
  pixiBackground?.setMotionEnabled(props.motionEnabled);
  videoBackground?.setPlaybackAllowed(playbackAllowed());
}

function syncSources() {
  if (!mounted) return;
  pixiBackground?.setArtwork(props.enabled ? props.artworkUrl : '');
  videoBackground?.setSource(props.enabled ? props.animatedArtworkUrl : '');
}

watch(
  () => [props.artworkUrl, props.animatedArtworkUrl, props.enabled],
  () => {
    syncSources();
    syncPlayback();
  }
);
watch(() => [props.playing, props.motionEnabled], syncPlayback);

onMounted(() => {
  mounted = true;
  pixiBackground = new PixiArtworkBackground(canvasRef.value);
  videoBackground = new VideoArtworkBackground(videoRef.value, {
    onReady: revealVideo,
    onFallback: hideVideo
  });
  visibility = useBackgroundVisibility(rootRef.value, {
    onVisibility(value) {
      windowVisible = value;
      pixiBackground?.setVisible(value);
      syncPlayback();
    },
    onReducedMotion(value) {
      reducedMotion = value;
      pixiBackground?.setReducedMotion(value);
      syncPlayback();
    }
  });
  visibility.start();
  syncSources();
  syncPlayback();
  void pixiBackground.initialize();
});

onBeforeUnmount(() => {
  mounted = false;
  visibility?.destroy();
  videoBackground?.destroy();
  pixiBackground?.destroy();
  gsap.killTweensOf(videoRef.value);
  visibility = null;
  videoBackground = null;
  pixiBackground = null;
});
</script>

<template>
  <div ref="rootRef" class="immersive-background" aria-hidden="true">
    <canvas ref="canvasRef" class="immersive-background__canvas" />
    <video
      ref="videoRef"
      class="immersive-background__video"
      autoplay
      loop
      muted
      playsinline
      preload="auto"
    />
  </div>
</template>
