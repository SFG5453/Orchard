<script>
export default {
  name: 'PodcastsView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <div v-if="podcastLoading && !podcastFeed.sections.length" class="empty-state" role="status">
    Loading podcasts…
  </div>

  <template v-else-if="podcastFeed.sections.length">
    <section v-for="section in podcastFeed.sections" :key="section.key" class="shelf-section">
      <div class="section-header">
        <h2>{{ section.title }}</h2>
        <div class="section-header__actions">
          <span>{{ sectionCount(section) }}</span>
          <div class="shelf-nav">
            <button type="button" :aria-label="`Scroll ${section.title} left`" @click="scrollShelf($event, -1)"><q-icon name="chevron_left" /></button>
            <button type="button" :aria-label="`Scroll ${section.title} right`" @click="scrollShelf($event, 1)"><q-icon name="chevron_right" /></button>
          </div>
        </div>
      </div>

      <div class="media-rail">
        <article
          v-for="item in section.items"
          :key="`podcast-${item.id || itemBrowseId(item) || item.title}`"
          class="media-card"
          role="button"
          tabindex="0"
          @click="openMedia(item, section.items)"
          @keydown.enter.prevent="openMedia(item, section.items)"
          @keydown.space.prevent="openMedia(item, section.items)"
        >
          <q-img v-if="item.thumbnail" :src="item.thumbnail" class="media-card__art" />
          <div v-else class="media-card__art media-card__art--empty"><q-icon name="podcasts" /></div>
          <div class="media-card__title">{{ item.title }}</div>
          <div class="media-card__meta">{{ itemMeta(item) || itemTypeLabel(item) }}</div>
          <div class="media-card__stat">{{ itemStat(item) }}</div>
        </article>
      </div>
    </section>
  </template>

  <div v-else class="empty-state">No podcasts were returned.</div>
</template>
