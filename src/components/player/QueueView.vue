<script>
export default {
  name: 'QueueView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
              <section class="shelf-section">
                <div class="section-header">
                  <h2>Up next</h2>
                  <div class="queue-view__header-actions">
                    <span>{{ queue.length }} items</span>
                    <button v-if="queue.length" type="button" class="queue-view__clear" @click="clearQueue">
                      Clear
                    </button>
                  </div>
                </div>

                <div class="table-card">
                  <div
                    v-for="(item, index) in queue"
                    :key="`queue-page-${item.id}`"
                    class="table-row queue-view__row"
                    :class="{
                      'table-row--active': activeTrack?.id === item.id,
                      'queue-view__row--dragging': queueDragIndex === index
                    }"
                    role="button"
                    tabindex="0"
                    draggable="true"
                    @click="playTrack(item, { queueSource: queue })"
                    @keydown.enter.prevent="playTrack(item, { queueSource: queue })"
                    @keydown.space.prevent="playTrack(item, { queueSource: queue })"
                    @keydown="onSongActionKeydown($event, item)"
                    @contextmenu="openSongActionMenu(item, $event)"
                    @dragstart="onQueueDragStart($event, index)"
                    @dragend="queueDragIndex = null"
                    @dragover.prevent
                    @drop.prevent="onQueueDrop($event, index)"
                  >
                    <button
                      type="button"
                      class="queue-drag-handle"
                      :aria-label="`Reorder ${item.title}`"
                      title="Drag to reorder"
                      tabindex="-1"
                      @click.stop
                      @keydown.stop
                    >
                      <q-icon name="drag_indicator" />
                    </button>
                    <span class="table-track">
                      <q-img v-if="item.thumbnail" :src="item.thumbnail" class="table-cover" />
                      <span v-else class="table-cover table-cover--empty">
                        <q-icon name="music_note" />
                      </span>
                      <span class="table-copy">
                        <strong class="explicit-title">
                          <span class="explicit-title__text">{{ item.title }}</span>
                          <ExplicitBadge :explicit="item.explicit" />
                        </strong>
                        <small>{{ itemMeta(item) }}</small>
                      </span>
                    </span>
                    <span class="table-album">{{ item.album || '—' }}</span>
                    <span class="table-time">{{ item.duration || '—' }}</span>
                    <button
                      type="button"
                      class="table-icon-button"
                      :aria-label="`Remove ${item.title} from queue`"
                      title="Remove from queue"
                      @click.stop="removeQueueTrack(index)"
                      @keydown.stop
                    >
                      <q-icon name="close" />
                    </button>
                  </div>

                  <div v-if="!queue.length" class="table-empty">The queue is empty.</div>
                </div>
              </section>

              <section class="shelf-section">
                <div class="section-header">
                  <h2>Recently played</h2>
                  <span>{{ history.length }} items</span>
                </div>

                <div class="table-card">
                  <button
                    v-for="item in history"
                    :key="`history-${item.id}`"
                    type="button"
                    class="table-row"
                    @click="playTrack(item, { queueSource: history })"
                    @keydown="onSongActionKeydown($event, item)"
                    @contextmenu="openSongActionMenu(item, $event)"
                  >
                    <span class="table-index">•</span>
                    <span class="table-track">
                      <q-img v-if="item.thumbnail" :src="item.thumbnail" class="table-cover" />
                      <span v-else class="table-cover table-cover--empty">
                        <q-icon name="music_note" />
                      </span>
                      <span class="table-copy">
                        <strong class="explicit-title">
                          <span class="explicit-title__text">{{ item.title }}</span>
                          <ExplicitBadge :explicit="item.explicit" />
                        </strong>
                        <small>{{ itemMeta(item) }}</small>
                      </span>
                    </span>
                    <span class="table-time">{{ item.duration || '—' }}</span>
                  </button>

                  <div v-if="!history.length" class="table-empty">Nothing has been played yet.</div>
                </div>
              </section>

              <section class="shelf-section">
                <div class="section-header">
                  <h2>Now playing history</h2>
                  <div class="queue-view__header-actions">
                    <span>{{ sessionHistoryCount }} events</span>
                    <button v-if="sessionHistory.length" type="button" class="queue-view__clear" @click="clearSessionHistory">
                      Clear
                    </button>
                  </div>
                </div>

                <div class="table-card session-history">
                  <div
                    v-for="event in sessionHistory"
                    :key="event.id"
                    class="table-row session-history__row"
                  >
                    <span class="session-history__time">{{ sessionEventTime(event) }}</span>
                    <span class="table-track">
                      <q-img v-if="event.track.thumbnail" :src="event.track.thumbnail" class="table-cover" />
                      <span v-else class="table-cover table-cover--empty">
                        <q-icon name="music_note" />
                      </span>
                      <span class="table-copy">
                        <strong class="explicit-title">
                          <span class="explicit-title__text">{{ event.track.title }}</span>
                          <ExplicitBadge :explicit="event.track.explicit" />
                        </strong>
                        <small>{{ sessionEventMeta(event) }}</small>
                      </span>
                    </span>
                    <span class="session-history__action">{{ event.label }}</span>
                    <button
                      type="button"
                      class="table-icon-button"
                      :aria-label="`Restore queue from ${event.track.title}`"
                      title="Restore this queue"
                      @click="restoreSessionEvent(event)"
                    >
                      <q-icon name="restore" />
                    </button>
                  </div>

                  <div v-if="!sessionHistory.length" class="table-empty">Session changes will appear here.</div>
                </div>
              </section>
</template>
