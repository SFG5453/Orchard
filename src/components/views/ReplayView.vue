<script>
export default {
  name: 'ReplayView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <section class="replay-view">
    <div class="replay-toolbar">
      <div class="replay-periods" role="tablist" aria-label="Replay period">
        <button
          v-for="option in replayPeriodOptions"
          :key="option.value"
          type="button"
          :class="{ 'replay-periods__button--active': replayPeriod === option.value }"
          :aria-selected="replayPeriod === option.value"
          role="tab"
          @click="replayPeriod = option.value"
        >
          {{ option.label }}
        </button>
      </div>

      <div class="replay-actions">
        <button type="button" class="replay-action" :disabled="!replaySummary.tracks.length" @click="playReplayTopTracks">
          <q-icon name="play_arrow" />
          <span>Play top tracks</span>
        </button>
        <button type="button" class="replay-action replay-action--muted" :disabled="!replayEvents.length" @click="clearReplayStats">
          <q-icon name="delete_outline" />
          <span>Clear</span>
        </button>
      </div>
    </div>

    <div class="replay-summary" aria-label="Replay summary">
      <div>
        <span>Listens</span>
        <strong>{{ replaySummary.totalPlays }}</strong>
      </div>
      <div>
        <span>Time</span>
        <strong>{{ replayDurationLabel(replaySummary.totalSeconds) }}</strong>
      </div>
      <div>
        <span>Tracks</span>
        <strong>{{ replaySummary.uniqueTracks }}</strong>
      </div>
      <div>
        <span>Artists</span>
        <strong>{{ replaySummary.uniqueArtists }}</strong>
      </div>
      <div>
        <span>Days</span>
        <strong>{{ replaySummary.activeDays }}</strong>
      </div>
    </div>

    <section class="shelf-section replay-section">
      <div class="section-header">
        <h2>Top tracks</h2>
        <span>{{ replaySummary.tracks.length }} tracks</span>
      </div>

      <div v-if="replaySummary.tracks.length" class="table-card replay-table">
        <button
          v-for="(entry, index) in replaySummary.tracks.slice(0, 12)"
          :key="`replay-track-${entry.key}`"
          type="button"
          class="table-row replay-row replay-row--track"
          :style="replayRankStyle(entry, replaySummary.tracks)"
          @click="playTrack(entry.item, { queueSource: replaySummary.tracks.map((item) => item.item) })"
          @keydown="onSongActionKeydown($event, entry.item)"
          @contextmenu="openSongActionMenu(entry.item, $event)"
        >
          <span class="table-index">{{ index + 1 }}</span>
          <span class="table-track">
            <q-img v-if="entry.item.thumbnail" :src="entry.item.thumbnail" class="table-cover" />
            <span v-else class="table-cover table-cover--empty"><q-icon name="music_note" /></span>
            <span class="table-copy">
              <strong class="explicit-title">
                <span class="explicit-title__text">{{ entry.item.title }}</span>
                <ExplicitBadge :explicit="entry.item.explicit" />
              </strong>
              <small>{{ entry.item.artist || itemMeta(entry.item) }}</small>
            </span>
          </span>
          <span class="table-album">{{ entry.item.album || '—' }}</span>
          <span class="replay-count">{{ entry.plays }} plays</span>
          <span class="table-time">{{ replayDurationLabel(entry.seconds) }}</span>
        </button>
      </div>

      <div v-else class="empty-state replay-empty">
        Replay starts counting after a song reaches 30 seconds or halfway through.
      </div>
    </section>

    <div class="replay-lists">
      <section class="shelf-section replay-section">
        <div class="section-header">
          <h2>Artists</h2>
          <span>{{ replaySummary.artists.length }} artists</span>
        </div>

        <div v-if="replaySummary.artists.length" class="replay-rank-list">
          <div
            v-for="(entry, index) in replaySummary.artists.slice(0, 10)"
            :key="`replay-artist-${entry.key}`"
            class="replay-rank-row"
            :style="replayRankStyle(entry, replaySummary.artists)"
          >
            <span>{{ index + 1 }}</span>
            <strong>{{ entry.item.title }}</strong>
            <em>{{ entry.plays }} plays</em>
          </div>
        </div>
        <div v-else class="replay-mini-empty">No artists yet.</div>
      </section>

      <section class="shelf-section replay-section">
        <div class="section-header">
          <h2>Albums</h2>
          <span>{{ replaySummary.albums.length }} albums</span>
        </div>

        <div v-if="replaySummary.albums.length" class="replay-rank-list">
          <div
            v-for="(entry, index) in replaySummary.albums.slice(0, 10)"
            :key="`replay-album-${entry.key}`"
            class="replay-rank-row replay-rank-row--album"
            :style="replayRankStyle(entry, replaySummary.albums)"
          >
            <span>{{ index + 1 }}</span>
            <q-img v-if="entry.item.thumbnail" :src="entry.item.thumbnail" class="replay-rank-cover" />
            <strong>{{ entry.item.title }}</strong>
            <em>{{ entry.plays }} plays</em>
          </div>
        </div>
        <div v-else class="replay-mini-empty">No albums yet.</div>
      </section>
    </div>
  </section>
</template>
