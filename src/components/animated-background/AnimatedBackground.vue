<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { gsap } from 'gsap';
import { KawarpArtworkBackground } from './KawarpArtworkBackground.js';
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
let kawarpBackground = null;
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
  kawarpBackground?.setEnabled(props.enabled);
  kawarpBackground?.setPlaying(props.playing);
  kawarpBackground?.setMotionEnabled(props.motionEnabled);
  videoBackground?.setPlaybackAllowed(playbackAllowed());
}

function syncSources() {
  if (!mounted) return;
  kawarpBackground?.setArtwork(props.enabled ? props.artworkUrl : '');
  // Static mode must not leave animated artwork paused on its final frame.
  // That reads as a flat image plane instead of an ambient background.
  videoBackground?.setSource(props.enabled && props.motionEnabled ? props.animatedArtworkUrl : '');
}

function resizeBackground() {
  kawarpBackground?.resize();
  syncPlayback();
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
  kawarpBackground = new KawarpArtworkBackground(canvasRef.value);
  videoBackground = new VideoArtworkBackground(videoRef.value, {
    onReady: revealVideo,
    onFallback: hideVideo
  });
  visibility = useBackgroundVisibility(rootRef.value, {
    onVisibility(value) {
      windowVisible = value;
      kawarpBackground?.setVisible(value);
      syncPlayback();
    },
    onReducedMotion(value) {
      reducedMotion = value;
      kawarpBackground?.setReducedMotion(value);
      syncPlayback();
    }
  });
  visibility.start();
  kawarpBackground.initialize();
  syncSources();
  syncPlayback();
  window.addEventListener('resize', resizeBackground);
});

onBeforeUnmount(() => {
  mounted = false;
  window.removeEventListener('resize', resizeBackground);
  visibility?.destroy();
  videoBackground?.destroy();
  kawarpBackground?.destroy();
  gsap.killTweensOf(videoRef.value);
  visibility = null;
  videoBackground = null;
  kawarpBackground = null;
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
