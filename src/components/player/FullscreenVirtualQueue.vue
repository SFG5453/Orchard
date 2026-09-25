<!--
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
-->

<script>
import { toRef, ref } from 'vue';
import { useVirtualRows } from '../../app/playback/useVirtualRows.js';

const ROW_HEIGHT = 64;
const GROUP_HEIGHT = 28;

export default {
  name: 'FullscreenVirtualQueue',
  props: {
    app: { type: Object, required: true },
    entries: { type: Array, default: () => [] },
    continuous: { type: Boolean, default: false }
  },
  setup(props) {
    const contentRef = ref(null);
    const rows = toRef(props, 'entries');
    const { totalHeight, visibleRows } = useVirtualRows({
      rows,
      rowHeight: ROW_HEIGHT,
      headerFor: (row) => (props.continuous && row?.sectionStart ? GROUP_HEIGHT : 0),
      contentRef,
      resolveRoot: (element) => element?.closest('.fullscreen-player__queue-list') || null
    });

    const rowKey = (cell) => props.continuous
      ? `fullscreen-queue-${cell.row.key}`
      : `fullscreen-queue-${cell.row.id}-${cell.index}`;

    const playEntry = (row) => {
      if (props.continuous) {
        props.app.playContinuousQueueEntry(row);
        return;
      }
      props.app.playTrack(row, { queueSource: props.app.queue.value });
    };

    const removeEntry = (cell) => {
      if (props.continuous) props.app.removeContinuousQueueEntry(cell.row);
      else props.app.removeQueueTrack(cell.index);
    };

    const entryTrack = (row) => props.continuous ? row.track : row;

    return {
      contentRef,
      entryTrack,
      GROUP_HEIGHT,
      playEntry,
      removeEntry,
      rowKey,
      totalHeight,
      visibleRows
    };
  }
};
</script>

<template>
  <div ref="contentRef" class="fullscreen-player__queue-content" :style="{ height: `${totalHeight}px` }">
    <template v-for="cell in visibleRows" :key="rowKey(cell)">
      <p
        v-if="continuous && cell.row.sectionStart"
        class="fullscreen-player__queue-group"
        :style="{ transform: `translateY(${cell.top - GROUP_HEIGHT}px)` }"
      >
        {{ app.continuousQueueSectionLabel(cell.row.section) }}
      </p>

      <div
        class="fullscreen-player__queue-item"
        :class="continuous ? `fullscreen-player__queue-item--${cell.row.section}` : undefined"
        data-virtual-row
        :style="{ transform: `translateY(${cell.top}px)` }"
        role="button"
        tabindex="0"
        :aria-current="continuous && cell.row.section === 'current' ? 'true' : undefined"
        @click="playEntry(cell.row)"
        @keydown.enter.prevent="playEntry(cell.row)"
        @keydown.space.prevent="playEntry(cell.row)"
      >
        <img v-if="entryTrack(cell.row).thumbnail" :src="entryTrack(cell.row).thumbnail" alt="" />
        <span v-else class="fullscreen-player__queue-cover"><q-icon name="music_note" /></span>
        <span class="fullscreen-player__queue-copy">
          <strong>{{ entryTrack(cell.row).title }}</strong>
          <span>{{ entryTrack(cell.row).artist || entryTrack(cell.row).artists?.join(', ') || entryTrack(cell.row).album || 'Orchard' }}</span>
        </span>
        <span class="fullscreen-player__queue-time">{{ entryTrack(cell.row).duration || '' }}</span>
        <button
          v-if="!continuous || cell.row.section !== 'current'"
          type="button"
          class="fullscreen-player__queue-remove"
          :aria-label="`Remove ${entryTrack(cell.row).title} from the queue`"
          title="Remove from queue"
          @click.stop="removeEntry(cell)"
          @keydown.stop
        >
          <q-icon name="close" />
        </button>
        <span v-else class="fullscreen-player__queue-now-playing" aria-hidden="true">
          <span class="fullscreen-player__eq-bar" />
          <span class="fullscreen-player__eq-bar" />
          <span class="fullscreen-player__eq-bar" />
        </span>
      </div>
    </template>

    <div v-if="!entries.length" class="fullscreen-player__queue-empty">The queue is empty.</div>
  </div>
</template>
