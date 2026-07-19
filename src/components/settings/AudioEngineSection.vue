<script>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

export default {
  name: 'AudioEngineSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const spectrum = ref(Array.from({ length: 32 }, () => 0));
    const profileInput = ref(null);
    let animationFrame = 0;

    const autoEqStatus = computed(() => {
      const profile = props.app.audioEngineAutoProfile.value || props.app.audioEngineAutoProfile;
      const parts = [
        profile?.learned ? 'Using learned profile' : 'Learning this track',
        `${profile?.profileCount || 0} saved profile${profile?.profileCount === 1 ? '' : 's'}`
      ];
      if (profile?.tempo) parts.push(`${Math.round(profile.tempo)} BPM`);
      return parts.join(' · ');
    });

    const responsePath = computed(() => {
      const config = props.app.audioEngineConfig.value || props.app.audioEngineConfig;
      const autoGains = props.app.audioEngineAutoGains.value || props.app.audioEngineAutoGains;
      const gains = config?.autoEqEnabled ? autoGains : (config?.gains || []);
      const points = gains.map((gain, index) => {
        const x = 8 + (index * 284) / Math.max(1, gains.length - 1);
        const y = Math.max(8, Math.min(112, 60 - Number(gain || 0) * 4));
        return `${x},${y}`;
      });
      return points.length ? `M ${points.join(' L ')}` : 'M 8,60 L 292,60';
    });

    function updateSpectrum() {
      const next = props.app.audioSpectrum?.(32) || [];
      spectrum.value = spectrum.value.map((value, index) => Math.max(next[index] || 0, value * 0.78));
      animationFrame = window.requestAnimationFrame(updateSpectrum);
    }

    async function importProfile(event) {
      try {
        await props.app.importAudioEngineProfile(event.target.files?.[0]);
      } catch (error) {
        props.app.audioEngineMessage.value = error.message || 'Could not import that audio profile.';
      } finally {
        event.target.value = '';
      }
    }

    onMounted(() => {
      updateSpectrum();
      props.app.loadAudioOutputDevices?.();
    });
    onBeforeUnmount(() => window.cancelAnimationFrame(animationFrame));

    return { ...props.app, spectrum, profileInput, responsePath, autoEqStatus, importProfile };
  }
};
</script>

<template>
  <section id="settings-audio-engine" class="settings-section audio-engine" aria-labelledby="settings-audio-engine-title">
    <div class="settings-section__heading audio-engine__heading">
      <div>
        <h2 id="settings-audio-engine-title">Audio Engine</h2>
        <p>Shape Orchard's sound and keep track-level adjustments between sessions.</p>
      </div>
      <q-toggle v-model="audioEngineConfig.enabled" color="primary" aria-label="Audio Engine" />
    </div>

    <div class="audio-engine__monitor" :class="{ 'audio-engine__monitor--bypassed': !audioEngineConfig.enabled }">
      <div class="audio-engine__spectrum" aria-hidden="true">
        <i v-for="(level, index) in spectrum" :key="index" :style="{ height: `${Math.max(3, level * 100)}%` }"></i>
      </div>
      <svg class="audio-engine__response" viewBox="0 0 300 120" preserveAspectRatio="none" aria-label="Equalizer response curve">
        <line x1="0" y1="60" x2="300" y2="60" />
        <path :d="responsePath" />
      </svg>
      <span>{{ audioEngineConfig.enabled ? (audioEngineConfig.autoEqEnabled ? 'Auto EQ active' : audioEngineConfig.eqEnabled ? 'Manual EQ active' : 'Direct') : 'Bypassed' }}</span>
    </div>

    <div class="settings-row">
      <div class="settings-row__copy">
        <label for="settings-engine-auto-eq">Automatic EQ</label>
        <p>Gently balance bass, mids, and treble as each track plays.</p>
      </div>
      <q-toggle
        id="settings-engine-auto-eq"
        :model-value="audioEngineConfig.autoEqEnabled"
        :disable="!audioEngineConfig.enabled"
        color="primary"
        @update:model-value="setAutoEqEnabled"
      />
    </div>
    <p v-if="audioEngineConfig.autoEqEnabled" class="audio-engine__auto-status">
      {{ autoEqStatus }}
    </p>

    <div class="settings-row">
      <div class="settings-row__copy">
        <label for="settings-engine-eq">Manual equalizer</label>
        <p>Use presets or shape the ten-band curve yourself.</p>
      </div>
      <q-toggle
        id="settings-engine-eq"
        :model-value="audioEngineConfig.eqEnabled"
        :disable="!audioEngineConfig.enabled"
        color="primary"
        @update:model-value="setManualEqEnabled"
      />
    </div>

    <div class="audio-engine__presets" :class="{ 'audio-engine__disabled': !audioEngineConfig.enabled || !audioEngineConfig.eqEnabled }">
      <button
        v-for="preset in audioEnginePresets"
        :key="preset.value"
        type="button"
        class="settings-option"
        :class="{ 'settings-option--active': audioEngineActivePreset === preset.value }"
        :aria-pressed="audioEngineActivePreset === preset.value"
        :disabled="!audioEngineConfig.enabled || !audioEngineConfig.eqEnabled"
        @click="applyAudioEnginePreset(preset.value)"
      >{{ preset.label }}</button>
    </div>

    <div class="audio-engine__equalizer" :class="{ 'audio-engine__disabled': !audioEngineConfig.enabled || !audioEngineConfig.eqEnabled }">
      <label v-for="(band, index) in audioEngineBands" :key="band.frequency" class="audio-engine__band">
        <output>{{ Number(audioEngineConfig.gains[index]).toFixed(1) }}</output>
        <input
          v-model.number="audioEngineConfig.gains[index]"
          type="range"
          min="-12"
          max="12"
          step="0.5"
          :disabled="!audioEngineConfig.enabled || !audioEngineConfig.eqEnabled"
          :aria-label="`${band.frequency} hertz gain`"
        />
        <span>{{ band.label }}</span>
      </label>
    </div>

    <div class="audio-engine__control-grid">
      <label class="audio-engine__control">
        <span>Preamp</span>
        <input v-model.number="audioEngineConfig.preampDb" type="range" min="-12" max="6" step="0.5" :disabled="!audioEngineConfig.enabled" />
        <output>{{ Number(audioEngineConfig.preampDb).toFixed(1) }} dB</output>
      </label>
      <label class="audio-engine__control">
        <span>Band width</span>
        <input v-model.number="audioEngineConfig.q" type="range" min="0.4" max="2.4" step="0.1" :disabled="!audioEngineConfig.enabled" />
        <output>Q {{ Number(audioEngineConfig.q).toFixed(1) }}</output>
      </label>
      <label class="audio-engine__control">
        <span>Balance</span>
        <input v-model.number="audioEngineConfig.balance" type="range" min="-1" max="1" step="0.05" :disabled="!audioEngineConfig.enabled" />
        <output>{{ audioEngineConfig.balance === 0 ? 'Center' : (audioEngineConfig.balance < 0 ? 'Left' : 'Right') }}</output>
      </label>
    </div>

    <div class="settings-row">
      <div class="settings-row__copy">
        <label for="settings-engine-leveling">Dynamic leveling</label>
        <p>Reduce sudden volume jumps and control loud peaks.</p>
      </div>
      <q-toggle id="settings-engine-leveling" v-model="volumeNormalizationEnabled" color="primary" />
    </div>

    <div class="settings-row settings-row--slider" :class="{ 'settings-row--disabled': !activeTrack || !audioEngineConfig.enabled }">
      <div class="settings-row__copy">
        <label for="settings-track-gain">Track gain</label>
        <p>{{ activeTrack ? `Remembered for ${activeTrack.title}` : 'Play a track to set its remembered gain.' }}</p>
      </div>
      <div class="settings-slider">
        <q-slider
          id="settings-track-gain"
          :model-value="activeTrackGainDb"
          :min="-12"
          :max="12"
          :step="0.5"
          :disable="!activeTrack || !audioEngineConfig.enabled"
          color="primary"
          @update:model-value="setActiveTrackGain"
        />
        <output>{{ Number(activeTrackGainDb).toFixed(1) }} dB</output>
      </div>
    </div>

    <div class="settings-row audio-engine__output-row">
      <div class="settings-row__copy">
        <label for="settings-audio-output">Output device</label>
        <p>Route Orchard independently when Chromium exposes system outputs.</p>
      </div>
      <select id="settings-audio-output" v-model="audioEngineConfig.outputDeviceId" class="audio-engine__select" :disabled="audioOutputLoading">
        <option v-for="device in audioOutputDevices" :key="device.deviceId" :value="device.deviceId">{{ device.label }}</option>
      </select>
    </div>

    <div class="audio-engine__actions">
      <button type="button" class="settings-button" @click="exportAudioEngineProfile"><q-icon name="download" />Export profile</button>
      <button type="button" class="settings-button" @click="profileInput?.click()"><q-icon name="upload" />Import profile</button>
      <button type="button" class="settings-link-button settings-link-button--danger" @click="resetAudioEngine"><q-icon name="restart_alt" />Reset engine</button>
      <input ref="profileInput" class="audio-engine__file" type="file" accept="application/json,.json" @change="importProfile" />
      <p v-if="audioEngineMessage" class="audio-engine__message" aria-live="polite">{{ audioEngineMessage }}</p>
    </div>
  </section>
</template>
