<script>
import { defineAsyncComponent } from 'vue';
import AuthGate from './AuthGate.vue';
import CollectionActionMenu from '../controls/CollectionActionMenu.vue';
import CollectionQuickSearch from '../controls/CollectionQuickSearch.vue';
import FullscreenPlayer from '../player/FullscreenPlayer.vue';
import HomeView from '../views/HomeView.vue';
import NowSideColumn from './NowSideColumn.vue';
import PlayerBar from '../player/PlayerBar.vue';
import PinsView from '../views/PinsView.vue';
import QueueView from '../player/QueueView.vue';
import RecentlyPlayedView from '../views/RecentlyPlayedView.vue';
import RightPanel from './RightPanel.vue';
import SearchView from '../views/SearchView.vue';
import SectionMoreView from '../views/SectionMoreView.vue';
import SongActionMenu from '../controls/SongActionMenu.vue';
import SongShareDialog from '../dialogs/SongShareDialog.vue';
import SidebarNav from './SidebarNav.vue';
import SpotlightSearch from '../controls/SpotlightSearch.vue';
import SupportView from '../views/SupportView.vue';
import VideoPlayer from '../player/VideoPlayer.vue';
import WindowTitlebar from './WindowTitlebar.vue';

const AnimatedBackground = defineAsyncComponent(() => import('../animated-background/AnimatedBackground.vue'));
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
    ChangelogDialog,
    CollectionActionMenu,
    CollectionQuickSearch,
    HomeView,
    LiveShowsDialog,
    ListeningPartyDialog,
    FullscreenPlayer,
    NowSideColumn,
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
    SpotlightSearch,
    VideoPlayer,
    WindowTitlebar
  },
  props: { app: { type: Object, required: true } },
  setup(props) {
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
      'app-shell--right-panel-hidden': !compactWindow && !rightPanelVisible,
      'app-shell--profile-camera': customArtistProfileCameraActive
    }"
    :style="{
      '--immersive-background-opacity': immersiveBackgroundOpacity(immersiveBackgroundIntensity),
      ...customArtistProfileCameraStyle
    }"
  >
    <AnimatedBackground
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

    <WindowTitlebar v-if="!nativeTitlebar" :app="app" />
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
            v-if="['available', 'downloading', 'downloaded', 'error'].includes(updateState.status)"
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
            <HomeView v-if="activeView === 'home'" :app="app" />
            <PinsView v-else-if="activeView === 'pins'" :app="app" />
            <PodcastsView v-else-if="activeView === 'podcasts'" :app="app" />
            <QueueView v-else-if="activeView === 'queue'" :app="app" />
            <RecentlyPlayedView v-else-if="activeView === 'history'" :app="app" />
            <ReplayView v-else-if="activeView === 'replay'" :app="app" />
            <ReleaseRadarView v-else-if="activeView === 'releaseRadar'" :app="app" />
            <SearchView v-else :app="app" />
          </section>
          <NowSideColumn :app="app" />
        </main>
      </q-page>
    </q-page-container>

    <VideoPlayer :app="app" />
    <audio
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
    <PlayerBar :app="app" />
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
