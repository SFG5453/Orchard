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
import { computed, ref, watch } from 'vue';
import { lyricHandoffOpacity } from './fullscreenPlayerVisuals.js';
import FullscreenLyricStack from './FullscreenLyricStack.vue';

function cloneItems(items = []) {
  return items.map((item) => ({
    ...item,
    words: item.words?.map((word) => ({ ...word })) || [],
    adlibs: item.adlibs?.map((word) => ({ ...word })) || []
  }));
}

export default {
  name: 'FullscreenLyrics',
  components: { FullscreenLyricStack },
  props: {
    app: { type: Object, required: true },
    mix: { type: Object, required: true }
  },
  setup(props) {
    const outgoingItems = ref([]);
    const outgoingMode = ref('');

    watch(
      () => [
        props.mix.id,
        props.mix.visible,
        props.app.lyricsState.value.trackId,
        props.app.lyricDisplayItems.value
      ],
      () => {
        if (!props.mix.visible) {
          outgoingItems.value = [];
          outgoingMode.value = '';
          return;
        }
        if (props.app.lyricsState.value.trackId !== props.mix.from?.id) return;
        outgoingItems.value = cloneItems(props.app.lyricDisplayItems.value);
        outgoingMode.value = props.app.lyricsState.value.mode;
      },
      { immediate: true }
    );

    const outgoingWindow = computed(() => {
      const items = outgoingItems.value;
      const activeIndex = items.findIndex((item) => item.active);
      if (activeIndex < 0) return items.slice(0, 5);
      return items.slice(Math.max(0, activeIndex - 1), activeIndex + 4);
    });

    const showOutgoing = computed(() => (
      props.mix.visible &&
      props.mix.phase !== 'preparing' &&
      props.app.lyricsState.value.trackId !== props.mix.from?.id &&
      outgoingWindow.value.length > 0
    ));

    const handoff = computed(() => lyricHandoffOpacity(props.mix));
    const currentStyle = computed(() => {
      if (!showOutgoing.value) return undefined;
      return {
        opacity: handoff.value.incoming,
        transform: `translate3d(0, ${(1 - handoff.value.incoming) * 18}px, 0)`
      };
    });
    const outgoingStyle = computed(() => ({
      opacity: handoff.value.outgoing,
      transform: `translate3d(0, ${-handoff.value.incoming * 18}px, 0)`
    }));

    return {
      currentStyle,
      handoff,
      outgoingMode,
      outgoingStyle,
      outgoingWindow,
      showOutgoing
    };
  }
};
</script>

<template>
  <section class="fullscreen-player__lyrics" aria-label="Lyrics">
    <div class="fullscreen-player__lyric-layer" :style="currentStyle">
      <FullscreenLyricStack
        :app="app"
        :items="app.lyricDisplayItems.value"
        :mode="app.lyricsState.value.mode"
        :status="app.lyricsState.value.status"
      />
    </div>

    <div
      v-if="showOutgoing"
      class="fullscreen-player__lyric-layer fullscreen-player__lyric-layer--outgoing"
      :style="outgoingStyle"
      aria-hidden="true"
    >
      <FullscreenLyricStack
        :app="app"
        :interactive="false"
        :items="outgoingWindow"
        :mode="outgoingMode"
        :scrollable="false"
        status="ready"
      />
    </div>
  </section>
</template>
