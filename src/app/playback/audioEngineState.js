import { ref } from 'vue';
import {
  createAudioEngine,
  EQ_BANDS,
  EQ_PRESETS,
  readAudioEngineState
} from '../../audio/engine/audioEngine.js';

export function installAudioEngineState(ctx) {
  const stored = readAudioEngineState();
  ctx.audioEngineConfig = ref(stored.config);
  ctx.audioEngineTrackGains = ref(stored.trackGains);
  ctx.audioEngine = createAudioEngine(stored.config);
  ctx.audioEngineBands = EQ_BANDS;
  ctx.audioEnginePresets = Object.entries(EQ_PRESETS).map(([value, preset]) => ({
    value,
    label: preset.label,
    gains: preset.gains
  }));
  ctx.audioEngineActivePreset = ref('flat');
  ctx.audioEngineAutoGains = ref(EQ_BANDS.map(() => 0));
  ctx.audioEngineAutoProfile = ref({
    learned: false,
    profileCount: 0,
    sampleCount: 0,
    tempo: null
  });
  ctx.activeTrackGainDb = ref(0);
  ctx.audioOutputDevices = ref([{ deviceId: 'default', label: 'System default' }]);
  ctx.audioOutputLoading = ref(false);
  ctx.audioEngineMessage = ref('');
}
