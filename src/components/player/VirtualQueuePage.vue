<script>
import { ref, toRef } from 'vue';
import { useVirtualRows } from '../../app/playback/useVirtualRows.js';

// Starting guesses only. `.table-row` changes padding at the responsive
// breakpoints, so useVirtualRows measures a real row after mount and works
// from that instead -- these just keep the first paint close.
const ROW_HEIGHT = 64;
const GROUP_HEIGHT = 30;

export default {
  name: 'VirtualQueuePage',
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
      contentRef
    });

    const rowKey = (cell) => (props.continuous ? cell.row.key : `queue-page-${cell.row.id}-${cell.index}`);
    const playFromQueue = (track) => props.app.playTrack(track, { queueSource: props.app.queue.value });

    return { GROUP_HEIGHT, contentRef, playFromQueue, rowKey, totalHeight, visibleRows };
  }
};
</script>

<template>
  <div class="table-card queue-view__viewport" ref="contentRef" :style="{ height: `${totalHeight}px` }">
    <template v-for="cell in visibleRows" :key="rowKey(cell)">
      <p
        v-if="continuous && cell.row.sectionStart"
        class="queue-view__group queue-view__group--virtual"
        :style="{ transform: `translateY(${cell.top - GROUP_HEIGHT}px)` }"
      >
        {{ app.continuousQueueSectionLabel(cell.row.section) }}
      </p>

      <div
        v-if="continuous"
        class="table-row queue-view__row queue-view__row--virtual"
        data-virtual-row
        :class="{
          'table-row--active': cell.row.section === 'current',
          'queue-view__row--previous': cell.row.section === 'previous',
          'queue-view__row--dragging': cell.row.section === 'next' && app.queueDragIndex.value === cell.row.queueIndex
        }"
        :style="{ transform: `translateY(${cell.top}px)` }"
        role="button"
        tabindex="0"
        :aria-current="cell.row.section === 'current' ? 'true' : undefined"
        :draggable="cell.row.section === 'next'"
        @click="app.playContinuousQueueEntry(cell.row)"
        @keydown.enter.prevent="app.playContinuousQueueEntry(cell.row)"
        @keydown.space.prevent="app.playContinuousQueueEntry(cell.row)"
        @keydown="app.onSongActionKeydown($event, cell.row.track)"
        @contextmenu="app.openSongActionMenu(cell.row.track, $event)"
        @dragstart="cell.row.section === 'next' && app.onQueueDragStart($event, cell.row.queueIndex)"
        @dragend="app.queueDragIndex.value = null"
        @dragover.prevent
        @drop.prevent="cell.row.section === 'next' && app.onQueueDrop($event, cell.row.queueIndex)"
      >
        <button
          v-if="cell.row.section === 'next'"
          type="button"
          class="queue-drag-handle"
          :aria-label="`Reorder ${cell.row.track.title}`"
          title="Drag to reorder"
          tabindex="-1"
          @click.stop
          @keydown.stop
        >
          <q-icon name="drag_indicator" />
        </button>
        <span v-else class="queue-view__row-marker">
          <q-icon :name="cell.row.section === 'current' ? 'graphic_eq' : 'history'" />
        </span>
        <span class="table-track">
          <q-img v-if="cell.row.track.thumbnail" :src="cell.row.track.thumbnail" class="table-cover" />
          <span v-else class="table-cover table-cover--empty">
            <q-icon name="music_note" />
          </span>
          <span class="table-copy">
            <strong class="explicit-title">
              <span class="explicit-title__text">{{ cell.row.track.title }}</span>
              <ExplicitBadge :explicit="cell.row.track.explicit" />
            </strong>
            <small>{{ app.itemMeta(cell.row.track) }}</small>
          </span>
        </span>
        <span class="table-album">{{ cell.row.track.album || '—' }}</span>
        <span class="table-time">{{ cell.row.track.duration || '—' }}</span>
        <button
          v-if="cell.row.section !== 'current'"
          type="button"
          class="table-icon-button"
          :aria-label="`Remove ${cell.row.track.title} from the queue`"
          title="Remove from queue"
          @click.stop="app.removeContinuousQueueEntry(cell.row)"
          @keydown.stop
        >
          <q-icon name="close" />
        </button>
        <span v-else aria-hidden="true" />
      </div>

      <div
        v-else
        class="table-row queue-view__row queue-view__row--virtual"
        data-virtual-row
        :class="{
          'table-row--active': app.activeTrack.value?.id === cell.row.id,
          'queue-view__row--dragging': app.queueDragIndex.value === cell.index
        }"
        :style="{ transform: `translateY(${cell.top}px)` }"
        role="button"
        tabindex="0"
        draggable="true"
        @click="playFromQueue(cell.row)"
        @keydown.enter.prevent="playFromQueue(cell.row)"
        @keydown.space.prevent="playFromQueue(cell.row)"
        @keydown="app.onSongActionKeydown($event, cell.row)"
        @contextmenu="app.openSongActionMenu(cell.row, $event)"
        @dragstart="app.onQueueDragStart($event, cell.index)"
        @dragend="app.queueDragIndex.value = null"
        @dragover.prevent
        @drop.prevent="app.onQueueDrop($event, cell.index)"
      >
        <button
          type="button"
          class="queue-drag-handle"
          :aria-label="`Reorder ${cell.row.title}`"
          title="Drag to reorder"
          tabindex="-1"
          @click.stop
          @keydown.stop
        >
          <q-icon name="drag_indicator" />
        </button>
        <span class="table-track">
          <q-img v-if="cell.row.thumbnail" :src="cell.row.thumbnail" class="table-cover" />
          <span v-else class="table-cover table-cover--empty">
            <q-icon name="music_note" />
          </span>
          <span class="table-copy">
            <strong class="explicit-title">
              <span class="explicit-title__text">{{ cell.row.title }}</span>
              <ExplicitBadge :explicit="cell.row.explicit" />
            </strong>
            <small>{{ app.itemMeta(cell.row) }}</small>
          </span>
        </span>
        <span class="table-album">{{ cell.row.album || '—' }}</span>
        <span class="table-time">{{ cell.row.duration || '—' }}</span>
        <button
          type="button"
          class="table-icon-button"
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
