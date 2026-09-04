<!--
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
-->

<script>
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { gsap } from 'gsap';

export default {
  name: 'HomeView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const homeViewRoot = ref(null);

    function animateShelves() {
      nextTick(() => {
        const shelves = Array.from(homeViewRoot.value?.querySelectorAll('.home-shelf') || []);
        if (!shelves.length) return;

        const pageBounds = homeViewRoot.value?.closest('.page')?.getBoundingClientRect();
        const viewportBottom = pageBounds?.bottom || window.innerHeight;
        const visibleShelves = shelves.filter(
          (shelf) => shelf.getBoundingClientRect().top <= viewportBottom + 96
        );
        if (!visibleShelves.length) return;

        gsap.killTweensOf(shelves);
        gsap.fromTo(
          visibleShelves,
          { opacity: 0, y: 16 },
          {
            opacity: 1,
            y: 0,
            duration: 0.45,
            ease: 'power2.out',
            stagger: 0.06,
            clearProps: 'opacity,transform',
            overwrite: true
          }
        );
      });
    }

    onMounted(animateShelves);
    watch(
      () => props.app.homeShelfSections.value.length,
      (count, previousCount) => {
        if (count && !previousCount) animateShelves();
      }
    );

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

      return props.app.applyHomeSectionLayout(sections);
    });

    return { ...props.app, homeDisplaySections, homeViewRoot };
  }
};
</script>

<template>
  <div ref="homeViewRoot" class="home-view">
    <div v-if="homeLoading && !hasHomeContent" class="empty-state">Loading your library…</div>

    <template v-else-if="hasHomeContent && homeDisplaySections.length">
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

        <section v-else class="shelf-section home-shelf home-shelf--catalog" :class="{'home-shelf--top-picks': section.title === 'Top Picks for You'}">
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
              <div v-if="section.title === 'Top Picks for You'" class="media-card__brand">
                <svg viewBox="0 0 384 512" class="media-card__brand-icon" aria-hidden="true" xmlns="http://www.w3.org/2000/svg"><path fill="currentColor" d="M318.7 268.7c-.2-36.7 16.4-64.4 50-84.8-18.8-26.9-47.2-41.7-84.7-44.6-35.5-2.8-74.3 20.7-88.5 20.7-15 0-49.4-19.7-76.4-19.7C63.3 141.2 4 184.8 4 273.5q0 39.3 14.4 81.2c12.8 36.7 59 126.7 107.2 125.2 25.2-.6 43-17.9 75.8-17.9 31.8 0 48.3 17.9 76.4 17.9 48.6-.7 90.4-82.5 102.6-119.3-65.2-30.7-61.7-90-61.7-91.9zm-56.6-164.2c27.3-32.4 24.8-61.9 24-72.5-24.1 1.4-52 16.4-67.9 34.9-17.5 19.8-27.8 44.3-25.6 71.9 26.1 2 49.9-11.4 69.5-34.3z"/></svg>
                Music
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

    <div v-else-if="hasHomeContent" class="empty-state home-layout-empty">
      <q-icon name="space_dashboard" />
      <strong>Your Home shelves are hidden.</strong>
      <span>Choose what appears here in Home &amp; sidebar settings.</span>
      <button type="button" @click="showLayoutSettings">Customize Home</button>
    </div>

    <div v-else class="empty-state">No library items were returned.</div>
  </div>
</template>
