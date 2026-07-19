<script>
import { computed } from 'vue';

export default {
  name: 'HomeView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const homeDisplaySections = computed(() => {
      const sections = [...props.app.homeShelfSections.value];
      const libraryIndex = sections.findIndex((section) =>
        String(section.title || '').trim().toLowerCase() === 'library'
      );

      if (libraryIndex > 0) {
        sections.unshift(...sections.splice(libraryIndex, 1));
      }

      if (props.app.history.value.length) {
        sections.splice(libraryIndex >= 0 ? 1 : 0, 0, {
          key: 'recently-played',
          title: 'Recently Played',
          items: props.app.history.value,
          isHistory: true
        });
      }

      return sections;
    });

    return { ...props.app, homeDisplaySections };
  }
};
</script>

<template>
  <div class="home-view">
    <div v-if="homeLoading && !hasHomeContent" class="empty-state">Loading your library…</div>

    <template v-else-if="hasHomeContent">
      <template v-for="section in homeDisplaySections" :key="`home-section-${section.key}`">
        <section v-if="section.isHistory" class="shelf-section home-shelf home-shelf--history">
          <div class="section-header">
            <h2>{{ section.title }}</h2>
            <div class="section-header__actions">
              <span>{{ section.items.length }} tracks</span>
              <div class="shelf-nav">
                <button type="button" aria-label="Scroll recently played left" @click="scrollShelf($event, -1)"><q-icon name="chevron_left" /></button>
                <button type="button" aria-label="Scroll recently played right" @click="scrollShelf($event, 1)"><q-icon name="chevron_right" /></button>
              </div>
            </div>
          </div>

          <div class="media-rail">
            <article
              v-for="item in section.items"
              :key="`home-history-${item.id}`"
              class="media-card"
              role="button"
              tabindex="0"
              @click="playTrack(item, { queueSource: section.items })"
              @keydown.enter.prevent="playTrack(item, { queueSource: section.items })"
              @keydown.space.prevent="playTrack(item, { queueSource: section.items })"
              @keydown="onHomeMediaKeydown($event, item, section.items)"
              @contextmenu="openHomeMediaContextMenu(item, $event, section.items)"
            >
              <q-img v-if="mediaThumbnail(item)" :src="mediaThumbnail(item)" class="media-card__art" />
              <div v-else class="media-card__art media-card__art--empty">
                <q-icon name="music_note" />
              </div>
              <div class="media-card__title explicit-title">
                <span class="explicit-title__text">{{ item.title }}</span>
                <ExplicitBadge :explicit="item.explicit" />
              </div>
              <div class="media-card__meta">{{ itemMeta(item) }}</div>
            </article>
          </div>
        </section>

        <section v-else class="shelf-section home-shelf home-shelf--catalog">
          <div class="section-header">
            <h2>{{ section.title }}</h2>
            <div class="section-header__actions">
              <span>{{ section.items.length }}</span>
              <button v-if="sectionHasMore(section)" type="button" class="section-more-button" @click="openSectionMore(section)">
                See all
              </button>
              <div class="shelf-nav">
                <button type="button" :aria-label="`Scroll ${section.title} left`" @click="scrollShelf($event, -1)"><q-icon name="chevron_left" /></button>
                <button type="button" :aria-label="`Scroll ${section.title} right`" @click="scrollShelf($event, 1)"><q-icon name="chevron_right" /></button>
              </div>
            </div>
          </div>

          <div class="media-rail">
            <article
              v-for="item in sectionPreviewItems(section)"
              :key="`home-card-${section.key}-${item.id || item.browseId || item.title}`"
              class="media-card"
              role="button"
              tabindex="0"
              @click="openMedia(item, section.items)"
              @keydown.enter.prevent="openMedia(item, section.items)"
              @keydown.space.prevent="openMedia(item, section.items)"
              @keydown="onHomeMediaKeydown($event, item, section.items)"
              @contextmenu="openHomeMediaContextMenu(item, $event, section.items)"
            >
              <q-img v-if="mediaThumbnail(item)" :src="mediaThumbnail(item)" class="media-card__art" />
              <div v-else class="media-card__art media-card__art--empty">
                <q-icon name="album" />
              </div>
              <div class="media-card__title explicit-title">
                <span class="explicit-title__text">{{ item.title }}</span>
                <ExplicitBadge :explicit="item.explicit" />
              </div>
              <div class="media-card__meta">{{ itemMeta(item) }}</div>
              <div class="media-card__stat">{{ itemStat(item) }}</div>
            </article>
          </div>
        </section>
      </template>
    </template>

    <div v-else class="empty-state">No library items were returned.</div>
  </div>
</template>
