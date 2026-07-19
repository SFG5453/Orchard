<script>
export default {
  name: 'RecentlyPlayedView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <section class="shelf-section recently-played-view">
    <div class="section-header">
      <h2>Listening history</h2>
      <span>{{ history.length }} tracks</span>
    </div>

    <div v-if="history.length" class="table-card">
      <button
        v-for="(item, index) in history"
        :key="`recently-played-${item.id}-${index}`"
        type="button"
        class="table-row recently-played-view__row"
        @click="playTrack(item, { queueSource: history })"
        @keydown="onSongActionKeydown($event, item)"
        @contextmenu="openSongActionMenu(item, $event)"
      >
        <span class="table-index">{{ index + 1 }}</span>
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
      </button>
    </div>

    <div v-else class="empty-state">Nothing has been played yet.</div>
  </section>
</template>
