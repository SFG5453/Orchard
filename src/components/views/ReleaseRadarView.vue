<script>
export default {
  name: 'ReleaseRadarView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <section class="release-radar-view">
    <div class="release-radar-toolbar">
      <div class="release-radar-summary" aria-label="Release Radar summary">
        <div>
          <span>Subscriptions</span>
          <strong>{{ releaseRadarSummary.subscribed }}</strong>
        </div>
        <div>
          <span>Out today</span>
          <strong>{{ releaseRadarSummary.outToday }}</strong>
        </div>
        <div>
          <span>Upcoming</span>
          <strong>{{ releaseRadarSummary.upcoming }}</strong>
        </div>
      </div>

      <button type="button" class="release-radar-refresh" :disabled="releaseRadarLoading" @click="loadReleaseRadar({ force: true })">
        <q-icon name="refresh" />
        <span>{{ releaseRadarLoading ? 'Refreshing' : 'Refresh' }}</span>
      </button>
    </div>

    <p class="release-radar-info">
      New and upcoming releases from Official Artist Channels you subscribe to on YouTube.
    </p>

    <section v-if="releaseRadarArtists.length" class="release-radar-followed" aria-label="Subscribed artists">
      <div
        v-for="artist in releaseRadarArtists"
        :key="`release-artist-${artist.browseId || artist.name}`"
        class="release-artist-token"
      >
        <q-img v-if="artist.thumbnail" :src="artist.thumbnail" class="release-artist-token__image" />
        <span v-else class="release-artist-token__image release-artist-token__image--empty">
          <q-icon name="person" />
        </span>
        <span>{{ artist.name }}</span>
      </div>
    </section>

    <div v-if="releaseRadarError" class="message-line message-line--error">
      <q-icon name="warning" />
      <span>{{ releaseRadarError }}</span>
    </div>

    <div v-if="releaseRadarLoading && !releaseRadarReleases.length && !releaseRadarArtists.length" class="empty-state release-radar-empty">
      Checking subscription releases…
    </div>

    <template v-else-if="releaseRadarSections.length">
      <section
        v-for="section in releaseRadarSections"
        :key="`release-radar-section-${section.key}`"
        class="release-radar-section"
      >
        <div class="section-header">
          <h2>{{ section.title }}</h2>
          <span>{{ section.items.length }} {{ section.items.length === 1 ? 'release' : 'releases' }}</span>
        </div>

        <div class="release-radar-list">
          <article
            v-for="release in section.items"
            :key="`release-radar-${release.futureAlbumId || release.browseId || release.title}`"
            class="release-radar-row"
          >
            <button type="button" class="release-radar-row__main" @click="openReleaseAlbum(release)">
              <q-img v-if="release.thumbnail" :src="release.thumbnail" class="release-radar-cover" />
              <span v-else class="release-radar-cover release-radar-cover--empty">
                <q-icon name="album" />
              </span>
              <span class="release-radar-copy">
                <strong class="explicit-title">
                  <span class="explicit-title__text">{{ release.title }}</span>
                  <ExplicitBadge :explicit="release.explicit" />
                </strong>
                <span>{{ release.artist }}</span>
              </span>
            </button>

            <span class="release-radar-date">
              <strong>{{ release.releaseTimingLabel }}</strong>
              <span>{{ release.releaseDateText || release.year }}</span>
            </span>

            <span class="release-radar-meta">
              <strong>{{ release.itemCount || 'Album' }}</strong>
              <span>{{ release.releaseResolved ? 'YouTube Music' : 'iTunes preview' }}</span>
            </span>

            <div class="release-radar-actions">
              <button
                type="button"
                title="Play album"
                :disabled="!releaseCanPlay(release)"
                @click="playReleaseAlbum(release)"
              >
                <q-icon name="play_arrow" />
              </button>
              <button
                type="button"
                title="Add album to queue"
                :disabled="!releaseCanPlay(release)"
                @click="addReleaseAlbumToQueue(release)"
              >
                <q-icon name="queue_music" />
              </button>
              <button
                type="button"
                title="Pin lead track"
                :disabled="!releaseCanPlay(release)"
                @click="pinReleaseAlbumLeadTrack(release)"
              >
                <q-icon name="push_pin" />
              </button>
              <button type="button" title="Open album" @click="openReleaseAlbum(release)">
                <q-icon name="open_in_new" />
              </button>
            </div>
          </article>
        </div>
      </section>
    </template>

    <template v-else-if="!releaseRadarLoading && !releaseRadarError">
      <div v-if="!releaseRadarArtists.length" class="empty-state release-radar-empty">
        No Official Artist Channel subscriptions found. Subscribe to artists on YouTube to track their new and upcoming releases.
      </div>
      <div v-else class="empty-state release-radar-empty">
        No new or upcoming releases found for your subscribed artists.
      </div>
    </template>
  </section>
</template>
