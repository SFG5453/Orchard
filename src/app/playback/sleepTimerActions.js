import { computed, ref } from 'vue';

const SLEEP_TIMER_FADE_SECONDS = 10;
const SLEEP_TIMER_INTERVAL_MS = 250;

function clamp01(value) {
  return Math.max(0, Math.min(1, Number(value) || 0));
}

function timerClock(seconds) {
  const wholeSeconds = Math.max(0, Math.ceil(Number(seconds) || 0));
  const minutes = Math.floor(wholeSeconds / 60);
  const remainder = String(wholeSeconds % 60).padStart(2, '0');
  return `${minutes}:${remainder}`;
}

export function installSleepTimerActions(ctx) {
  ctx.sleepTimerOptions = [15, 30, 45, 60].map((minutes) => ({
    label: `${minutes} min`,
    value: String(minutes),
    minutes
  }));
  ctx.sleepTimerMode = ref('off');
  ctx.sleepTimerDeadline = ref(0);
  ctx.sleepTimerRemainingSeconds = ref(0);
  ctx.sleepTimerTrackId = ref('');
  ctx.sleepTimerVolumeFactor = ref(1);
  ctx.sleepTimerInterval = 0;

  ctx.sleepTimerActive = computed(() => ctx.sleepTimerMode.value !== 'off');
  ctx.sleepTimerStatus = computed(() => (
    ctx.sleepTimerMode.value === 'end-track'
      ? 'End of song'
      : timerClock(ctx.sleepTimerRemainingSeconds.value)
  ));
  ctx.sleepTimerSummary = computed(() => {
    if (ctx.sleepTimerMode.value === 'end-track') return 'Orchard will pause when this song ends.';
    if (ctx.sleepTimerMode.value !== 'off') return `Orchard will pause in ${ctx.sleepTimerStatus.value}.`;
    return 'Pause playback after a set time or when the current song ends.';
  });

  ctx.effectivePlaybackVolume = function effectivePlaybackVolume(value = ctx.volume.value) {
    return clamp01(value) * ctx.sleepTimerVolumeFactor.value;
  };

  ctx.applySleepTimerVolume = function applySleepTimerVolume(factor) {
    const nextFactor = clamp01(factor);
    if (Math.abs(nextFactor - ctx.sleepTimerVolumeFactor.value) < 0.005) return;

    ctx.sleepTimerVolumeFactor.value = nextFactor;
    const media = ctx.currentPlaybackAudioElement?.() || ctx.currentPlaybackElement?.();
    if (media) ctx.audioAnalyzer.setVolume(media, ctx.effectivePlaybackVolume());
  };

  ctx.clearSleepTimerInterval = function clearSleepTimerInterval() {
    window.clearInterval(ctx.sleepTimerInterval);
    ctx.sleepTimerInterval = 0;
  };

  ctx.cancelSleepTimer = function cancelSleepTimer() {
    ctx.clearSleepTimerInterval();
    ctx.sleepTimerMode.value = 'off';
    ctx.sleepTimerDeadline.value = 0;
    ctx.sleepTimerRemainingSeconds.value = 0;
    ctx.sleepTimerTrackId.value = '';
    ctx.applySleepTimerVolume(1);
    ctx.setCurrentAudioVolume?.();
  };

  ctx.finishSleepTimer = function finishSleepTimer() {
    const media = ctx.currentPlaybackElement?.();
    media?.pause();
    if (ctx.activeTrackIsVideo.value) ctx.videoAudioRef.value?.pause();
    ctx.isPlaying.value = false;
    ctx.cancelSleepTimer();
  };

  ctx.updateSleepTimer = function updateSleepTimer() {
    if (ctx.sleepTimerMode.value === 'off') return;

    const remaining = ctx.sleepTimerMode.value === 'end-track'
      ? Math.max(0, ctx.duration.value - ctx.currentTime.value)
      : Math.max(0, (ctx.sleepTimerDeadline.value - Date.now()) / 1000);

    if (ctx.sleepTimerMode.value !== 'end-track') {
      ctx.sleepTimerRemainingSeconds.value = Math.ceil(remaining);
      if (remaining <= 0) {
        ctx.finishSleepTimer();
        return;
      }
    }

    const canFade = ctx.isPlaying.value && remaining > 0 && remaining <= SLEEP_TIMER_FADE_SECONDS;
    ctx.applySleepTimerVolume(canFade ? remaining / SLEEP_TIMER_FADE_SECONDS : 1);
  };

  ctx.startSleepTimer = function startSleepTimer(mode) {
    const normalizedMode = String(mode || '');
    const option = ctx.sleepTimerOptions.find((item) => item.value === normalizedMode);
    if (normalizedMode === 'end-track' && !ctx.activeTrack.value) return;
    if (!option && normalizedMode !== 'end-track') return;

    ctx.cancelSleepTimer();
    ctx.sleepTimerMode.value = normalizedMode;
    ctx.sleepTimerTrackId.value = normalizedMode === 'end-track' ? ctx.activeTrack.value?.id || '' : '';
    ctx.sleepTimerDeadline.value = option ? Date.now() + (option.minutes * 60 * 1000) : 0;
    ctx.updateSleepTimer();
    ctx.sleepTimerInterval = window.setInterval(ctx.updateSleepTimer, SLEEP_TIMER_INTERVAL_MS);
  };

  ctx.completeSleepTimerAfterTrack = function completeSleepTimerAfterTrack() {
    if (ctx.sleepTimerMode.value !== 'end-track') return false;
    ctx.finishSleepTimer();
    return true;
  };

  ctx.handleSleepTimerTrackChange = function handleSleepTimerTrackChange(track) {
    if (
      ctx.sleepTimerMode.value === 'end-track' &&
      ctx.sleepTimerTrackId.value &&
      track?.id !== ctx.sleepTimerTrackId.value
    ) {
      ctx.cancelSleepTimer();
    }
  };

  ctx.openSleepTimerSettings = function openSleepTimerSettings() {
    ctx.navigateToView('settings');
    window.setTimeout(() => document.getElementById('settings-sleep-timer')?.scrollIntoView({ block: 'center' }), 0);
  };

  ctx.destroySleepTimer = function destroySleepTimer() {
    ctx.clearSleepTimerInterval();
    ctx.applySleepTimerVolume(1);
  };
}
