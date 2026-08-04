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
  name: 'NowSideColumn',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
          <aside class="side-column">
            <section
              class="now-panel"
              :class="{ 'now-panel--animated': Boolean(nowArtworkVideo) }"
              :style="heroBackdropStyle(activeTrack, nowArtworkImage)"
            >
              <video
                v-if="nowArtworkVideo"
                ref="nowArtworkVideoRef"
                :key="nowArtworkVideo"
                class="now-panel__cover now-panel__cover--video"
                :src="nowArtworkVideo"
                :poster="highResolutionArtworkImage(nowArtworkImage)"
                autoplay
                muted
                loop
                playsinline
                preload="auto"
                @canplay="playNowArtworkVideo"
                @pause="keepNowArtworkVideoPlaying"
                @ended="restartNowArtworkVideo"
                @stalled="keepNowArtworkVideoPlaying"
                @waiting="keepNowArtworkVideoPlaying"
                @error="onNowArtworkVideoError"
              />
              <q-img v-else-if="nowArtworkImage" :src="highResolutionArtworkImage(nowArtworkImage)" class="now-panel__cover" />
              <div v-else class="now-panel__cover now-panel__cover--empty">
                <q-icon name="album" />
              </div>

              <div class="now-panel__copy">
                <h3 class="explicit-title">
                  <span class="explicit-title__text">{{ activeTrack?.title || 'Nothing playing' }}</span>
                  <ExplicitBadge :explicit="activeTrack?.explicit" />
                </h3>
                <p>{{ activeArtist || 'Pick something from your library' }}</p>
              </div>

              <div class="now-panel__controls">
                <q-btn flat round dense icon="skip_previous" :disable="!activeTrack || buffering" @click="playPrevious" />
                <q-btn
                  round
                  dense
                  color="primary"
                  :loading="buffering"
                  :disable="!activeTrack"
                  :icon="isPlaying ? 'pause' : 'play_arrow'"
                  @click="togglePlayback"
                />
                <q-btn flat round dense icon="skip_next" :disable="!queue.length || buffering" @click="playNext" />
              </div>
            </section>

            <section class="queue-panel">
              <div class="section-header section-header--compact">
                <h2>Up next</h2>
                <span>{{ queue.length }}</span>
              </div>

              <div v-if="queuePreview.length" class="queue-preview">
                <button
                  v-for="item in queuePreview"
                  :key="`queue-preview-${item.id}`"
                  type="button"
                  class="queue-preview__item"
                  @click="playTrack(item, { queueSource: queue })"
                  @contextmenu="openSongActionMenu(item, $event)"
                  @keydown="onSongActionKeydown($event, item)"
                >
                  <q-img v-if="item.thumbnail" :src="item.thumbnail" class="queue-preview__cover" />
                  <div v-else class="queue-preview__cover queue-preview__cover--empty">
                    <q-icon name="music_note" />
                  </div>
                  <div class="queue-preview__copy">
                    <strong class="explicit-title">
                      <span class="explicit-title__text">{{ item.title }}</span>
                      <ExplicitBadge :explicit="item.explicit" />
                    </strong>
                    <small>{{ itemMeta(item) }}</small>
                  </div>
                </button>
              </div>

              <div v-else class="queue-panel__empty">Queue preview will appear here.</div>
            </section>
          </aside>

</template>
