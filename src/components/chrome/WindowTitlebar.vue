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
export default {
  name: 'WindowTitlebar',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>

    <header class="window-titlebar" data-tauri-drag-region="deep">
      <div class="titlebar-transport">
        <q-btn flat round dense icon="skip_previous" :disable="!activeTrack || buffering" @click="playPrevious" />
        <q-btn
          flat
          round
          dense
          :loading="buffering"
          :disable="!activeTrack"
          :icon="isPlaying ? 'pause' : 'play_arrow'"
          @click="togglePlayback"
        />
        <q-btn flat round dense icon="skip_next" :disable="!queue.length || buffering" @click="playNext" />
      </div>
      <div class="window-titlebar__brand">
        <img class="titlebar-orchard-mark" :src="orchardLogoUrl" alt="Orchard" />
      </div>
      <div class="window-titlebar__controls">
        <button type="button" title="Minimize" aria-label="Minimize" @click="minimizeWindow">
          <q-icon name="remove" />
        </button>
        <button type="button" title="Maximize" aria-label="Maximize" @click="toggleMaximizeWindow">
          <q-icon name="crop_square" />
        </button>
        <button type="button" title="Close" aria-label="Close" class="window-titlebar__close" @click="closeWindow">
          <q-icon name="close" />
        </button>
      </div>
    </header>

</template>
