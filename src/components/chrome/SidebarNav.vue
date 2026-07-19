<script>
import { computed, ref, watch } from 'vue';

function isEmailLike(value = '') {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(value || '').trim());
}

export default {
  name: 'SidebarNav',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const accountAvatarFailed = ref(false);
    const accountAvatarRetry = ref(0);
    const accountAvatarUrl = computed(() => {
      const thumbnail = String(props.app.authState.value.user?.thumbnail || '').trim();
      if (!thumbnail || accountAvatarRetry.value === 0) return thumbnail;
      const separator = thumbnail.includes('?') ? '&' : '?';
      return `${thumbnail}${separator}orchard_retry=${accountAvatarRetry.value}`;
    });
    const accountDisplayName = computed(() => {
      const name = String(props.app.authState.value.user?.name || '').trim();
      return name && !isEmailLike(name) && !/^(signed in|youtube music)$/i.test(name) ? name : 'Signed in';
    });
    const accountDisplayByline = computed(() => {
      const byline = String(props.app.authState.value.user?.byline || '').trim();
      return byline && !isEmailLike(byline) && !/^(signed in)$/i.test(byline) ? byline : 'YouTube Music';
    });
    const accountInitial = computed(() => {
      const identity = [accountDisplayName.value, accountDisplayByline.value]
        .map((value) => String(value || '').trim())
        .find((value) => value && !/^(signed in|youtube music)$/i.test(value));
      return identity?.replace(/^@/, '').match(/[\p{L}\p{N}]/u)?.[0]?.toUpperCase() || '';
    });

    watch(() => props.app.authState.value.user?.thumbnail, () => {
      accountAvatarFailed.value = false;
      accountAvatarRetry.value = 0;
    });

    function retryAccountAvatar() {
      if (accountAvatarRetry.value < 2) {
        accountAvatarRetry.value += 1;
        return;
      }
      accountAvatarFailed.value = true;
    }

    return {
      ...props.app,
      accountAvatarFailed,
      accountAvatarUrl,
      accountDisplayByline,
      accountDisplayName,
      accountInitial,
      retryAccountAvatar
    };
  }
};
</script>

<template>
    <q-drawer
      show-if-above
      behavior="desktop"
      :width="sidebarWidth"
      :mini="sidebarMini"
      :mini-width="68"
      class="sidebar"
    >
      <template #mini>
        <nav class="sidebar-mini" aria-label="Compact navigation">
          <button type="button" class="sidebar-mini__button" title="Search" aria-label="Search" :disabled="!authState.signedIn" @click="openSpotlightSearch('')">
            <q-icon name="search" />
          </button>
          <button type="button" class="sidebar-mini__button" :class="{ 'sidebar-mini__button--active': activeView === 'home' }" title="Home" aria-label="Home" @click="selectView('home')">
            <q-icon name="home" />
          </button>
          <button type="button" class="sidebar-mini__button" :class="{ 'sidebar-mini__button--active': activeView === 'releaseRadar' }" title="New" aria-label="New releases" :disabled="!authState.signedIn" @click="showReleaseRadar">
            <q-icon name="new_releases" />
          </button>
          <button type="button" class="sidebar-mini__button" :class="{ 'sidebar-mini__button--active': activeView === 'browse' && browseDetail?.title === 'My Supermix' }" title="Radio" aria-label="Radio" :disabled="!authState.signedIn" @click="openPersonalizedRadio">
            <q-icon name="radio" />
          </button>
          <button type="button" class="sidebar-mini__button" :class="{ 'sidebar-mini__button--active': activeView === 'pins' }" title="Pins" aria-label="Pins" :disabled="!authState.signedIn" @click="showPins">
            <q-icon name="push_pin" />
          </button>
          <button type="button" class="sidebar-mini__button" :class="{ 'sidebar-mini__button--active': activeView === 'podcasts' }" title="Podcasts" aria-label="Podcasts" :disabled="!authState.signedIn" @click="loadPodcasts()">
            <q-icon name="podcasts" />
          </button>
          <span class="sidebar-mini__spacer" />
          <button type="button" class="sidebar-mini__button" :class="{ 'sidebar-mini__button--active': activeView === 'support' }" title="Support" aria-label="Support" @click="selectView('support')">
            <q-icon name="support_agent" />
            <span v-if="supportUnreadCount" class="sidebar-mini__count" aria-hidden="true">{{ supportUnreadCount }}</span>
          </button>
          <button type="button" class="sidebar-mini__button" :class="{ 'sidebar-mini__button--active': activeView === 'settings' }" title="Settings" aria-label="Settings" @click="selectView('settings')">
            <q-icon name="settings" />
          </button>
        </nav>
      </template>
      <div class="sidebar-inner">
        <q-form class="sidebar-search-form" @submit.prevent="runSearch">
          <q-input
            v-model="query"
            dense
            borderless
            clearable
            clear-icon="close"
            :loading="loading"
            placeholder="Search Orchard"
            aria-label="Search Orchard"
            :aria-busy="loading"
            autocomplete="off"
            spellcheck="false"
            class="sidebar-search-input"
            :disable="!authState.signedIn"
          >
            <template #prepend>
              <q-icon name="search" />
            </template>
          </q-input>
        </q-form>

        <nav class="sidebar-group sidebar-group--nav" aria-label="Browse">
          <div class="sidebar-label">Browse</div>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'home' }" @click="selectView('home')">
            <q-icon name="home" />
            <span>Home</span>
          </button>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'releaseRadar' }" :disabled="!authState.signedIn" @click="showReleaseRadar">
            <q-icon name="new_releases" />
            <span>New</span>
          </button>
          <button
            type="button"
            class="nav-link"
            :class="{ 'nav-link--active': activeView === 'browse' && browseDetail?.title === 'My Supermix' }"
            :disabled="!authState.signedIn"
            @click="openPersonalizedRadio"
          >
            <q-icon name="radio" />
            <span>Radio</span>
          </button>
          <button
            type="button"
            class="nav-link"
            :class="{ 'nav-link--active': activeView === 'search' && searchResult.source === 'ticketmaster' }"
            :disabled="!authState.signedIn"
            @click="openLiveShows"
          >
            <q-icon name="confirmation_number" />
            <span>Live Shows</span>
          </button>
        </nav>

        <nav class="sidebar-group sidebar-group--nav" aria-label="Your music">
          <div class="sidebar-label">Your music</div>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'pins' }" :disabled="!authState.signedIn" @click="showPins">
            <q-icon name="push_pin" />
            <span>Pins</span>
          </button>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'history' }" :disabled="!authState.signedIn" @click="selectView('history')">
            <q-icon name="history" />
            <span>Recently Played</span>
          </button>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'replay' }" :disabled="!authState.signedIn" @click="selectView('replay')">
            <q-icon name="leaderboard" />
            <span>Replay</span>
          </button>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'search' && searchResult.sections?.[0]?.key === 'library-songs' }" :disabled="!authState.signedIn" @click="showLibrarySongs">
            <q-icon name="music_note" />
            <span>Songs</span>
          </button>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'search' && searchResult.sections?.[0]?.key === 'library-albums' }" :disabled="!authState.signedIn" @click="showLibraryAlbums">
            <q-icon name="album" />
            <span>Albums</span>
          </button>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'search' && searchResult.sections?.[0]?.key === 'library-artists' }" :disabled="!authState.signedIn" @click="showSubscribedArtists">
            <q-icon name="people_outline" />
            <span>Artists</span>
          </button>
          <button type="button" class="nav-link" :class="{ 'nav-link--active': activeView === 'podcasts' }" :disabled="!authState.signedIn" @click="loadPodcasts()">
            <q-icon name="podcasts" />
            <span>Podcasts</span>
          </button>
        </nav>

        <div v-if="sidebarLibraryItems.length" class="sidebar-group sidebar-group--playlists">
          <div class="sidebar-section-heading">
            <span>Playlists</span>
            <span>{{ sidebarLibraryItems.length }}</span>
          </div>
          <div class="sidebar-playlist-scroll">
            <button
              v-for="item in sidebarLibraryItems"
              :key="`sidebar-${itemBrowseId(item)}`"
              type="button"
              class="library-link"
              :class="{ 'library-link--active': browseDetail?.browseId === itemBrowseId(item) }"
              @click="openMedia(item, homeShelfSections.flatMap((section) => section.items))"
            >
              <q-img v-if="item.thumbnail" :src="item.thumbnail" class="library-link__cover" />
              <div v-else class="library-link__cover library-link__cover--empty">
                <q-icon name="album" />
              </div>
              <div class="library-link__copy">
                <span>{{ item.title }}</span>
                <small>{{ itemMeta(item) }}</small>
              </div>
            </button>
          </div>
        </div>

        <div class="sidebar-group sidebar-group--status">
          <button
            type="button"
            class="nav-link sidebar-support-link"
            :class="{ 'nav-link--active': activeView === 'support' }"
            @click="selectView('support')"
          >
            <q-icon name="support_agent" />
            <span>Support</span>
            <span v-if="supportUnreadCount" class="nav-link__count" :aria-label="`${supportUnreadCount} unread support replies`">{{ supportUnreadCount }}</span>
          </button>
          <button
            type="button"
            class="nav-link sidebar-settings-link"
            :class="{ 'nav-link--active': activeView === 'settings' }"
            @click="selectView('settings')"
          >
            <q-icon name="settings" />
            <span>Settings</span>
          </button>

          <div class="sidebar-presence">
            <span :title="`Connection: ${socketState}`">
              <q-icon :name="socketState === 'connected' ? 'link' : 'link_off'" />
              {{ socketState === 'connected' ? 'Online' : socketState }}
            </span>
            <span :title="authLabel">{{ authLabel }}</span>
          </div>

          <div class="account-actions">
            <q-btn
              v-if="!authState.signedIn"
              unelevated
              color="primary"
              icon="login"
              label="Sign in"
              :loading="authState.status === 'starting'"
              @click="startLogin"
            />
            <div v-else class="account-menu-wrap">
              <button
                type="button"
                class="account-profile"
                :aria-expanded="accountMenuOpen"
                aria-haspopup="menu"
                title="Account options"
                @click="accountMenuOpen = !accountMenuOpen"
              >
                <img
                  v-if="authState.user?.thumbnail && !accountAvatarFailed"
                  :src="accountAvatarUrl"
                  class="account-profile__avatar"
                  alt=""
                  @error="retryAccountAvatar"
                />
                <span v-else class="account-profile__avatar">
                  <template v-if="accountInitial">{{ accountInitial }}</template>
                  <q-icon v-else name="account_circle" />
                </span>
                <span class="account-profile__copy">
                  <strong>{{ accountDisplayName }}</strong>
                  <span>{{ accountDisplayByline }}</span>
                </span>
                <q-icon name="unfold_more" />
              </button>

              <q-menu v-model="accountMenuOpen" no-parent-event anchor="top right" self="bottom right" class="account-menu">
                <div class="account-menu__identity">
                  <strong>{{ accountDisplayName }}</strong>
                  <span>{{ accountDisplayByline }}</span>
                </div>
                <q-separator dark />
                <button type="button" class="account-menu__action" :disabled="accountSwitching" @click="switchAccount">
                  <q-icon name="switch_account" />
                  <span>Switch account</span>
                </button>
                <button type="button" class="account-menu__action" @click="signOut">
                  <q-icon name="logout" />
                  <span>Sign out</span>
                </button>
              </q-menu>
            </div>
          </div>

          <div v-if="authState.pending" class="device-card">
            <div class="device-card__code">{{ authState.pending.userCode }}</div>
            <div class="device-card__url">{{ authState.pending.verificationUrl }}</div>
            <div class="device-card__actions">
              <button type="button" @click="openVerification">Open link</button>
              <button type="button" @click="copyLoginText(authState.pending.userCode)">Copy code</button>
              <button type="button" @click="copyLoginText(authState.pending.verificationUrl)">Copy link</button>
            </div>
          </div>
        </div>
      </div>
    </q-drawer>

</template>
