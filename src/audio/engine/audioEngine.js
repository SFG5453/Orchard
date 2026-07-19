export const AUDIO_ENGINE_STORAGE_KEY = 'orchard:audio-engine';

export const EQ_BANDS = [
  { frequency: 31, label: '31' },
  { frequency: 62, label: '62' },
  { frequency: 125, label: '125' },
  { frequency: 250, label: '250' },
  { frequency: 500, label: '500' },
  { frequency: 1000, label: '1k' },
  { frequency: 2000, label: '2k' },
  { frequency: 4000, label: '4k' },
  { frequency: 8000, label: '8k' },
  { frequency: 16000, label: '16k' }
];

export const EQ_PRESETS = {
  flat: { label: 'Flat', gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] },
  bass: { label: 'Bass boost', gains: [6, 5, 4, 2, 0, -1, -1, 0, 1, 2] },
  electronic: { label: 'Electronic', gains: [5, 4, 1, 0, -2, 1, 2, 3, 4, 4] },
  rock: { label: 'Rock', gains: [4, 3, 2, 0, -1, 1, 3, 4, 4, 3] },
  vocal: { label: 'Vocal', gains: [-3, -2, -1, 0, 2, 4, 5, 3, 1, 0] },
  acoustic: { label: 'Acoustic', gains: [2, 2, 1, 0, 2, 3, 3, 2, 2, 1] },
  bright: { label: 'Bright', gains: [-2, -1, 0, 0, 1, 2, 3, 4, 5, 5] }
};

export const DEFAULT_AUDIO_ENGINE_CONFIG = {
  enabled: true,
  autoEqEnabled: false,
  eqEnabled: false,
  gains: [...EQ_PRESETS.flat.gains],
  preampDb: 0,
  q: 1.1,
  balance: 0,
  outputDeviceId: 'default'
};

function clamp(value, min, max, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(min, Math.min(max, number)) : fallback;
}

export function dbToGain(value) {
  return 10 ** (clamp(value, -60, 24) / 20);
}

export function normalizeAudioEngineConfig(value = {}) {
  const autoEqEnabled = typeof value.autoEqEnabled === 'boolean'
    ? value.autoEqEnabled
    : DEFAULT_AUDIO_ENGINE_CONFIG.autoEqEnabled;
  return {
    enabled: typeof value.enabled === 'boolean' ? value.enabled : DEFAULT_AUDIO_ENGINE_CONFIG.enabled,
    autoEqEnabled,
    eqEnabled: autoEqEnabled
      ? false
      : (typeof value.eqEnabled === 'boolean' ? value.eqEnabled : DEFAULT_AUDIO_ENGINE_CONFIG.eqEnabled),
    gains: EQ_BANDS.map((_, index) => clamp(value.gains?.[index], -12, 12)),
    preampDb: clamp(value.preampDb, -12, 6),
    q: clamp(value.q, 0.4, 2.4, DEFAULT_AUDIO_ENGINE_CONFIG.q),
    balance: clamp(value.balance, -1, 1),
    outputDeviceId: String(value.outputDeviceId || 'default')
  };
}

export function readAudioEngineState() {
  const fallback = { config: normalizeAudioEngineConfig(), trackGains: {} };
  if (typeof window === 'undefined' || !window.localStorage) return fallback;

  try {
    const stored = JSON.parse(window.localStorage.getItem(AUDIO_ENGINE_STORAGE_KEY) || '{}');
    return {
      config: normalizeAudioEngineConfig(stored.config),
      trackGains: stored.trackGains && typeof stored.trackGains === 'object' ? stored.trackGains : {}
    };
  } catch {
    return fallback;
  }
}

export function createAudioEngine(initialConfig = {}) {
  let config = normalizeAudioEngineConfig(initialConfig);
  let autoEqGains = EQ_BANDS.map(() => 0);
  let context = null;
  const processors = new Set();
  const pendingTrackGains = new WeakMap();

  function applyProcessor(processor) {
    const now = processor.context.currentTime;
    const useEq = config.enabled && (config.eqEnabled || config.autoEqEnabled);
    const gains = config.autoEqEnabled ? autoEqGains : config.gains;
    processor.dryGain.gain.setTargetAtTime(useEq ? 0 : 1, now, 0.012);
    processor.wetGain.gain.setTargetAtTime(useEq ? 1 : 0, now, 0.012);
    processor.preamp.gain.setTargetAtTime(config.enabled && config.eqEnabled ? dbToGain(config.preampDb) : 1, now, 0.012);
    processor.balance.pan.setTargetAtTime(config.enabled ? config.balance : 0, now, 0.012);
    processor.filters.forEach((filter, index) => {
      filter.gain.setTargetAtTime(useEq ? gains[index] : 0, now, config.autoEqEnabled ? 0.35 : 0.012);
      filter.Q.setTargetAtTime(config.autoEqEnabled ? DEFAULT_AUDIO_ENGINE_CONFIG.q : config.q, now, 0.012);
    });
    const trackDb = config.enabled ? processor.trackGainDb : 0;
    processor.trackGain.gain.setTargetAtTime(dbToGain(trackDb), now, 0.012);
  }

  function createProcessor({ context: audioContext, source, element }) {
    context = audioContext;
    const input = audioContext.createGain();
    const dryGain = audioContext.createGain();
    const wetGain = audioContext.createGain();
    const preamp = audioContext.createGain();
    const balance = audioContext.createStereoPanner();
    const trackGain = audioContext.createGain();
    const output = audioContext.createGain();
    const filters = EQ_BANDS.map(({ frequency }) => {
      const filter = audioContext.createBiquadFilter();
      filter.type = 'peaking';
      filter.frequency.value = frequency;
      return filter;
    });

    source.connect(input);
    input.connect(dryGain);
    dryGain.connect(balance);
    input.connect(preamp);
    let previous = preamp;
    filters.forEach((filter) => {
      previous.connect(filter);
      previous = filter;
    });
    previous.connect(wetGain);
    wetGain.connect(balance);
    balance.connect(trackGain);
    trackGain.connect(output);

    const processor = {
      context: audioContext,
      element,
      filters,
      dryGain,
      wetGain,
      preamp,
      balance,
      trackGain,
      output,
      trackGainDb: pendingTrackGains.get(element) || 0
    };
    processors.add(processor);
    applyProcessor(processor);
    if (config.outputDeviceId !== 'default' && typeof audioContext.setSinkId === 'function') {
      audioContext.setSinkId(config.outputDeviceId).catch(() => {});
    }
    return processor;
  }

  function update(nextConfig) {
    config = normalizeAudioEngineConfig(nextConfig);
    processors.forEach(applyProcessor);
  }

  function setTrackGain(element, value) {
    if (!element) return;
    const gainDb = clamp(value, -12, 12);
    pendingTrackGains.set(element, gainDb);
    for (const processor of processors) {
      if (processor.element !== element) continue;
      processor.trackGainDb = gainDb;
      applyProcessor(processor);
    }
  }

  function setAutoEqGains(values = []) {
    autoEqGains = EQ_BANDS.map((_, index) => clamp(values[index], -3, 3));
    processors.forEach(applyProcessor);
  }

  async function setOutputDevice(deviceId = 'default') {
    if (!context || typeof context.setSinkId !== 'function') return false;
    await context.setSinkId(deviceId === 'default' ? '' : deviceId);
    return true;
  }

  return { createProcessor, setAutoEqGains, setOutputDevice, setTrackGain, update };
}
