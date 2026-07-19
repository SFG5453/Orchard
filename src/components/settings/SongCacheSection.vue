<script>
import { computed, ref } from 'vue';

export default {
  name: 'SongCacheSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const app = props.app;
    const cacheActionsOpen = ref(false);
    const usagePercent = computed(() => {
      const maxBytes = Number(app.songCacheMaxSizeMb.value || 0) * 1024 * 1024;
      if (!maxBytes) return 0;
      return Math.min(1, Number(app.songCacheInventory.value.totalBytes || 0) / maxBytes);
    });
    const cacheableQueueCount = computed(() => {
      const seen = new Set();
      return [app.activeTrack.value, ...app.queue.value].filter((track) => {
        if (!app.isPlayableTrack(track) || track.mediaKind === 'video' || seen.has(track.id)) return false;
        seen.add(track.id);
        return true;
      }).length;
    });

    return { ...app, app, cacheActionsOpen, usagePercent, cacheableQueueCount };
  }
};
</script>

<template>
  <section id="settings-song-cache" class="settings-section" aria-labelledby="settings-song-cache-title">
    <div class="settings-section__heading">
      <h2 id="settings-song-cache-title">Song Cache</h2>
      <p>Keep recently played songs on this computer.</p>
    </div>

    <div class="settings-row">
      <div class="settings-row__copy">
        <label for="settings-song-cache-enabled">Song caching</label>
        <p>Replay cached songs from disk instead of fetching them again.</p>
      </div>
      <q-toggle id="settings-song-cache-enabled" v-model="songCacheEnabled" color="primary" aria-label="Song caching" />
    </div>

    <div class="settings-row settings-row--slider" :class="{ 'settings-row--disabled': !songCacheEnabled }">
      <div class="settings-row__copy">
        <label for="settings-song-cache-size">Maximum size</label>
        <p>Old cached songs are removed first when the cache reaches this limit.</p>
      </div>
      <div class="settings-slider settings-slider--wide-output">
        <q-slider
          id="settings-song-cache-size"
          v-model="songCacheMaxSizeMb"
          :min="128"
          :max="4096"
          :step="128"
          :disable="!songCacheEnabled"
          color="primary"
          aria-label="Song cache maximum size"
        />
        <output for="settings-song-cache-size">{{ songCacheMaxSizeMb }} MB</output>
      </div>
    </div>

    <div class="settings-cache-panel">
      <div class="settings-cache-panel__summary">
        <div>
          <span>{{ songCacheTrackCountLabel() }}</span>
          <p>{{ songCacheUsageLabel() }}</p>
        </div>
        <div class="settings-cache-actions">
          <button
            type="button"
            class="settings-button settings-cache-actions__trigger"
            aria-haspopup="menu"
            :aria-expanded="cacheActionsOpen"
            @click="cacheActionsOpen = !cacheActionsOpen"
          >
            <q-icon name="more_horiz" />
            Actions
            <q-icon name="arrow_drop_down" />
          </button>
          <q-menu
            v-model="cacheActionsOpen"
            no-parent-event
            anchor="bottom right"
            self="top right"
            class="settings-cache-menu"
          >
            <button
              v-close-popup
              type="button"
              class="settings-cache-menu__action"
              :disabled="songCacheLoading"
              @click="loadSongCacheInventory"
            >
              <q-icon name="refresh" />
              <span>Refresh cache</span>
            </button>
            <button
              v-close-popup
              type="button"
              class="settings-cache-menu__action"
              :disabled="!songCacheEnabled || !cacheableQueueCount || songCachePrefetching"
              @click="prefetchCurrentQueue"
            >
              <q-icon name="download" />
              <span>Cache current queue</span>
            </button>
            <button
              v-close-popup
              type="button"
              class="settings-cache-menu__action settings-cache-menu__action--danger"
              :disabled="!songCacheInventory.entries.length || songCacheLoading"
              @click="clearSongCache"
            >
              <q-icon name="delete_sweep" />
              <span>Clear song cache</span>
            </button>
          </q-menu>
        </div>
      </div>

      <q-linear-progress :value="usagePercent" color="primary" track-color="grey-10" aria-label="Song cache usage" />
      <p v-if="songCacheMessage" class="settings-cache-panel__message">{{ songCacheMessage }}</p>
    </div>

    <details class="settings-cache-dropdown">
      <summary>
        <span>{{ songCacheTrackCountLabel() }}</span>
        <q-icon name="keyboard_arrow_down" />
      </summary>
      <div class="settings-cache-list">
        <div v-for="entry in songCacheInventory.entries" :key="entry.key" class="settings-action-row settings-action-row--cache">
          <div class="settings-cache-song">
            <img v-if="entry.thumbnail" class="settings-cache-song__art" :src="entry.thumbnail" alt="" loading="lazy" />
            <span v-else class="settings-cache-song__art settings-cache-song__art--fallback" aria-hidden="true">
              <q-icon name="music_note" />
            </span>
            <div class="settings-row__copy">
              <span>{{ cachedSongTitle(entry) }}</span>
              <p>{{ cachedSongDetails(entry) }}</p>
            </div>
          </div>
          <button type="button" class="settings-link-button settings-link-button--danger" @click="removeCachedSong(entry)">
            Remove
          </button>
        </div>
        <div v-if="!songCacheInventory.entries.length" class="settings-connect-empty">
          No cached songs yet.
        </div>
      </div>
    </details>
  </section>
</template>
