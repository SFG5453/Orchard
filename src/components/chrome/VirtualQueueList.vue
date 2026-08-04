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
import { ref, toRef } from 'vue';
import { useVirtualRows } from '../../app/playback/useVirtualRows.js';

// Must agree with `.right-queue .queue-preview__item` and `.right-queue-group`
// in right-panel.css. The rows are positioned from these numbers rather than
// measured, so a change to either rule has to be mirrored here.
const ROW_HEIGHT = 54;
const GROUP_HEIGHT = 26;

export default {
  name: 'VirtualQueueList',
  props: {
    app: { type: Object, required: true },
    // Continuous entries carry section metadata; plain queue rows are tracks.
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
      resolveRoot: (element) => element?.closest('.queue-preview') || null
    });

    const rowKey = (cell) => (props.continuous
      ? cell.row.key
      : `right-queue-${cell.row.id}-${cell.index}`);

    // `app` is the raw context, so its queue is still a ref here -- playTrack
    // wants the array behind it.
    const playFromQueue = (track) => props.app.playTrack(track, { queueSource: props.app.queue.value });

    return {
      GROUP_HEIGHT,
      contentRef,
      playFromQueue,
      rowKey,
      totalHeight,
      visibleRows
    };
  }
};
</script>

<template>
  <div class="queue-preview__viewport" ref="contentRef" :style="{ height: `${totalHeight}px` }">
    <template v-for="cell in visibleRows" :key="rowKey(cell)">
      <p
        v-if="continuous && cell.row.sectionStart"
        class="right-queue-group right-queue-group--virtual"
        :style="{ transform: `translateY(${cell.top - GROUP_HEIGHT}px)` }"
      >
        {{ app.continuousQueueSectionLabel(cell.row.section) }}
      </p>

      <div
        v-if="continuous"
        class="queue-preview__item queue-preview__item--virtual"
        :class="`queue-preview__item--${cell.row.section}`"
        data-virtual-row
        :style="{ transform: `translateY(${cell.top}px)` }"
        role="button"
        tabindex="0"
        :aria-current="cell.row.section === 'current' ? 'true' : undefined"
        @click="app.playContinuousQueueEntry(cell.row)"
        @keydown.enter.prevent="app.playContinuousQueueEntry(cell.row)"
        @keydown.space.prevent="app.playContinuousQueueEntry(cell.row)"
        @contextmenu="app.openSongActionMenu(cell.row.track, $event)"
        @keydown="app.onSongActionKeydown($event, cell.row.track)"
      >
        <span class="right-queue-index">
          <q-icon v-if="cell.row.section === 'current'" name="graphic_eq" />
          <q-icon v-else-if="cell.row.section === 'previous'" name="history" />
          <template v-else>{{ String(cell.row.queueIndex + 1).padStart(2, '0') }}</template>
        </span>
        <q-img :src="app.trackCover(cell.row.track)" class="queue-preview__cover" />
        <div class="queue-preview__copy">
          <strong class="explicit-title">
            <span class="explicit-title__text">{{ cell.row.track.title }}</span>
            <ExplicitBadge :explicit="cell.row.track.explicit" />
          </strong>
          <small>{{ app.itemMeta(cell.row.track) }}</small>
        </div>
        <button
          v-if="cell.row.section !== 'current'"
          type="button"
          class="right-queue-remove"
          :aria-label="`Remove ${cell.row.track.title} from the queue`"
          title="Remove from queue"
          @click.stop="app.removeContinuousQueueEntry(cell.row)"
          @keydown.stop
        >
          <q-icon name="close" />
        </button>
        <span v-else class="right-queue-remove right-queue-remove--placeholder" aria-hidden="true" />
      </div>

      <div
        v-else
        class="queue-preview__item queue-preview__item--virtual"
        data-virtual-row
        :style="{ transform: `translateY(${cell.top}px)` }"
        role="button"
        tabindex="0"
        @click="playFromQueue(cell.row)"
        @keydown.enter.prevent="playFromQueue(cell.row)"
        @keydown.space.prevent="playFromQueue(cell.row)"
        @contextmenu="app.openSongActionMenu(cell.row, $event)"
        @keydown="app.onSongActionKeydown($event, cell.row)"
      >
        <span class="right-queue-index">{{ String(cell.index + 1).padStart(2, '0') }}</span>
        <q-img :src="app.trackCover(cell.row)" class="queue-preview__cover" />
        <div class="queue-preview__copy">
          <strong class="explicit-title">
            <span class="explicit-title__text">{{ cell.row.title }}</span>
            <ExplicitBadge :explicit="cell.row.explicit" />
          </strong>
          <small>{{ app.itemMeta(cell.row) }}</small>
        </div>
        <button
          type="button"
          class="right-queue-remove"
          :aria-label="`Remove ${cell.row.title} from queue`"
          title="Remove from queue"
          @click.stop="app.removeQueueTrack(cell.index)"
          @keydown.stop
        >
          <q-icon name="close" />
        </button>
      </div>
    </template>
  </div>
</template>
