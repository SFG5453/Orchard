<script>
export default {
  name: 'SectionMoreView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
        <main class="detail-page">
          <section v-if="sectionMoreDetail" class="shelf-section">
            <div class="section-header">
              <h2>{{ sectionMoreDetail.title }}</h2>
              <span>{{ sectionMoreDetail.loading ? 'Loading…' : sectionCount(sectionMoreDetail) }}</span>
            </div>

            <div class="media-rail media-rail--more">
              <article
                v-for="item in sectionMoreDetail.items"
                :key="`section-more-${sectionMoreDetail.key}-${item.id || item.browseId || item.title}`"
                class="media-card"
                role="button"
                tabindex="0"
                @click="openMedia(item, sectionMoreDetail.items)"
                @keydown.enter.prevent="openMedia(item, sectionMoreDetail.items)"
                @keydown.space.prevent="openMedia(item, sectionMoreDetail.items)"
                @keydown="onSongActionKeydown($event, item)"
                @contextmenu="shareMediaSongLink(item, $event)"
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
        </main>

</template>
