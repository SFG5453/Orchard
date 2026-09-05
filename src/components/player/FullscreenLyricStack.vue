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
  name: 'FullscreenLyricStack',
  props: {
    app: { type: Object, required: true },
    interactive: { type: Boolean, default: true },
    items: { type: Array, default: () => [] },
    mode: { type: String, default: '' },
    scrollable: { type: Boolean, default: true },
    status: { type: String, default: 'idle' }
  }
};
</script>

<template>
  <div
    v-if="status === 'loading'"
    class="fullscreen-player__lyrics-message"
    aria-live="polite"
  >
    <q-spinner-dots size="38px" />
    <span>Loading lyrics</span>
  </div>

  <div
    v-else-if="status === 'ready'"
    class="fullscreen-player__lyrics-scroll"
    :tabindex="interactive ? -1 : undefined"
    :class="{
      'fullscreen-player__lyrics-scroll--static': !scrollable,
      'fullscreen-player__lyrics-scroll--synced': mode === 'synced'
    }"
    @scroll.passive="interactive && app.onLyricsUserScroll()"
    @wheel.passive="interactive && app.onLyricsUserScrollStart()"
    @touchstart.passive="interactive && app.onLyricsUserScrollStart()"
    @pointerdown="interactive && app.onLyricsPointerdown($event)"
  >
    <div v-if="mode !== 'synced' && interactive" class="fullscreen-player__unsynced-message">
      <q-icon name="info" />
      <span>These lyrics aren't synced to the music.</span>
    </div>

    <template v-for="item in items" :key="`fullscreen-${item.key}`">
      <button
        v-if="item.type === 'line' && item.canSeek && interactive"
        type="button"
        class="lyrics-line lyrics-line--button"
        :class="{
          'lyrics-line--active': item.active,
          'lyrics-line--word-synced': item.words?.length || item.adlibs?.length,
          'lyrics-line--alternate-agent': item.agentLane === 'alternate'
        }"
        @click="app.seekToLyric(item)"
      >
        <span v-if="item.words?.length" class="lyrics-line__words">
          <span
            v-for="word in item.words"
            :key="word.key"
            class="lyrics-word"
            :class="`lyrics-word--${word.state}`"
            :style="{ '--word-progress': word.progress }"
          >{{ word.text }}</span>
        </span>
        <span v-else>{{ item.text }}</span>
        <span v-if="item.adlibs?.length" class="lyrics-line__adlibs">
          <span
            v-for="word in item.adlibs"
            :key="word.key"
            class="lyrics-word"
            :class="`lyrics-word--${word.state}`"
            :style="{ '--word-progress': word.progress }"
          >{{ word.text }}</span>
        </span>
      </button>

      <div
        v-else
        class="lyrics-line"
        :class="{
          'lyrics-line--active': item.type === 'line' && item.active,
          'lyrics-line--word-synced': item.type === 'line' && (item.words?.length || item.adlibs?.length),
          'lyrics-line--alternate-agent': item.type === 'line' && item.agentLane === 'alternate',
          'lyrics-pause': item.type === 'pause',
          'lyrics-pause--active': item.type === 'pause' && item.active
        }"
      >
        <template v-if="item.type === 'line'">
          <span v-if="item.words?.length" class="lyrics-line__words">
            <span
              v-for="word in item.words"
              :key="word.key"
              class="lyrics-word"
              :class="`lyrics-word--${word.state}`"
              :style="{ '--word-progress': word.progress }"
            >{{ word.text }}</span>
          </span>
          <span v-else>{{ item.text }}</span>
          <span v-if="item.adlibs?.length" class="lyrics-line__adlibs">
            <span
              v-for="word in item.adlibs"
              :key="word.key"
              class="lyrics-word"
              :class="`lyrics-word--${word.state}`"
              :style="{ '--word-progress': word.progress }"
            >{{ word.text }}</span>
          </span>
        </template>
        <span v-else class="fullscreen-player__pause" aria-label="Instrumental break">
          <span class="fullscreen-player__pause-dot" />
          <span class="fullscreen-player__pause-dot" />
          <span class="fullscreen-player__pause-dot" />
        </span>
      </div>
    </template>
  </div>

  <div v-else class="fullscreen-player__lyrics-message">
    <q-icon name="lyrics" />
    <span>Lyrics unavailable</span>
  </div>
</template>
