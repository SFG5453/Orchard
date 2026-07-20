<script>
import { computed } from 'vue';
import { cachedCustomArtistIndex, fetchCustomArtistConfig } from '../../app/appearance/customArtistPacks.js';

export default {
  name: 'SearchView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const customArtistId = (item) => item?.id || props.app.itemBrowseId(item);
    const isCustomArtistItem = (item) => {
      if (props.app.customArtistPagesEnabled?.value === false) return false;
      if (!props.app.isArtistItem(item)) return false;
      const artistId = customArtistId(item);
      return Boolean(cachedCustomArtistIndex()?.artists?.[artistId]);
    };
    const customArtistAccentColor = (config) => {
      const variables = config?.theme?.cssVariables || {};
      return variables['--custom-artist-accent'] || variables['--custom-artist-accent-dark'] || '';
    };
    const openSearchItem = async (event, item, source = []) => {
      if (props.app.customArtistPagesEnabled?.value === false) {
        await props.app.openMedia(item, source);
        return;
      }

      const artistId = isCustomArtistItem(item) ? customArtistId(item) : '';
      const sourceElement = event?.currentTarget?.querySelector?.('.custom-artist-profile-art__image');
      if (!artistId || !sourceElement || !props.app.openCustomArtistProfileCameraTransition) {
        await props.app.openMedia(item, source);
        return;
      }

      const configPromise = fetchCustomArtistConfig(artistId).catch(() => null);
      await props.app.openCustomArtistProfileCameraTransition({
        sourceElement,
        accentColorPromise: configPromise.then(customArtistAccentColor),
        open: async () => {
          await configPromise;
          await props.app.openMedia(item, source);
        }
      });
    };
    const librarySection = computed(() => {
      const sections = props.app.searchResult.value.sections || [];
      return sections.length === 1 && sections[0].key?.startsWith('library-')
        ? sections[0]
        : null;
    });
    const libraryEmptyState = computed(() => ({
      Albums: {
        icon: 'album',
        title: 'No albums in your library',
        detail: 'Save an album in YouTube Music and it will appear here.'
      },
      Artists: {
        icon: 'person_off',
        title: 'No subscribed artists found',
        detail: 'Subscribe to an Official Artist Channel on YouTube and it will appear here.'
      },
      Songs: {
        icon: 'music_off',
        title: 'No songs in your library',
        detail: 'Save a song in YouTube Music and it will appear here.'
      }
    }[librarySection.value?.title] || {
      icon: 'library_music',
      title: 'Nothing here yet',
      detail: 'Items added to your library will appear here.'
    }));
    const isSongsLibrary = computed(() => librarySection.value?.key === 'library-songs');
    const libraryItems = computed(() => {
      const items = librarySection.value?.items || [];
      return isSongsLibrary.value ? items.filter(props.app.isPlayableTrack) : items;
    });
    const shuffleLibrarySongs = () => {
      const tracks = libraryItems.value;
      if (!tracks.length) return;

      props.app.playCollection({
        kind: 'playlist',
        title: 'Songs',
        tracks
      }, { shuffle: true });
    };

    return {
      ...props.app,
      librarySection,
      libraryEmptyState,
      isSongsLibrary,
      libraryItems,
      isLibraryCategory: computed(() => Boolean(librarySection.value)),
      isLiveShowsSearch: computed(() => props.app.searchResult.value.source === 'ticketmaster'),
      isCustomArtistItem,
      openSearchItem,
      shuffleLibrarySongs
    };
  }
};
</script>

<template>
  <div class="search-view" :class="{ 'search-view--library': isLibraryCategory }">
    <header class="search-menu">
      <div class="search-menu__summary">
        <h1 v-if="isLiveShowsSearch">Live shows near {{ searchResult.location }}</h1>
        <h1 v-else-if="isLibraryCategory">{{ librarySection.title }}</h1>
        <h1 v-else>{{ query.trim() ? `Results for “${query.trim()}”` : 'Search Orchard' }}</h1>
        <p aria-live="polite">
          <template v-if="loading && isLibraryCategory">Loading {{ librarySection.title.toLowerCase() }}…</template>
          <template v-else-if="loading">Updating results as you type…</template>
          <template v-else-if="isLiveShowsSearch && flatResults.length">{{ flatResults.length }} upcoming Ticketmaster events</template>
          <template v-else-if="isLiveShowsSearch">No upcoming Ticketmaster events found.</template>
          <template v-else-if="isLibraryCategory">{{ libraryItems.length }} {{ libraryItems.length === 1 ? 'item' : 'items' }}</template>
          <template v-else-if="flatResults.length">{{ flatResults.length }} results across {{ searchCategorySections.length }} categories</template>
          <template v-else>Find songs, albums, artists, videos, and playlists.</template>
        </p>
      </div>

      <div v-if="!isLibraryCategory && !isLiveShowsSearch" class="search-filters" role="group" aria-label="Search result type">
        <button
          v-for="filter in filters"
          :key="filter.value"
          type="button"
          class="search-filter"
          :class="{ 'search-filter--active': selectedFilter === filter.value }"
          :aria-pressed="selectedFilter === filter.value"
          @click="selectedFilter = filter.value"
        >
          {{ filter.label }}
        </button>
      </div>
    </header>

    <div v-if="loading && flatResults.length" class="search-progress" aria-hidden="true">
      <span />
    </div>

    <div v-if="loading && !flatResults.length" class="search-loading" role="status">
      <div v-for="index in 5" :key="index" class="search-loading__row">
        <span class="search-loading__art" />
        <span class="search-loading__copy" />
      </div>
    </div>

    <template v-else-if="isLibraryCategory ? libraryItems.length : flatResults.length">
      <section v-if="!isLibraryCategory && !isLiveShowsSearch && searchTopResults.length" class="search-top-results" aria-labelledby="search-top-heading">
        <div class="section-header">
          <h2 id="search-top-heading">Top matches</h2>
          <span>{{ searchTopResults.length }} results</span>
        </div>

        <div class="search-top-grid">
          <button
            v-if="searchTopResults[0]"
            type="button"
            class="search-top-card search-top-card--lead"
            @click="openSearchItem($event, searchTopResults[0], flatPlayableResults)"
            @keydown="onSongActionKeydown($event, searchTopResults[0])"
            @contextmenu="shareMediaSongLink(searchTopResults[0], $event)"
          >
            <q-img
              v-if="mediaThumbnail(searchTopResults[0])"
              :src="mediaThumbnail(searchTopResults[0])"
              class="search-top-card__art"
              :class="{
                'search-top-card__art--artist': isArtistItem(searchTopResults[0]),
                'custom-artist-profile-art__image': isCustomArtistItem(searchTopResults[0])
              }"
            />
            <span v-else class="search-top-card__art search-top-card__art--empty">
              <q-icon :name="isArtistItem(searchTopResults[0]) ? 'person' : 'music_note'" />
            </span>
            <span class="search-top-card__copy">
              <strong class="explicit-title">
                <span class="explicit-title__text">{{ searchTopResults[0].title }}</span>
                <ExplicitBadge :explicit="searchTopResults[0].explicit" />
              </strong>
              <span>{{ itemMeta(searchTopResults[0]) || itemTypeLabel(searchTopResults[0]) }}</span>
              <span>{{ itemStat(searchTopResults[0]) || itemTypeLabel(searchTopResults[0]) }}</span>
            </span>
          </button>

          <div v-if="searchTopResults.length > 1" class="search-top-list">
            <button
              v-for="item in searchTopResults.slice(1)"
              :key="`search-top-${item.id || itemBrowseId(item) || item.title}`"
              type="button"
              class="search-top-card search-top-card--compact"
              @click="openSearchItem($event, item, flatPlayableResults)"
              @keydown="onSongActionKeydown($event, item)"
              @contextmenu="shareMediaSongLink(item, $event)"
            >
              <q-img
                v-if="mediaThumbnail(item)"
                :src="mediaThumbnail(item)"
                class="search-top-card__art"
                :class="{
                  'search-top-card__art--artist': isArtistItem(item),
                  'custom-artist-profile-art__image': isCustomArtistItem(item)
                }"
              />
              <span v-else class="search-top-card__art search-top-card__art--empty">
                <q-icon :name="isArtistItem(item) ? 'person' : 'music_note'" />
              </span>
              <span class="search-top-card__copy">
                <strong class="explicit-title">
                  <span class="explicit-title__text">{{ item.title }}</span>
                  <ExplicitBadge :explicit="item.explicit" />
                </strong>
                <span>{{ itemMeta(item) || itemTypeLabel(item) }}</span>
              </span>
              <span class="search-top-card__type">{{ itemTypeLabel(item) }}</span>
            </button>
          </div>
        </div>
      </section>

      <section
        v-for="section in searchCategorySections"
        :key="section.key"
        class="shelf-section search-shelf"
        :class="[`search-shelf--${section.key}`, { 'search-shelf--library': isLibraryCategory }]"
      >
        <div v-if="!isLibraryCategory" class="section-header">
          <h2>{{ section.title }}</h2>
          <div class="section-header__actions">
            <span>{{ sectionCount(section) }}</span>
            <button v-if="sectionHasMore(section)" type="button" class="section-more-button" @click="openSectionMore(section)">
              See all
            </button>
            <div class="shelf-nav">
              <button type="button" aria-label="Scroll left" @click="scrollShelf($event, -1)"><q-icon name="chevron_left" /></button>
              <button type="button" aria-label="Scroll right" @click="scrollShelf($event, 1)"><q-icon name="chevron_right" /></button>
            </div>
          </div>
        </div>

        <div class="media-rail search-media-rail">
          <button
            v-if="isSongsLibrary"
            type="button"
            class="search-media-card search-media-card--shuffle"
            @click="shuffleLibrarySongs"
          >
            <span class="search-media-card__art search-media-card__art--empty">
              <q-icon name="shuffle" />
            </span>
            <strong>Shuffle all</strong>
            <span>Song</span>
          </button>
          <button
            v-for="item in isLibraryCategory ? libraryItems : sectionPreviewItems(section)"
            :key="`${section.key}-${item.id || itemBrowseId(item) || item.title}`"
            type="button"
            class="search-media-card"
            :class="{ 'search-media-card--event': item.type === 'event' }"
            @click="openSearchItem($event, item, section.items)"
            @keydown="item.type !== 'event' && onSongActionKeydown($event, item)"
            @contextmenu="item.type !== 'event' && shareMediaSongLink(item, $event)"
          >
            <q-img
              v-if="mediaThumbnail(item)"
              :src="mediaThumbnail(item)"
              class="search-media-card__art"
              :class="{
                'search-media-card__art--artist': isArtistItem(item),
                'custom-artist-profile-art__image': isCustomArtistItem(item)
              }"
            />
            <span v-else class="search-media-card__art search-media-card__art--empty">
              <q-icon :name="item.type === 'event' ? 'confirmation_number' : isArtistItem(item) ? 'person' : 'album'" />
            </span>
            <strong class="explicit-title">
              <span class="explicit-title__text">{{ item.title }}</span>
              <ExplicitBadge :explicit="item.explicit" />
            </strong>
            <span>{{ itemMeta(item) }}</span>
          </button>
        </div>
      </section>
    </template>

    <div v-else class="search-empty">
      <q-icon :name="isLibraryCategory ? libraryEmptyState.icon : isLiveShowsSearch ? 'event_busy' : query.trim() ? 'search_off' : 'search'" />
      <strong>{{ isLibraryCategory ? libraryEmptyState.title : isLiveShowsSearch ? `No live shows found near ${searchResult.location}` : query.trim() ? `No results for “${query.trim()}”` : 'Start typing to search' }}</strong>
      <span>{{ isLibraryCategory ? libraryEmptyState.detail : isLiveShowsSearch ? 'Try another city or ZIP code.' : query.trim() ? 'Try a different spelling or a broader filter.' : 'Your results will update here without interrupting your typing.' }}</span>
    </div>
  </div>
</template>
