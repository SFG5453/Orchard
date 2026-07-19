<script>
export default {
  name: 'CollectionQuickSearch',
  props: { app: { type: Object, required: true } },
  setup(props) {
    function onKeydown(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        props.app.closeCollectionQuickSearch();
        return;
      }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        props.app.moveCollectionQuickSearchSelection(event.key === 'ArrowDown' ? 1 : -1);
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        props.app.scrollToCollectionQuickSearchMatch(
          props.app.collectionQuickSearchVisibleMatches.value[
            props.app.collectionQuickSearchActiveIndex.value
          ]
        );
      }
    }

    function resultLabel(count) {
      if (!props.app.collectionQuickSearchQuery.value) return 'Type to find a track';
      return `${count} ${count === 1 ? 'match' : 'matches'}`;
    }

    return { ...props.app, onKeydown, resultLabel };
  }
};
</script>

<template>
  <Teleport to="body">
    <section
      v-if="collectionQuickSearchOpen"
      class="collection-quick-search"
      role="dialog"
      :aria-label="`Find a track in ${browseDetail?.title || 'this collection'}`"
      :style="playerBarStyle"
    >
      <div class="collection-quick-search__input-row">
        <q-icon name="search" />
        <input
          ref="collectionQuickSearchInputRef"
          v-model="collectionQuickSearchQuery"
          type="search"
          autocomplete="off"
          spellcheck="false"
          :placeholder="`Find in ${browseDetail?.title || 'collection'}`"
          :aria-label="`Find a track in ${browseDetail?.title || 'this collection'}`"
          @input="queueCollectionQuickSearchScroll"
          @keydown="onKeydown"
        />
        <span class="collection-quick-search__count" aria-live="polite">
          {{ resultLabel(collectionQuickSearchMatches.length) }}
        </span>
        <button type="button" aria-label="Close track search" @mousedown.prevent @click="closeCollectionQuickSearch">
          <q-icon name="close" />
        </button>
      </div>

      <div v-if="collectionQuickSearchVisibleMatches.length" class="collection-quick-search__results" role="listbox">
        <button
          v-for="(match, index) in collectionQuickSearchVisibleMatches"
          :key="match.id"
          type="button"
          class="collection-quick-search__result"
          :class="{ 'collection-quick-search__result--active': collectionQuickSearchActiveIndex === index }"
          :aria-selected="collectionQuickSearchActiveIndex === index"
          role="option"
          @mousedown.prevent
          @mouseenter="collectionQuickSearchActiveIndex = index"
          @click="scrollToCollectionQuickSearchMatch(match)"
        >
          <img v-if="match.artwork" :src="match.artwork" alt="" />
          <span v-else class="collection-quick-search__art-empty"><q-icon name="music_note" /></span>
          <span class="collection-quick-search__copy">
            <strong>{{ match.title }}</strong>
            <span>{{ match.subtitle || browseDetail?.title }}</span>
          </span>
          <span class="collection-quick-search__track-number">{{ match.track.index || match.index + 1 }}</span>
        </button>
      </div>

      <div v-else class="collection-quick-search__empty" role="status">
        No tracks match “{{ collectionQuickSearchQuery }}”
      </div>
    </section>
  </Teleport>
</template>
