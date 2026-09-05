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
import FullscreenLyricStack from '../player/FullscreenLyricStack.vue';
import VirtualQueueList from './VirtualQueueList.vue';

export default {
  name: 'RightPanel',
  components: { FullscreenLyricStack, VirtualQueueList },
  props: { app: { type: Object, required: true } },
  setup(props) {
    // The queue list takes the whole app rather than the spread, because it
    // windows the list itself and needs the live refs, not their snapshots.
    function onDrawerVisibilityChange(open) {
      // Above the breakpoint the drawer is part of the layout and must remain
      // visible. Only persist dismissals while it is acting as an overlay.
      if (props.app.viewportWidth.value < 1281) {
        props.app.rightPanelOpen.value = open;
      }
    }

    return { ...props.app, app: props.app, onDrawerVisibilityChange };
  }
};
</script>

<template>
    <q-drawer
      :key="viewportWidth >= 1281 ? 'right-panel-desktop' : 'right-panel-overlay'"
      :model-value="viewportWidth >= 1281 ? true : rightPanelOpen"
      side="right"
      :behavior="viewportWidth >= 1281 ? 'desktop' : 'mobile'"
      :show-if-above="viewportWidth >= 1281"
      :overlay="viewportWidth < 1281"
      :width="rightPanelWidth"
      :breakpoint="1280"
      class="lyrics-sidebar"
      :style="playerBarStyle"
      @update:model-value="onDrawerVisibilityChange"
    >
      <div class="lyrics-sidebar__inner">
        <div class="right-panel-toolbar" aria-label="Right panel controls">
          <strong class="right-panel-toolbar__title">
            {{ activeTrack ? activeArtist : 'Now playing' }}
          </strong>
          <div class="right-panel-toolbar__actions" role="group" aria-label="Right panel view">
            <button
              type="button"
              :aria-label="`Open queue${queue.length ? `, ${queue.length} tracks` : ''}`"
              :aria-pressed="rightPanelMode === 'queue'"
              :class="{ active: rightPanelMode === 'queue' }"
              title="Queue"
              @click="rightPanelMode = 'queue'"
            >
              <q-icon name="queue_music" />
            </button>
            <button
              type="button"
              aria-label="Open lyrics"
              :aria-pressed="rightPanelMode === 'lyrics'"
              :class="{ active: rightPanelMode === 'lyrics' }"
              title="Lyrics"
              @click="rightPanelMode = 'lyrics'"
            >
              <q-icon name="lyrics" />
            </button>
            <button
              type="button"
              aria-label="Open settings"
              title="Settings"
              @click="closeRightPanel(); selectView('settings')"
            >
              <q-icon name="more_horiz" />
            </button>
            <button
              v-if="viewportWidth < 1281"
              type="button"
              aria-label="Close now playing panel"
              title="Close"
              @click="closeRightPanel"
            >
              <q-icon name="close" />
            </button>
          </div>
        </div>

        <template v-if="rightPanelMode === 'queue'">
          <div v-if="!continuousQueueEnabled" class="right-now-playing">
            <button
              v-if="activeTrack"
              type="button"
              class="queue-preview__item queue-preview__item--active"
              @contextmenu="openSongActionMenu(activeTrack, $event, browseDetail)"
              @keydown="onSongActionKeydown($event, activeTrack, browseDetail)"
            >
              <video
                v-if="nowArtworkVideo"
                ref="rightPanelArtworkVideoRef"
                :key="nowArtworkVideo"
                class="queue-preview__cover queue-preview__cover--video"
                :src="nowArtworkVideo"
                :poster="highResolutionArtworkImage(nowArtworkImage || trackCover(activeTrack))"
                :autoplay="isPlaying"
                muted
                loop
                playsinline
                preload="auto"
                aria-hidden="true"
                @canplay="playRightPanelArtworkVideo"
                @pause="keepRightPanelArtworkVideoPlaying"
                @ended="restartRightPanelArtworkVideo"
                @stalled="keepRightPanelArtworkVideoPlaying"
                @waiting="keepRightPanelArtworkVideoPlaying"
                @error="onNowArtworkVideoError"
              />
              <q-img v-else :src="highResolutionArtworkImage(nowArtworkImage || trackCover(activeTrack))" class="queue-preview__cover" />
              <div class="queue-preview__copy">
                <strong class="explicit-title">
                  <span class="explicit-title__text">{{ activeTrack.title }}</span>
                  <ExplicitBadge :explicit="activeTrack.explicit" />
                </strong>
                <small>{{ activeArtist }}</small>
              </div>
            </button>
          </div>
          <div class="right-queue" :class="{ 'right-queue--continuous': continuousQueueEnabled }">
            <div class="right-queue-header">
              <div>
                <strong>{{ continuousQueueEnabled ? 'Queue' : 'Up next' }}</strong>
              </div>
              <div class="right-queue-header__actions">
                <!-- The full queue has always had a page of its own, but the
                     only route to it was a sidebar entry filed under Library,
                     which reads as saved content rather than what is playing.
                     Anyone wanting more room for the queue is looking at the
                     queue, so the way through is offered here. -->
                <button
                  type="button"
                  class="right-queue-expand"
                  title="Open the full queue"
                  aria-label="Open the full queue"
                  @click="selectView('queue')"
                >
                  <q-icon name="open_in_full" />
                  <span>Open</span>
                </button>
                <button
                  v-if="queue.length > 1"
                  type="button"
                  class="right-queue-sort"
                  :class="{ 'right-queue-sort--active': transitionQueueSorted }"
                  :aria-pressed="transitionQueueSorted"
                  :aria-label="transitionQueueSorted ? 'Restore previous queue order' : 'Sort the queue by musical compatibility'"
                  :title="transitionQueueSorted ? 'Restore previous queue order' : 'Uses BPM, key, energy, loudness, and vocal density'"
                  :disabled="transitionQueueSortBusy"
                  @click="toggleTransitionQueueSort"
                >
                  <q-icon name="route" />
                  <span v-if="transitionQueueSortBusy">{{ transitionQueueSortAnalyzedCount }} / {{ transitionQueueSortTotalCount }}</span>
                  <span v-else>Best mix</span>
                </button>
                <button
                  v-if="queueTracksForPlaylist().length || queue.length"
                  type="button"
                  class="right-queue-more"
                  title="Queue options"
                  aria-label="Queue options"
                >
                  <q-icon name="more_horiz" />
                  <q-menu anchor="bottom right" self="top right" class="right-queue-menu">
                    <button
                      v-if="queueTracksForPlaylist().length"
                      v-close-popup
                      type="button"
                      class="right-queue-menu__action"
                      @click="openQueuePlaylistDialog"
                    >
                      <q-icon name="playlist_add" />
                      <span>Add to playlist</span>
                    </button>
                    <button
                      v-if="queue.length"
                      v-close-popup
                      type="button"
                      class="right-queue-menu__action right-queue-menu__action--danger"
                      @click="clearQueue"
                    >
                      <q-icon name="delete_sweep" />
                      <span>Clear queue</span>
                    </button>
                  </q-menu>
                </button>
              </div>
            </div>
            <div v-if="continuousQueueEnabled && continuousQueue.length" class="queue-preview queue-preview--continuous">
              <VirtualQueueList :app="app" :entries="continuousQueue" continuous />
            </div>
            <div v-else-if="!continuousQueueEnabled && queue.length" class="queue-preview">
              <VirtualQueueList :app="app" :entries="queue" />
            </div>
            <div v-else class="right-queue-empty">
              <q-icon name="music_note" />
              <strong>Nothing in the queue yet</strong>
              <span>Play a song to start building your queue.</span>
            </div>
          </div>

          <div class="autoplay-footer">
            <q-icon name="all_inclusive" />
            <div>
              <strong>Autoplay</strong>
              <span v-if="autoplayLoading">Finding more music…</span>
              <span v-else-if="autoplayError">{{ autoplayError }}</span>
              <span v-else>Keep the music going</span>
            </div>
            <q-toggle v-model="autoplayEnabled" color="primary" dense aria-label="Toggle Autoplay" />
          </div>
        </template>

        <template v-else-if="rightPanelMode === 'lyrics'">
        <div class="lyrics-sidebar__header">
          <div>
            <h2>Lyrics</h2>
            <span v-if="activeTrack">{{ activeTrack.title }} · {{ activeArtist }}</span>
          </div>
          <span class="lyrics-sidebar__status">{{ lyricsStatusText }}</span>
        </div>

        <section class="fullscreen-player__lyrics lyrics-sidebar__fullscreen" aria-label="Lyrics">
          <FullscreenLyricStack
            :app="app"
            :items="lyricDisplayItems"
            :mode="lyricsState.mode"
            :status="lyricsState.status"
          />
        </section>
        </template>

        <template v-else-if="rightPanelMode === 'party' && listeningParty.status === 'connected'">
          <div class="right-party">
            <div class="right-party__room">
              <span>Room {{ listeningParty.room?.id }}</span>
              <strong>{{ listeningPartyIsHost ? 'Hosting' : 'Synced as guest' }}</strong>
            </div>

            <div class="right-party__actions">
              <button
                type="button"
                class="right-party__button"
                :disabled="!listeningPartyInviteUrl"
                @click="copyListeningPartyInviteUrl"
              >
                <q-icon :name="listeningPartyInviteCopied ? 'check' : 'content_copy'" />
                <span>{{ listeningPartyInviteCopied ? 'Copied' : 'Copy invite' }}</span>
              </button>
              <button
                type="button"
                class="right-party__button right-party__button--danger"
                @click="leaveListeningParty"
              >
                <q-icon :name="listeningPartyIsHost ? 'power_settings_new' : 'logout'" />
                <span>{{ listeningPartyIsHost ? 'End' : 'Leave' }}</span>
              </button>
            </div>

            <div class="right-party__section">
              <div class="right-panel-heading">
                <span>Connected</span>
                <span class="right-now-playing__state">{{ listeningParty.peers?.length || 0 }}</span>
              </div>
              <div class="right-party__self">
                <q-icon name="person" />
                <div>
                  <strong>{{ listeningParty.participant?.name || 'You' }}</strong>
                  <span>{{ listeningPartyIsHost ? 'Host' : 'Guest' }}</span>
                </div>
              </div>
              <div v-if="listeningParty.peers?.length" class="right-party__peer-list">
                <div v-for="peer in listeningParty.peers" :key="peer.id" class="right-party__peer">
                  <q-icon :name="peer.open ? 'check_circle' : 'radio_button_unchecked'" />
                  <div>
                    <strong>{{ peer.name || `Listener ${peer.id.slice(0, 4)}` }}</strong>
                    <span>{{ peer.role === 'host' ? 'Host' : peer.open ? 'Connected' : peer.state }}</span>
                  </div>
                </div>
              </div>
              <div v-else class="right-party__empty">Waiting for someone to join.</div>
            </div>
          </div>
        </template>
      </div>
    </q-drawer>

</template>
