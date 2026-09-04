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
import { defineAsyncComponent } from 'vue';
import FullscreenPlayer from '../player/FullscreenPlayer.vue';
import NowSideColumn from './NowSideColumn.vue';
import PlayerBar from '../player/PlayerBar.vue';
import SidebarNav from './SidebarNav.vue';
import WindowTitlebar from './WindowTitlebar.vue';
import NativeAudioDecks from '../platform/NativeAudioDecks.vue';

const AuthGate = defineAsyncComponent(() => import('./AuthGate.vue'));
const CollectionActionMenu = defineAsyncComponent(() => import('../controls/CollectionActionMenu.vue'));
const CollectionQuickSearch = defineAsyncComponent(() => import('../controls/CollectionQuickSearch.vue'));
const HomeView = defineAsyncComponent(() => import('../views/HomeView.vue'));
const PinsView = defineAsyncComponent(() => import('../views/PinsView.vue'));
const QueueView = defineAsyncComponent(() => import('../player/QueueView.vue'));
const RecentlyPlayedView = defineAsyncComponent(() => import('../views/RecentlyPlayedView.vue'));
const RightPanel = defineAsyncComponent(() => import('./RightPanel.vue'));
const SearchView = defineAsyncComponent(() => import('../views/SearchView.vue'));
const SectionMoreView = defineAsyncComponent(() => import('../views/SectionMoreView.vue'));
const SongActionMenu = defineAsyncComponent(() => import('../controls/SongActionMenu.vue'));
const SongShareDialog = defineAsyncComponent(() => import('../dialogs/SongShareDialog.vue'));
const SponsorSkipButton = defineAsyncComponent(() => import('../player/SponsorSkipButton.vue'));
const SpotlightSearch = defineAsyncComponent(() => import('../controls/SpotlightSearch.vue'));
const SupportView = defineAsyncComponent(() => import('../views/SupportView.vue'));
const VideoPlayer = defineAsyncComponent(() => import('../player/VideoPlayer.vue'));

const AnimatedBackground = defineAsyncComponent(() => import('../animated-background/AnimatedBackground.vue'));
const CanopyTitlebar = defineAsyncComponent(() => import('./CanopyTitlebar.vue'));
const AboutDialog = defineAsyncComponent(() => import('../dialogs/AboutDialog.vue'));
const BrowseDetailView = defineAsyncComponent(() => import('../views/BrowseDetailView.vue'));
const ChangelogDialog = defineAsyncComponent(() => import('../dialogs/ChangelogDialog.vue'));
const LiveShowsDialog = defineAsyncComponent(() => import('../dialogs/LiveShowsDialog.vue'));
const PlaylistDialog = defineAsyncComponent(() => import('../dialogs/PlaylistDialog.vue'));
const PodcastsView = defineAsyncComponent(() => import('../views/PodcastsView.vue'));
const ReplayView = defineAsyncComponent(() => import('../views/ReplayView.vue'));
const ReleaseRadarView = defineAsyncComponent(() => import('../views/ReleaseRadarView.vue'));
const SettingsView = defineAsyncComponent(() => import('../settings/SettingsView.vue'));
const SmartCrossfadeMixOverlay = defineAsyncComponent(() => import('../player/SmartCrossfadeMixOverlay.vue'));
const UpdateDialog = defineAsyncComponent(() => import('../dialogs/UpdateDialog.vue'));
const ListeningPartyDialog = defineAsyncComponent(() => import('../dialogs/ListeningPartyDialog.vue'));

export default {
  name: 'AppFrame',
  components: {
    AboutDialog,
    AnimatedBackground,
    AuthGate,
    BrowseDetailView,
    CanopyTitlebar,
    ChangelogDialog,
    CollectionActionMenu,
    CollectionQuickSearch,
    HomeView,
    LiveShowsDialog,
    ListeningPartyDialog,
    FullscreenPlayer,
    NowSideColumn,
    NativeAudioDecks,
    PlayerBar,
    PinsView,
    PodcastsView,
    PlaylistDialog,
    QueueView,
    RecentlyPlayedView,
    ReplayView,
    ReleaseRadarView,
    RightPanel,
    SearchView,
    SectionMoreView,
    SettingsView,
    SmartCrossfadeMixOverlay,
    SupportView,
    UpdateDialog,
    SongActionMenu,
    SongShareDialog,
    SidebarNav,
    SponsorSkipButton,
    SpotlightSearch,
    VideoPlayer,
    WindowTitlebar
  },
  props: { app: { type: Object, required: true } },
  setup(props) {
    props.app.nativeAudioPlayback = Boolean(window.orchardNativeAudio);
    return props.app;
  }
};
</script>

<template>
  <q-layout
    view="hHh LpR fFf"
    class="app-shell"
    :class="{
      'app-shell--immersive': Boolean(immersiveArtworkImage || immersiveArtworkVideo),
      'app-shell--fullscreen-immersive': fullscreenPlayerOpen && immersiveBackgroundsEnabled && Boolean(immersiveArtworkImage || immersiveArtworkVideo),
      'app-shell--compact-window': compactWindow,
      'app-shell--native-titlebar': nativeTitlebar,
      'app-shell--narrow-window': narrowWindow,
      'app-shell--mini-sidebar': sidebarMini,
      [`app-shell--layout-${layoutPreset}`]: true,
      'app-shell--right-panel-hidden': !compactWindow && !rightPanelVisible,
      'app-shell--profile-camera': customArtistProfileCameraActive
    }"
    :style="{
      ...playerBarStyle,
      '--immersive-background-opacity': immersiveBackgroundOpacity(immersiveBackgroundIntensity),
      '--immersive-veil-alpha': immersiveVeilAlpha,
      ...customArtistProfileCameraStyle
    }"
  >
    <AnimatedBackground
      v-if="immersiveBackgroundsEnabled"
      :artwork-url="immersiveArtworkImage"
      :animated-artwork-url="immersiveArtworkVideo"
      :enabled="immersiveBackgroundsEnabled"
      :motion-enabled="immersiveBackgroundMotion === 'animated'"
      :playing="isPlaying"
    />

    <FullscreenPlayer v-if="fullscreenPlayerOpen" :app="app" />
    <SmartCrossfadeMixOverlay :app="app" />
    <Transition name="playback-status-popup">
      <div
        v-if="playbackStatusPopup"
        class="playback-status-popup"
        :class="`playback-status-popup--${playbackStatusPopup.tone}`"
        role="status"
        aria-live="polite"
      >
        <q-icon :name="playbackStatusPopup.icon" />
        <span>{{ playbackStatusPopup.message }}</span>
      </div>
    </Transition>

    <Transition name="idle-preview-mini-player">
      <div
        v-if="customArtistIdlePreview && customArtistIdlePreview.visible"
        class="idle-preview-mini-player"
        role="complementary"
      >
        <div class="idle-preview-mini-player__cover-wrap">
          <q-img
            v-if="customArtistIdlePreview.track?.thumbnail"
            :src="customArtistIdlePreview.track.thumbnail"
            class="idle-preview-mini-player__cover"
          />
          <div v-else class="idle-preview-mini-player__cover idle-preview-mini-player__cover--empty">
            <q-icon name="music_note" />
          </div>
          <div v-if="customArtistIdlePreview.status === 'loading'" class="idle-preview-mini-player__loader">
            <q-spinner size="18px" color="white" />
          </div>
        </div>
        <div class="idle-preview-mini-player__copy">
          <div class="idle-preview-mini-player__title">{{ customArtistIdlePreview.track?.title || 'Previewing Track' }}</div>
          <div class="idle-preview-mini-player__artist">{{ customArtistIdlePreview.track?.artist || customArtistIdlePreview.artistName || 'Artist' }}</div>
        </div>
        <q-btn
          flat
          round
          dense
          class="idle-preview-mini-player__mute-btn"
          :icon="customArtistIdlePreview.muted ? 'volume_off' : 'volume_up'"
          :title="customArtistIdlePreview.muted ? 'Unmute' : 'Mute'"
          :aria-label="customArtistIdlePreview.muted ? 'Unmute' : 'Mute'"
          @click="toggleCustomArtistIdlePreviewMute"
        />
      </div>
    </Transition>

    <CanopyTitlebar v-if="layoutPreset === 'canopy'" :app="app" />
    <WindowTitlebar v-else-if="!nativeTitlebar" :app="app" />
    <SidebarNav :app="app" />
    <RightPanel v-if="rightPanelMounted" :app="app" />

    <q-page-container>
      <q-page
        class="page"
        :class="{ 'page--search': activeView === 'search', 'page--settings': activeView === 'settings', 'page--support': activeView === 'support', 'page--browse': activeView === 'browse', 'page--auth': showAuthGate && activeView !== 'support' }"
        :style="pageStyle"
      >
        <header class="topbar" :class="{ 'topbar--home': activeView === 'home', 'topbar--search': activeView === 'search' }">
          <div class="viewport-toolbar">
            <div class="viewport-toolbar__left">
              <q-btn flat round dense icon="chevron_left" class="back-button" :disable="!navigationHistory.length" @click="goBack" />
            </div>
            <div class="topbar-controls">
              <q-btn v-if="activeView === 'home'" flat round dense icon="tune" title="Customize Home" aria-label="Customize Home" @click="showLayoutSettings" />
              <q-btn v-if="activeView === 'home' && authState.signedIn" flat round dense icon="refresh" :loading="homeLoading" @click="loadHomeLibrary" />
              <q-btn v-if="activeView === 'podcasts'" flat round dense icon="refresh" :loading="podcastLoading" @click="loadPodcasts({ force: true })" />
              <q-btn v-if="activeView === 'releaseRadar'" flat round dense icon="refresh" :loading="releaseRadarLoading" @click="loadReleaseRadar({ force: true })" />
            </div>
          </div>

          <div v-if="pageTitle || pageSubtitle" class="topbar-copy">
            <div>
              <h1>{{ pageTitle }}</h1>
              <p>{{ pageSubtitle }}</p>
            </div>
          </div>

        </header>

        <div v-if="errorMessage" class="message-line message-line--error">
          <q-icon name="warning" />
          <span>{{ errorMessage }}</span>
        </div>
        <div v-if="warningMessage" class="message-line message-line--warning">
          <q-icon name="info" />
          <span>{{ warningMessage }}</span>
        </div>
        <div v-if="updateBannerMessage" class="message-line message-line--update" :class="{ 'message-line--error': updateState.status === 'error' }">
          <q-icon :name="updateBannerIcon" />
          <span>{{ updateBannerMessage }}</span>
          <q-btn
            v-if="['available', 'downloading', 'downloaded', 'external-available', 'external-downloading', 'external-downloaded', 'error'].includes(updateState.status)"
            flat
            dense
            icon="info_outline"
            label="Details"
            @click="openUpdateDialog"
          />
          <q-btn
            v-if="updateState.status === 'downloaded'"
            flat
            dense
            icon="restart_alt"
            label="Install"
            @click="installUpdate"
          />
          <q-btn
            v-else-if="externalUpdateCanDownload"
            flat
            dense
            icon="download"
            label="Download"
            @click="downloadExternalUpdate"
          />
          <q-btn
            v-else-if="externalUpdateCanReveal"
            flat
            dense
            icon="folder_open"
            label="Show file"
            @click="revealExternalUpdate"
          />
          <q-btn
            v-else-if="updateState.status === 'error'"
            flat
            dense
            icon="refresh"
            label="Retry"
            @click="checkForUpdates"
          />
        </div>

        <SupportView v-if="activeView === 'support'" :app="app" />
        <AuthGate v-else-if="showAuthGate" :app="app" />
        <SettingsView v-else-if="activeView === 'settings'" :app="app" />
        <SectionMoreView v-else-if="activeView === 'sectionMore'" :app="app" />
        <BrowseDetailView v-else-if="activeView === 'browse'" :app="app" />
        <main v-else class="content-shell" :class="{ 'content-shell--home': activeView === 'home' }">
          <section class="main-column">
            <Transition name="view-fade" mode="out-in">
              <HomeView v-if="activeView === 'home'" :app="app" />
              <PinsView v-else-if="activeView === 'pins'" :app="app" />
              <PodcastsView v-else-if="activeView === 'podcasts'" :app="app" />
              <QueueView v-else-if="activeView === 'queue'" :app="app" />
              <RecentlyPlayedView v-else-if="activeView === 'history'" :app="app" />
              <ReplayView v-else-if="activeView === 'replay'" :app="app" />
              <ReleaseRadarView v-else-if="activeView === 'releaseRadar'" :app="app" />
              <SearchView v-else :app="app" />
            </Transition>
          </section>
          <NowSideColumn :app="app" />
        </main>
      </q-page>
    </q-page-container>

    <VideoPlayer :app="app" />
    <SponsorSkipButton :app="app" />
    <NativeAudioDecks v-if="nativeAudioPlayback" :app="app" />
    <audio
      v-else
      ref="audioRef"
      crossorigin="anonymous"
      @timeupdate="onAudioTime"
      @loadedmetadata="onAudioLoaded"
      @waiting="onAudioWaiting"
      @playing="onAudioPlaying"
      @canplay="onAudioCanPlay"
      @play="onAudioPlay"
      @pause="onAudioPause"
      @error="onAudioError"
      @ended="onAudioEnded"
    />
    <audio
      v-if="!nativeAudioPlayback"
      ref="nextAudioRef"
      crossorigin="anonymous"
      preload="auto"
      @timeupdate="onAudioTime"
      @loadedmetadata="onAudioLoaded"
      @waiting="onAudioWaiting"
      @playing="onAudioPlaying"
      @canplay="onAudioCanPlay"
      @play="onAudioPlay"
      @pause="onAudioPause"
      @error="onAudioError"
      @ended="onAudioEnded"
    />
    <PlayerBar v-if="layoutPreset !== 'canopy'" :app="app" />
    <AboutDialog v-if="aboutDialogOpen" :app="app" />
    <ChangelogDialog v-if="changelogDialogOpen" :app="app" />
    <UpdateDialog v-if="updateDialogOpen" :app="app" />
    <LiveShowsDialog v-if="liveShowsDialogOpen" :app="app" />
    <ListeningPartyDialog v-if="listeningPartyDialogOpen" :app="app" />
    <CollectionActionMenu :app="app" />
    <CollectionQuickSearch :app="app" />
    <PlaylistDialog v-if="playlistDialogOpen || deletePlaylistDialogOpen" :app="app" />
    <SongActionMenu :app="app" />
    <SongShareDialog :app="app" />
    <SpotlightSearch :app="app" />
  </q-layout>
</template>
