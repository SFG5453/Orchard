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
  name: 'DataSavingSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const app = props.app;
    const streamQualityDescription = computed(() => {
      const active = app.streamQualityOptions
        .find((option) => option.value === app.streamQuality.value);
      return active?.description || '';
    });

    return { ...app, app, streamQualityDescription };
  }
};
</script>

<template>
  <section id="settings-data-saving" class="settings-section" aria-labelledby="settings-data-saving-title">
    <div class="settings-section__heading">
      <h2 id="settings-data-saving-title">Data Saving</h2>
      <p>Spend less on a metered or slow connection.</p>
    </div>

    <div class="settings-row settings-row--options">
      <div class="settings-row__copy">
        <label id="settings-stream-quality-label">Streaming quality</label>
        <p>{{ streamQualityDescription }}</p>
      </div>
      <div class="settings-option-group" role="group" aria-labelledby="settings-stream-quality-label">
        <button
          v-for="option in streamQualityOptions"
          :key="option.value"
          type="button"
          class="settings-option"
          :class="{ 'settings-option--active': streamQuality === option.value }"
          :aria-pressed="streamQuality === option.value"
          @click="streamQuality = option.value"
        >
          {{ option.label }}
        </button>
      </div>
    </div>

    <div class="settings-row">
      <div class="settings-row__copy">
        <label for="settings-video-playback">Play music videos</label>
        <p>Off plays the audio-only album version of every song and hides music video shelves.</p>
      </div>
      <q-toggle id="settings-video-playback" v-model="videoPlaybackEnabled" color="primary" aria-label="Play music videos" />
    </div>

    <div class="settings-row">
      <div class="settings-row__copy">
        <label for="settings-animated-artwork">Animated artwork</label>
        <p>Moving covers and Spotify canvases run tens of megabytes each. Off keeps still artwork only.</p>
      </div>
      <q-toggle id="settings-animated-artwork" v-model="animatedArtworkEnabled" color="primary" aria-label="Animated artwork" />
    </div>
  </section>
</template>
