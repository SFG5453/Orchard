import { nextTick, onBeforeUnmount, watch } from 'vue';
import {
  AUDIO_ENGINE_STORAGE_KEY,
  DEFAULT_AUDIO_ENGINE_CONFIG,
  EQ_PRESETS,
  normalizeAudioEngineConfig
} from '../../audio/engine/audioEngine.js';
import { createAutomaticEq } from '../../audio/engine/automaticEq.js';
import { parseAudioEngineProfile } from '../../audio/engine/audioEngineSchemas.js';

function downloadJson(filename, data) {
  const blob = new Blob([`${JSON.stringify(data, null, 2)}\n`], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

function readFile(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(reader.error || new Error('Could not read the audio profile.'));
    reader.readAsText(file);
  });
}

export function installAudioEngineActions(ctx) {
  let autoEqTimer = 0;
  const automaticEq = createAutomaticEq({ analyzer: ctx.audioAnalyzer });

  function updateAutoEq() {
    const config = ctx.audioEngineConfig.value;
    if (!config.enabled || !config.autoEqEnabled || !ctx.isPlaying.value) return;
    const result = automaticEq.update(
      ctx.currentPlaybackAudioElement?.(),
      ctx.audioEngineAutoGains.value
    );
    if (!result) return;
    ctx.audioEngineAutoGains.value = result.gains;
    ctx.audioEngineAutoProfile.value = result.profile;
    ctx.audioEngine.setAutoEqGains(result.gains);
  }

  function trackGain(track) {
    return Math.max(-12, Math.min(12, Number(ctx.audioEngineTrackGains.value[track?.id]) || 0));
  }

  function persist() {
    try {
      window.localStorage.setItem(AUDIO_ENGINE_STORAGE_KEY, JSON.stringify({
        config: ctx.audioEngineConfig.value,
        trackGains: ctx.audioEngineTrackGains.value
      }));
    } catch {
      // Audio changes should continue to work when local storage is unavailable.
    }
  }

  ctx.syncAudioEngineDecks = function syncAudioEngineDecks() {
    const activeGain = trackGain(ctx.activeTrack.value);
    ctx.activeTrackGainDb.value = activeGain;
    ctx.audioEngine.setTrackGain(ctx.currentAudio?.(), activeGain);
    ctx.audioEngine.setTrackGain(ctx.standbyAudio?.(), trackGain(ctx.queue.value[0]));
    ctx.audioEngine.setTrackGain(ctx.videoRef.value, activeGain);
    ctx.audioEngine.setTrackGain(ctx.videoAudioRef.value, activeGain);
  };

  ctx.setActiveTrackGain = function setActiveTrackGain(value) {
    const trackId = ctx.activeTrack.value?.id;
    if (!trackId) return;
    const gainDb = Math.max(-12, Math.min(12, Number(value) || 0));
    ctx.activeTrackGainDb.value = gainDb;
    const next = { ...ctx.audioEngineTrackGains.value };
    if (Math.abs(gainDb) < 0.05) delete next[trackId];
    else next[trackId] = gainDb;
    ctx.audioEngineTrackGains.value = Object.fromEntries(Object.entries(next).slice(-500));
    ctx.syncAudioEngineDecks();
    persist();
  };

  ctx.applyAudioEnginePreset = function applyAudioEnginePreset(name) {
    const preset = EQ_PRESETS[name];
    if (!preset) return;
    ctx.audioEngineConfig.value = {
      ...ctx.audioEngineConfig.value,
      enabled: true,
      autoEqEnabled: false,
      eqEnabled: true,
      gains: [...preset.gains]
    };
    ctx.audioEngineActivePreset.value = name;
  };

  ctx.setAutoEqEnabled = function setAutoEqEnabled(enabled) {
    ctx.audioEngineConfig.value = {
      ...ctx.audioEngineConfig.value,
      enabled: enabled ? true : ctx.audioEngineConfig.value.enabled,
      autoEqEnabled: Boolean(enabled),
      eqEnabled: enabled ? false : ctx.audioEngineConfig.value.eqEnabled
    };
    if (!enabled) {
      ctx.audioEngineAutoGains.value = ctx.audioEngineAutoGains.value.map(() => 0);
      ctx.audioEngine.setAutoEqGains(ctx.audioEngineAutoGains.value);
    }
  };

  ctx.setManualEqEnabled = function setManualEqEnabled(enabled) {
    ctx.audioEngineConfig.value = {
      ...ctx.audioEngineConfig.value,
      enabled: enabled ? true : ctx.audioEngineConfig.value.enabled,
      autoEqEnabled: enabled ? false : ctx.audioEngineConfig.value.autoEqEnabled,
      eqEnabled: Boolean(enabled)
    };
  };

  ctx.resetAudioEngine = function resetAudioEngine() {
    ctx.audioEngineConfig.value = normalizeAudioEngineConfig(DEFAULT_AUDIO_ENGINE_CONFIG);
    ctx.audioEngineTrackGains.value = {};
    ctx.activeTrackGainDb.value = 0;
    ctx.audioEngineMessage.value = 'Audio Engine reset.';
    ctx.audioEngineAutoProfile.value = {
      learned: false,
      profileCount: 0,
      sampleCount: 0,
      tempo: null
    };
    ctx.syncAudioEngineDecks();
  };

  ctx.loadAudioOutputDevices = async function loadAudioOutputDevices() {
    if (!navigator.mediaDevices?.enumerateDevices) return;
    ctx.audioOutputLoading.value = true;
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const outputs = devices.filter((device) => device.kind === 'audiooutput');
      ctx.audioOutputDevices.value = [
        { deviceId: 'default', label: 'System default' },
        ...outputs
          .filter((device) => device.deviceId && device.deviceId !== 'default')
          .map((device, index) => ({
            deviceId: device.deviceId,
            label: device.label || `Audio output ${index + 1}`
          }))
      ];
    } catch (error) {
      ctx.audioEngineMessage.value = error.message || 'Could not list audio output devices.';
    } finally {
      ctx.audioOutputLoading.value = false;
    }
  };

  ctx.applyAudioOutputDevice = async function applyAudioOutputDevice(deviceId) {
    const target = deviceId || 'default';
    let applied = false;
    try {
      applied = await ctx.audioEngine.setOutputDevice(target);
      if (!applied) {
        const sinkId = target === 'default' ? '' : target;
        const elements = [ctx.audioRef.value, ctx.nextAudioRef.value, ctx.videoRef.value, ctx.videoAudioRef.value];
        await Promise.all(elements.filter((item) => typeof item?.setSinkId === 'function').map((item) => item.setSinkId(sinkId)));
      }
      ctx.audioEngineMessage.value = 'Output device updated.';
    } catch (error) {
      ctx.audioEngineMessage.value = error.message || 'Could not select that output device.';
    }
  };

  ctx.audioSpectrum = function audioSpectrum(size = 32) {
    return ctx.audioAnalyzer.spectrum(ctx.currentPlaybackAudioElement?.(), size);
  };

  ctx.exportAudioEngineProfile = function exportAudioEngineProfile() {
    downloadJson('orchard-audio-profile.json', {
      app: 'orchard',
      type: 'audio-engine-profile',
      version: 1,
      config: ctx.audioEngineConfig.value
    });
    ctx.audioEngineMessage.value = 'Audio profile exported.';
  };

  ctx.importAudioEngineProfile = async function importAudioEngineProfile(file) {
    if (!file) return;
    const profile = parseAudioEngineProfile(JSON.parse(await readFile(file)));
    ctx.audioEngineConfig.value = normalizeAudioEngineConfig(profile.config);
    ctx.audioEngineMessage.value = 'Audio profile imported.';
  };

  ctx.openAudioEngineSettings = async function openAudioEngineSettings() {
    ctx.selectView('settings');
    await nextTick();
    document.getElementById('settings-audio-engine')?.scrollIntoView({ behavior: 'smooth' });
  };

  watch(ctx.audioEngineConfig, (config) => {
    ctx.audioEngine.update(config);
    const match = Object.entries(EQ_PRESETS).find(([, preset]) =>
      preset.gains.every((gain, index) => Math.abs(gain - config.gains[index]) < 0.05));
    ctx.audioEngineActivePreset.value = match?.[0] || 'custom';
    persist();
  }, { deep: true, immediate: true });

  watch(() => ctx.audioEngineConfig.value.outputDeviceId, (deviceId) => {
    void ctx.applyAudioOutputDevice(deviceId);
  }, { immediate: true });

  watch([
    ctx.activeTrack,
    ctx.activeAudioDeck,
    () => ctx.queue.value[0]?.id || '',
    ctx.audioRef,
    ctx.nextAudioRef,
    ctx.videoRef,
    ctx.videoAudioRef
  ], ctx.syncAudioEngineDecks, { immediate: true });

  watch(() => ctx.activeTrack.value?.id || '', () => {
    ctx.audioEngineAutoGains.value = ctx.audioEngineAutoGains.value.map(() => 0);
    ctx.audioEngine.setAutoEqGains(ctx.audioEngineAutoGains.value);
    void automaticEq.beginTrack(ctx.activeTrack.value);
  }, { immediate: true });

  watch([ctx.audioRef, ctx.nextAudioRef, ctx.videoRef, ctx.videoAudioRef], () => {
    void ctx.applyAudioOutputDevice(ctx.audioEngineConfig.value.outputDeviceId);
  });

  autoEqTimer = window.setInterval(updateAutoEq, 900);
  onBeforeUnmount(() => {
    window.clearInterval(autoEqTimer);
    void automaticEq.persistCurrent();
  });
}
