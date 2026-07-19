<script>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import VirtualPlaylistTrackRow from './VirtualPlaylistTrackRow.vue';

const ROW_HEIGHT = 54;
const OVERSCAN = 10;
const playlistScrollPositions = new Map();

function artworkKey(url) {
  return String(url || '').trim();
}

export default {
  name: 'VirtualPlaylistTrackList',
  components: { VirtualPlaylistTrackRow },
  props: {
    app: { type: Object, required: true },
    detail: { type: Object, required: true },
    tracks: { type: Array, default: () => [] }
  },
  setup(props, { expose }) {
    const scrollRef = ref(null);
    const range = ref({ start: 0, end: 0 });
    const artworkCache = new Map();
    let scrollRoot = null;
    let frame = 0;

    const totalHeight = computed(() => props.tracks.length * ROW_HEIGHT);
    const visibleRows = computed(() => {
      const rows = [];
      const start = range.value.start;
      const end = Math.min(range.value.end, props.tracks.length);

      for (let index = start; index < end; index += 1) {
        rows.push({
          index,
          track: props.tracks[index],
          artworkUrl: artworkFor(props.tracks[index])
        });
      }
      return rows;
    });

    function updateRange() {
      const listBounds = scrollRef.value?.getBoundingClientRect();
      const rootBounds = scrollRoot?.getBoundingClientRect();
      if (!listBounds || !rootBounds) return;

      const firstVisible = Math.floor((rootBounds.top - listBounds.top) / ROW_HEIGHT);
      const visibleCount = Math.ceil((rootBounds.bottom - listBounds.top) / ROW_HEIGHT) - firstVisible;
      range.value = {
        start: Math.max(0, firstVisible - OVERSCAN),
        end: Math.min(props.tracks.length, firstVisible + visibleCount + OVERSCAN)
      };
    }

    function flushScrollRange() {
      frame = 0;
      playlistScrollPositions.set(props.detail.browseId, scrollRoot?.scrollTop || 0);
      updateRange();
    }

    function onScroll() {
      if (frame) return;
      frame = requestAnimationFrame(flushScrollRange);
    }

    function artworkFor(track) {
      const candidate = props.app.collectionTrackCover(track, props.detail);
      const key = artworkKey(candidate);
      if (!key) return '';
      if (!artworkCache.has(key)) artworkCache.set(key, candidate);
      return artworkCache.get(key);
    }

    function trackKey(row) {
      return `playlist-track-${row.track?.id || row.track?.index || row.track?.title}-${row.index}`;
    }

    function resetPlaylistViewport() {
      artworkCache.clear();
      updateRange();
    }

    function scrollToIndex(index) {
      if (!scrollRoot || !scrollRef.value || !props.tracks.length) return;
      const targetIndex = Math.max(0, Math.min(Number(index) || 0, props.tracks.length - 1));
      const listBounds = scrollRef.value.getBoundingClientRect();
      const rootBounds = scrollRoot.getBoundingClientRect();
      const listTop = scrollRoot.scrollTop + listBounds.top - rootBounds.top;
      const centeredOffset = Math.max(0, (scrollRoot.clientHeight - ROW_HEIGHT) / 2);
      scrollRoot.scrollTo({
        top: Math.max(0, listTop + targetIndex * ROW_HEIGHT - centeredOffset),
        behavior: 'smooth'
      });
    }

    expose({ scrollToIndex });

    watch(() => props.detail.browseId, resetPlaylistViewport);
    watch(() => props.tracks.length, updateRange);

    onMounted(async () => {
      await nextTick();
      scrollRoot = scrollRef.value?.closest('.page') || null;
      updateRange();
      const savedScrollTop = playlistScrollPositions.get(props.detail.browseId) || 0;
      if (savedScrollTop && scrollRoot) {
        scrollRoot.scrollTop = savedScrollTop;
        updateRange();
      }
      scrollRoot?.addEventListener('scroll', onScroll, { passive: true });
      window.addEventListener('resize', updateRange);
    });

    onBeforeUnmount(() => {
      scrollRoot?.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', updateRange);
      if (frame) cancelAnimationFrame(frame);
    });

    return {
      ROW_HEIGHT,
      onScroll,
      range,
      scrollRef,
      totalHeight,
      trackKey,
      visibleRows
    };
  }
};
</script>

<template>
  <div v-if="!tracks.length" class="table-empty">No tracks in this playlist.</div>
  <div v-else class="playlist-virtual-scroll" ref="scrollRef">
    <div class="playlist-virtual-content" :style="{ height: `${totalHeight}px` }">
      <VirtualPlaylistTrackRow
        v-for="row in visibleRows"
        :key="trackKey(row)"
        :app="app"
        :detail="detail"
        :track="row.track"
        :row-index="row.index"
        :artwork-url="row.artworkUrl"
        :row-style="{ transform: `translateY(${row.index * ROW_HEIGHT}px)` }"
      />
    </div>
  </div>
  <div v-if="app.browseTrackPageLoading?.value || app.browseTrackPageError?.value" class="table-load-more" role="status" aria-live="polite">
    <span v-if="app.browseTrackPageLoading?.value">Loading additional tracks in the background…</span>
    <span v-else>{{ app.browseTrackPageError.value }}</span>
  </div>
</template>
