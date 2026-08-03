<script>
import VirtualQueuePage from './VirtualQueuePage.vue';

export default {
  name: 'QueueView',
  components: { VirtualQueuePage },
  props: { app: { type: Object, required: true } },
  setup(props) {
    // The windowed list needs the live refs, not the unwrapped snapshot the
    // rest of this template reads.
    return { ...props.app, app: props.app };
  }
};
</script>

<template>
              <section class="shelf-section">
                <div class="section-header">
                  <h2>{{ continuousQueueEnabled ? 'Queue' : 'Up next' }}</h2>
                  <div class="queue-view__header-actions">
                    <span>{{ queue.length }} items</span>
                    <button
                      v-if="queue.length > 1"
                      type="button"
                      class="queue-view__sort"
                      :class="{ 'queue-view__sort--active': transitionQueueSorted }"
                      :aria-pressed="transitionQueueSorted"
                      :aria-label="transitionQueueSorted ? 'Restore previous queue order' : 'Sort the queue by musical compatibility'"
                      :title="transitionQueueSorted ? 'Restore previous queue order' : 'Uses BPM, key, energy, loudness, and vocal density'"
                      :disabled="transitionQueueSortBusy"
                      @click="toggleTransitionQueueSort"
                    >
                      <q-icon name="route" />
                      <span v-if="transitionQueueSortBusy">{{ transitionQueueSortAnalyzedCount }} / {{ transitionQueueSortTotalCount }}</span>
                      <span v-else>Best mix</span>
                    </button>
                    <button v-if="queue.length" type="button" class="queue-view__clear" @click="clearQueue">
                      Clear
                    </button>
                  </div>
                </div>

                <div v-if="continuousQueueEnabled" class="queue-view__list-wrap">
                  <VirtualQueuePage v-if="continuousQueue.length" :app="app" :entries="continuousQueue" continuous />
                  <div v-else class="table-card"><div class="table-empty">The queue is empty.</div></div>
                </div>

                <div v-else class="queue-view__list-wrap">
                  <VirtualQueuePage v-if="queue.length" :app="app" :entries="queue" />
                  <div v-else class="table-card"><div class="table-empty">The queue is empty.</div></div>
                </div>
              </section>

              <section v-if="!continuousQueueEnabled" class="shelf-section">
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
                    @click="playHistoryTrack(item)"
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
