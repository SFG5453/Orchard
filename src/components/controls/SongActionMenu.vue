<script>
import { computed, nextTick, ref, watch } from 'vue';

export default {
  name: 'SongActionMenu',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const menuRef = ref(null);
    const menuStyle = computed(() => {
      const menu = props.app.songActionMenu.value;
      const width = 268;
      const height = 430;
      const gutter = 12;

      return {
        left: `${Math.max(gutter, Math.min(menu.x, window.innerWidth - width - gutter))}px`,
        top: `${Math.max(40, Math.min(menu.y, window.innerHeight - height - gutter))}px`
      };
    });

    watch(() => props.app.songActionMenu.value.open, async (open) => {
      if (!open) return;
      await nextTick();
      menuRef.value?.querySelector('button:not(:disabled)')?.focus();
    });

    function onMenuKeydown(event) {
      if (event.key === 'Escape') {
        props.app.closeSongActionMenu();
        return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;

      const buttons = [...menuRef.value.querySelectorAll('button:not(:disabled)')];
      if (!buttons.length) return;
      event.preventDefault();
      const current = buttons.indexOf(document.activeElement);
      let next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : current;
      if (event.key === 'ArrowDown') next = (current + 1) % buttons.length;
      if (event.key === 'ArrowUp') next = (current - 1 + buttons.length) % buttons.length;
      buttons[next]?.focus();
    }

    return { ...props.app, menuRef, menuStyle, onMenuKeydown };
  }
};
</script>

<template>
  <Teleport to="body">
    <div
      v-if="songActionMenu.open"
      class="song-action-layer"
      @mousedown.self="closeSongActionMenu"
      @contextmenu.prevent.self="closeSongActionMenu"
    >
      <section
        ref="menuRef"
        class="song-action-menu"
        :style="menuStyle"
        role="menu"
        :aria-label="`Actions for ${songActionMenu.track?.title || 'track'}`"
        @keydown="onMenuKeydown"
        @contextmenu.prevent
      >
        <div class="song-action-menu__track">
          <q-img
            v-if="songActionMenu.track?.thumbnail"
            :src="songActionMenu.track.thumbnail"
            class="song-action-menu__cover"
          />
          <span v-else class="song-action-menu__cover song-action-menu__cover--empty">
            <q-icon name="music_note" />
          </span>
          <span class="song-action-menu__copy">
            <strong class="explicit-title">
              <span class="explicit-title__text">{{ songActionMenu.track?.title }}</span>
              <ExplicitBadge :explicit="songActionMenu.track?.explicit" />
            </strong>
            <span>{{ itemMeta(songActionMenu.track, songActionMenu.detail?.artist) }}</span>
          </span>
        </div>

        <div class="song-action-menu__items">
          <button
            type="button"
            role="menuitem"
            :disabled="songActionMenu.track?.id === activeTrack?.id"
            @click="runSongAction('play-next')"
          >
            <q-icon name="playlist_play" />
            <span>Play next</span>
          </button>
          <button
            type="button"
            role="menuitem"
            :disabled="songActionMenu.track?.id === activeTrack?.id"
            @click="runSongAction('add-queue')"
          >
            <q-icon name="queue_music" />
            <span>Add to queue</span>
          </button>
          <button
            type="button"
            role="menuitem"
            :disabled="smartQueueLoadingTrackId === songActionMenu.track?.id"
            @click="runSongAction('smart-queue')"
          >
            <q-icon name="auto_awesome_motion" />
            <span>{{ smartQueueLoadingTrackId === songActionMenu.track?.id ? 'Building smart queue' : 'Start smart queue' }}</span>
          </button>
          <button type="button" role="menuitem" @click="runSongAction('pin')">
            <q-icon :name="isTrackPinned(songActionMenu.track) ? 'push_pin' : 'push_pin'" />
            <span>{{ isTrackPinned(songActionMenu.track) ? 'Unpin' : 'Pin' }}</span>
          </button>
          <button
            type="button"
            role="menuitem"
            :disabled="!authState.signedIn"
            @click="runSongAction('playlist')"
          >
            <q-icon name="playlist_add" />
            <span>Add to playlist</span>
          </button>
          <button type="button" role="menuitem" @click="runSongAction('share')">
            <q-icon name="ios_share" />
            <span>Share song</span>
          </button>
        </div>

        <div
          v-if="canRemoveTrackFromPlaylist(songActionMenu.track, songActionMenu.detail)"
          class="song-action-menu__items song-action-menu__items--related"
        >
          <button type="button" role="menuitem" @click="runSongAction('remove-playlist')">
            <q-icon name="playlist_remove" />
            <span>Remove from this playlist</span>
          </button>
        </div>

        <div class="song-action-menu__items song-action-menu__items--related">
          <button
            type="button"
            role="menuitem"
            :disabled="!canOpenSongArtist(songActionMenu.track, songActionMenu.detail)"
            @click="runSongAction('artist')"
          >
            <q-icon name="person_outline" />
            <span>Open artist</span>
          </button>
          <button
            type="button"
            role="menuitem"
            :disabled="!canOpenSongAlbum(songActionMenu.track)"
            @click="runSongAction('album')"
          >
            <q-icon name="album" />
            <span>Open album</span>
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
