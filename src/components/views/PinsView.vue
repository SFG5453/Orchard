<script>
export default {
  name: 'PinsView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <section class="shelf-section pins-view">
    <div class="section-header">
      <h2>Pinned songs</h2>
      <span>{{ pinnedTracks.length }} items</span>
    </div>

    <div v-if="pinnedTracks.length" class="table-card">
      <div
        v-for="(track, index) in pinnedTracks"
        :key="`pin-${track.id}`"
        class="table-row pins-view__row"
        role="button"
        tabindex="0"
        @click="playTrack(track, { queueSource: pinnedTracks })"
        @keydown.enter.prevent="playTrack(track, { queueSource: pinnedTracks })"
        @keydown.space.prevent="playTrack(track, { queueSource: pinnedTracks })"
        @keydown="onSongActionKeydown($event, track)"
        @contextmenu="openSongActionMenu(track, $event)"
      >
        <span class="table-index">{{ index + 1 }}</span>
        <span class="table-track">
          <q-img v-if="track.thumbnail" :src="track.thumbnail" class="table-cover" />
          <span v-else class="table-cover table-cover--empty"><q-icon name="music_note" /></span>
          <span class="table-copy">
            <strong class="explicit-title">
              <span class="explicit-title__text">{{ track.title }}</span>
              <ExplicitBadge :explicit="track.explicit" />
            </strong>
            <small>{{ itemMeta(track) }}</small>
          </span>
        </span>
        <span class="table-album">{{ track.album || '—' }}</span>
        <span class="table-time">{{ track.duration || '—' }}</span>
        <button
          type="button"
          class="table-icon-button"
          :aria-label="`Unpin ${track.title}`"
          title="Unpin"
          @click.stop="togglePinnedTrack(track)"
          @keydown.stop
        >
          <q-icon name="push_pin" />
        </button>
      </div>
    </div>

    <div v-else class="empty-state pins-view__empty">
      Right-click any song and choose Pin to keep it here.
    </div>
  </section>
</template>
