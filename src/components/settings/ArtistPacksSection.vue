<script>
import { ref, watch } from 'vue';
import { fetchCustomArtistIndex } from '../../app/appearance/customArtistPacks.js';

export default {
  name: 'ArtistPacksSection',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const artistsList = ref([]);

    async function loadArtists() {
      try {
        const index = await fetchCustomArtistIndex();
        if (index && index.artists) {
          artistsList.value = Object.values(index.artists).filter(entry => entry.localConfig);
        }
      } catch (err) {
        console.error('Failed to load installed artists', err);
      }
    }

    watch(() => props.app.customArtistPagesEnabled?.value !== false, (enabled) => {
      if (enabled) void loadArtists();
      else artistsList.value = [];
    }, { immediate: true });

    const getArtistLabel = (entry) => {
      const nameSetByPack = entry.displayName;
      const originalName = entry.artistName;
      if (nameSetByPack && originalName && nameSetByPack !== originalName) {
        return `${nameSetByPack} (${originalName})`;
      }
      return nameSetByPack || originalName || 'Unknown Artist';
    };

    const getArtistColor = (entry) => {
      return entry.localConfig?.theme?.cssVariables?.['--custom-artist-accent'] || 'var(--q-primary)';
    };

    return {
      artistsList,
      customArtistPagesEnabled: props.app.customArtistPagesEnabled,
      getArtistLabel,
      getArtistColor
    };
  }
};
</script>

<template>
  <section id="settings-artist-packs" class="settings-section" aria-labelledby="settings-artist-packs-title">
    <div class="settings-section__heading">
      <h2 id="settings-artist-packs-title">Artist Packs</h2>
      <p>Custom artist pages installed across all packs.</p>
    </div>

    <div class="settings-row">
      <div class="settings-row__copy">
        <label for="settings-custom-artist-pages">Custom artist pages</label>
        <p>Use artist pack artwork, layouts, search aliases, and page effects.</p>
      </div>
      <q-toggle id="settings-custom-artist-pages" v-model="customArtistPagesEnabled" color="primary" aria-label="Custom artist pages" />
    </div>

    <details class="settings-cache-dropdown">
      <summary>
        <span>{{ artistsList.length }} Installed Artists</span>
        <q-icon name="keyboard_arrow_down" />
      </summary>
      <div class="settings-cache-list">
        <div v-for="entry in artistsList" :key="entry.artistId" class="settings-action-row settings-action-row--cache">
          <div class="settings-cache-song">
            <img v-if="entry.profileArtwork" class="settings-cache-song__art" :src="entry.profileArtwork" alt="" loading="lazy" />
            <span v-else class="settings-cache-song__art settings-cache-song__art--fallback" aria-hidden="true" :style="{ color: getArtistColor(entry) }">
              <q-icon name="person" />
            </span>
            <div class="settings-row__copy">
              <span :style="{ color: getArtistColor(entry) }">{{ getArtistLabel(entry) }}</span>
              <p>{{ entry.source === 'hosted-archive' ? 'Built-in pack' : 'Custom pack' }}</p>
            </div>
          </div>
        </div>
        <div v-if="!artistsList.length" class="settings-connect-empty">
          {{ customArtistPagesEnabled ? 'No artists installed.' : 'Artist pages are turned off.' }}
        </div>
      </div>
    </details>
  </section>
</template>
