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
  name: 'PlaylistDialog',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <q-dialog v-model="playlistDialogOpen" persistent :aria-label="playlistDialogMode === 'queue' ? 'Add queue to playlist' : 'Add track to playlist'">
    <q-card class="playlist-dialog">
      <header class="playlist-dialog__header">
        <div>
          <div class="playlist-dialog__title">{{ playlistDialogMode === 'queue' ? 'Add queue to playlist' : 'Add to playlist' }}</div>
          <div class="playlist-dialog__track">
            <template v-if="playlistDialogMode === 'queue'">
              {{ playlistDialogTracks.length }} {{ playlistDialogTracks.length === 1 ? 'song' : 'songs' }} in queue
            </template>
            <template v-else>
              {{ playlistDialogTrack?.title || 'Track' }}
            </template>
          </div>
        </div>
        <q-btn
          flat
          round
          dense
          icon="close"
          aria-label="Close playlist dialog"
          :disable="Boolean(playlistMutationPending)"
          @click="closePlaylistDialog"
        />
      </header>

      <form class="playlist-dialog__create" @submit.prevent="createPlaylistWithTrack">
        <label for="new-playlist-title">New playlist</label>
        <div class="playlist-dialog__create-row">
          <input
            id="new-playlist-title"
            v-model="newPlaylistTitle"
            type="text"
            maxlength="150"
            autocomplete="off"
            placeholder="Playlist name"
            :disabled="Boolean(playlistMutationPending)"
          />
          <button type="submit" :disabled="!canCreatePlaylist">
            <q-spinner v-if="playlistMutationPending === 'create'" size="16px" />
            <span v-else>Create</span>
          </button>
        </div>
      </form>

      <div v-if="playlistMutationError" class="playlist-dialog__error" role="alert">
        <q-icon name="warning" />
        <span>{{ playlistMutationError }}</span>
      </div>

      <div class="playlist-dialog__list" aria-live="polite">
        <div v-if="playlistTargetsLoading" class="playlist-dialog__status">
          <q-spinner size="20px" />
          <span>Checking your playlists…</span>
        </div>
        <div v-else-if="!playlistTargets.length" class="playlist-dialog__status">
          No editable playlists found. Create one above.
        </div>
        <template v-else>
          <button
            v-for="playlist in playlistTargets"
            :key="playlist.id"
            type="button"
            class="playlist-dialog__playlist"
            :disabled="Boolean(playlistMutationPending) || (playlistDialogMode !== 'queue' && playlist.containsTrack)"
            @click="addTrackToPlaylist(playlist)"
          >
            <q-img v-if="playlist.thumbnail" :src="playlist.thumbnail" class="playlist-dialog__cover" />
            <span v-else class="playlist-dialog__cover playlist-dialog__cover--empty">
              <q-icon name="queue_music" />
            </span>
            <span class="playlist-dialog__copy">
              <strong>{{ playlist.title }}</strong>
              <span>{{ (playlistDialogMode !== 'queue' && playlist.containsTrack) ? 'Already added' : playlist.subtitle || 'Editable playlist' }}</span>
            </span>
            <q-spinner v-if="playlistMutationPending === playlist.id" size="18px" />
            <q-icon v-else :name="(playlistDialogMode !== 'queue' && playlist.containsTrack) ? 'check' : 'add'" />
          </button>
        </template>
      </div>
    </q-card>
  </q-dialog>

  <q-dialog v-model="deletePlaylistDialogOpen" persistent aria-label="Delete playlist">
    <q-card class="playlist-delete-dialog">
      <div class="playlist-delete-dialog__copy">
        <strong>Delete {{ playlistDeleteTarget?.title || 'playlist' }}?</strong>
        <p>This removes the playlist from your YouTube Music library. This cannot be undone.</p>
      </div>
      <div v-if="playlistDeleteError" class="playlist-dialog__error" role="alert">
        <q-icon name="warning" />
        <span>{{ playlistDeleteError }}</span>
      </div>
      <div class="playlist-delete-dialog__actions">
        <button type="button" :disabled="playlistDeletePending" @click="deletePlaylistDialogOpen = false">Cancel</button>
        <button type="button" class="playlist-delete-dialog__confirm" :disabled="playlistDeletePending" @click="confirmDeletePlaylist">
          <q-spinner v-if="playlistDeletePending" size="16px" />
          <span v-else>Delete playlist</span>
        </button>
      </div>
    </q-card>
  </q-dialog>
</template>
