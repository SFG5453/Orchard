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
import { computed } from 'vue';

export default {
  name: 'CompactSettingsMenu',
  props: {
    app: {
      type: Object,
      required: true
    }
  },
  setup(props) {
    const eqMode = computed({
      get() {
        if (!props.app.audioEngineConfig.value.enabled) return 'off';
        if (props.app.audioEngineConfig.value.autoEqEnabled) return 'auto';
        if (props.app.audioEngineConfig.value.eqEnabled) return 'manual';
        return 'off';
      },
      set(val) {
        if (val === 'off') {
          props.app.setAutoEqEnabled(false);
          props.app.setManualEqEnabled(false);
        } else if (val === 'auto') {
          props.app.setAutoEqEnabled(true);
        } else if (val === 'manual') {
          props.app.setManualEqEnabled(true);
        }
      }
    });

    return {
      eqMode,
      crossfadeEnabled: props.app.crossfadeEnabled,
      crossfadeMode: props.app.crossfadeMode,
      crossfadeModeOptions: props.app.crossfadeModeOptions,
      discordRpcEnabled: props.app.discordRpcEnabled
    };
  }
};
</script>

<template>
  <div class="compact-settings-menu" aria-label="Quick Settings">
    <div class="compact-settings-header">
      <q-icon name="settings" size="16px" />
      <span>Quick Settings</span>
    </div>

    <q-separator dark />

    <div class="compact-settings-sections">
      <!-- Equalizer Section -->
      <div class="compact-settings-section">
        <div class="compact-settings-row">
          <label class="compact-settings-label">Equalizer Mode</label>
          <div class="settings-option-group" role="group" aria-label="Equalizer Mode">
            <button
              v-for="option in [{ label: 'Off', value: 'off' }, { label: 'Auto', value: 'auto' }, { label: 'Manual', value: 'manual' }]"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': eqMode === option.value }"
              :aria-label="`Equalizer mode: ${option.label}`"
              :aria-pressed="eqMode === option.value"
              @click="eqMode = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>
      </div>

      <q-separator dark />

      <!-- Crossfade Section -->
      <div class="compact-settings-section">
        <div class="compact-settings-row">
          <label for="compact-crossfade-toggle" class="compact-settings-label">Crossfade</label>
          <q-toggle
            id="compact-crossfade-toggle"
            v-model="crossfadeEnabled"
            color="primary"
            dense
            aria-label="Toggle crossfade"
          />
        </div>
        <div class="compact-settings-row" :class="{ 'compact-settings-row--disabled': !crossfadeEnabled }">
          <span class="compact-settings-label">Crossfade Mode</span>
          <div class="settings-option-group" role="group" aria-label="Crossfade Mode">
            <button
              v-for="option in crossfadeModeOptions"
              :key="option.value"
              type="button"
              class="settings-option"
              :class="{ 'settings-option--active': crossfadeMode === option.value }"
              :disabled="!crossfadeEnabled"
              :aria-label="`Crossfade mode: ${option.label}`"
              :aria-pressed="crossfadeMode === option.value"
              @click="crossfadeMode = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>
      </div>

      <q-separator dark />

      <!-- Discord RPC Section -->
      <div class="compact-settings-section">
        <div class="compact-settings-row">
          <label for="compact-discord-toggle" class="compact-settings-label">Discord Presence</label>
          <q-toggle
            id="compact-discord-toggle"
            v-model="discordRpcEnabled"
            color="primary"
            dense
            aria-label="Toggle Discord Rich Presence"
          />
        </div>
      </div>
    </div>
  </div>
</template>
