/*
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
 */

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
