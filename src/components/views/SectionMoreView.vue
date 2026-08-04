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
