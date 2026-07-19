<script>
import { computed, ref, watch } from 'vue';

export default {
  name: 'VirtualPlaylistTrackRow',
  props: {
    app: { type: Object, required: true },
    detail: { type: Object, required: true },
    track: { type: Object, required: true },
    rowIndex: { type: Number, required: true },
    artworkUrl: { type: String, default: '' },
    rowStyle: { type: Object, default: () => ({}) }
  },
  setup(props) {
    const artworkLoaded = ref(false);
    const artworkFailed = ref(false);
    const artistLinks = computed(() => props.app.trackArtistLinks(props.track, props.detail));
    const isPlayable = computed(() => props.app.isPlayableTrack(props.track));
    const itemMetadata = computed(() => props.app.itemMeta(props.track, props.detail.artist));
    const albumLabel = computed(() => props.app.trackAlbumLabel(props.track));

    watch(() => props.artworkUrl, () => {
      artworkLoaded.value = false;
      artworkFailed.value = false;
    }, { immediate: true });

    function onArtworkLoad(event) {
      if (event.currentTarget?.currentSrc === props.artworkUrl || event.currentTarget?.src === props.artworkUrl) {
        artworkLoaded.value = true;
      }
    }

    function onArtworkError(event) {
      if (event.currentTarget?.currentSrc === props.artworkUrl || event.currentTarget?.src === props.artworkUrl) {
        artworkFailed.value = true;
      }
    }

    return {
      artworkFailed,
      artworkLoaded,
      albumLabel,
      artistLinks,
      isPlayable,
      itemMetadata,
      onArtworkError,
      onArtworkLoad
    };
  }
};
</script>

<template>
  <div
    class="table-row playlist-virtual-row"
    :style="rowStyle"
    :class="{
      'table-row--active': app.activeTrack?.value?.id === track.id,
      'table-row--quick-search-match': app.collectionQuickSearchOpen?.value && app.collectionQuickSearchFocusedTrackIndex?.value === rowIndex,
      'table-row--disabled': !isPlayable,
      'table-row--with-album': true
    }"
    :role="isPlayable ? 'button' : undefined"
    :tabindex="isPlayable ? 0 : -1"
    @click="app.playBrowseDetailTrack(track)"
    @keydown="app.onBrowseTrackRowKeydown($event, track)"
  >
    <span class="table-index">{{ track.index }}</span>
    <span class="table-track">
      <span class="playlist-track-artwork-wrap">
        <span v-if="!artworkUrl || artworkFailed || !artworkLoaded" class="table-cover table-cover--empty">
          <q-icon name="music_note" />
        </span>
        <img
          v-if="artworkUrl && !artworkFailed"
          :key="artworkUrl"
          class="table-cover playlist-track-artwork"
          :class="{ 'playlist-track-artwork--loaded': artworkLoaded }"
          :src="artworkUrl"
          :alt="`${track.title} artwork`"
          loading="lazy"
          decoding="async"
          @error="onArtworkError"
          @load="onArtworkLoad"
        />
      </span>
      <span class="table-copy">
        <span class="explicit-title">
          <strong @contextmenu="app.shareTrackSongLink(track, $event, detail)">{{ track.title }}</strong>
          <ExplicitBadge :explicit="track.explicit" />
        </span>
        <small>{{ itemMetadata }}</small>
      </span>
    </span>
    <span class="table-artist">
      <template v-if="artistLinks.length">
        <template
          v-for="(artist, artistIndex) in artistLinks"
          :key="`track-artist-${track.id || track.index || track.title}-${artist.name}-${artistIndex}`"
        >
          <span v-if="artistIndex" class="table-link-separator">, </span>
          <button
            v-if="artist.browseId"
            type="button"
            class="table-link"
            :title="`Open ${artist.name}`"
            @click.stop="app.openBrowseTrackArtist(track, artist)"
            @contextmenu="app.shareTrackSongLink(track, $event, detail)"
            @keydown.stop
          >
            {{ artist.name }}
          </button>
          <span v-else @contextmenu="app.shareTrackSongLink(track, $event, detail)">{{ artist.name }}</span>
        </template>
      </template>
      <span v-else>—</span>
    </span>
    <span class="table-album">
      <button
        v-if="track.albumId || track.futureAlbumId"
        type="button"
        class="table-album__button"
        :title="`Open ${albumLabel}`"
        @click.stop="app.openBrowseTrackAlbum(track)"
        @contextmenu="app.shareTrackSongLink(track, $event, detail)"
        @keydown.stop
      >
        {{ albumLabel }}
      </button>
      <span v-else @contextmenu="app.shareTrackSongLink(track, $event, detail)">{{ albumLabel }}</span>
    </span>
    <span class="table-time">{{ track.duration || '—' }}</span>
    <button
      type="button"
      class="table-more"
      :aria-label="`Actions for ${track.title}`"
      title="Song actions"
      @click.stop="app.openSongActionMenu(track, $event, detail)"
      @keydown.stop
    >
      <q-icon v-if="isPlayable" name="more_horiz" />
    </button>
  </div>
</template>
