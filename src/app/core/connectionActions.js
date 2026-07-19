import { nextTick } from 'vue';
import { installLyricsActions } from '../playback/lyricsActions.js';

export function installConnectionActions(ctx) {
  installLyricsActions(ctx);

  ctx.socketPort = function socketPort() {
    return new URLSearchParams(window.location.search).get('socketPort') || '0';
  };

  ctx.minimizeWindow = function minimizeWindow() {
    window.orchardWindow?.minimize();
  };

  ctx.toggleMaximizeWindow = function toggleMaximizeWindow() {
    window.orchardWindow?.toggleMaximize();
  };

  ctx.closeWindow = function closeWindow() {
    window.orchardWindow?.close();
  };

  ctx.emitWithReply = function emitWithReply(event, payload = {}, options = {}) {
    return new Promise((resolve, reject) => {
      let settled = false;
      const timeoutMs = Math.max(0, Number(options.timeoutMs) || 0);
      const timeout = timeoutMs
        ? globalThis.setTimeout(() => {
          if (settled) return;
          settled = true;
          reject(new Error(`${event} request timed out`));
        }, timeoutMs)
        : 0;
      const settle = (callback, value) => {
        if (settled) return;
        settled = true;
        if (timeout) globalThis.clearTimeout(timeout);
        callback(value);
      };

      try {
        ctx.socket.value.emit(event, payload, (response) => {
          if (response?.ok) settle(resolve, response.data);
          else settle(reject, new Error(response?.error || 'Request failed'));
        });
      } catch (error) {
        settle(reject, error);
      }
    });
  };

  ctx.isInterruptedPlaybackRequest = function isInterruptedPlaybackRequest(error) {
    return error?.name === 'AbortError' ||
      /play\(\) request was interrupted|interrupted by a call to pause|interrupted by a new load request/i.test(error?.message || '');
  };

  ctx.supportedAudioMimes = function supportedAudioMimes() {
    const audio = ctx.currentAudio() || document.createElement('audio');

    return ctx.audioMimeCandidates
      .map((mimeType) => ({
        mimeType,
        support: audio.canPlayType(mimeType)
      }))
      .filter((item) => item.support);
  };

  ctx.supportedVideoMimes = function supportedVideoMimes() {
    const video = ctx.videoRef.value || document.createElement('video');

    return ctx.videoMimeCandidates
      .map((mimeType) => ({
        mimeType,
        support: video.canPlayType(mimeType)
      }))
      .filter((item) => item.support)
      .sort((a, b) => {
        if (a.support === b.support) return 0;
        return a.support === 'probably' ? -1 : 1;
      });
  };

  ctx.currentAudio = function currentAudio() {
    return ctx.activeAudioDeck.value === 'main' ? ctx.audioRef.value : ctx.nextAudioRef.value;
  };

  ctx.currentPlaybackElement = function currentPlaybackElement() {
    return ctx.activeTrackIsVideo.value ? ctx.videoRef.value : ctx.currentAudio();
  };

  ctx.waitForPlaybackElement = async function waitForPlaybackElement() {
    for (let attempt = 0; attempt < 4; attempt += 1) {
      const element = ctx.currentPlaybackElement();
      if (element) return element;
      await nextTick();
    }
    return null;
  };

  ctx.currentPlaybackAudioElement = function currentPlaybackAudioElement() {
    if (!ctx.activeTrackIsVideo.value) return ctx.currentAudio();
    return ctx.videoAudioRef.value?.src ? ctx.videoAudioRef.value : ctx.videoRef.value;
  };

  ctx.standbyAudio = function standbyAudio() {
    return ctx.activeAudioDeck.value === 'main' ? ctx.nextAudioRef.value : ctx.audioRef.value;
  };

  ctx.isCurrentAudioEvent = function isCurrentAudioEvent(event) {
    return event?.target === ctx.currentPlaybackElement();
  };

  ctx.clearMediaElement = function clearMediaElement(element) {
    if (!element) return;
    element.pause();
    ctx.audioAnalyzer.setVolume(element, 0);
    element.removeAttribute('src');
    element.load();
    element.volume = 1;
  };

  ctx.clearAudioElement = function clearAudioElement(audio) {
    ctx.clearMediaElement(audio);
  };

  ctx.setCurrentAudioVolume = function setCurrentAudioVolume(value = ctx.volume.value) {
    const media = ctx.currentPlaybackAudioElement() || ctx.currentPlaybackElement();
    const effectiveVolume = ctx.effectivePlaybackVolume?.(value) ?? value;
    if (media && !ctx.autoCrossfade.isActive()) ctx.audioAnalyzer.setVolume(media, effectiveVolume);
  };

  ctx.setAudioNormalization = function setAudioNormalization(element, enabled = ctx.volumeNormalizationEnabled.value) {
    if (element) ctx.audioAnalyzer.setNormalization(element, enabled);
  };

  ctx.syncVideoCompanionAudio = function syncVideoCompanionAudio(targetTime = ctx.videoRef.value?.currentTime || 0) {
    if (!ctx.activeTrackIsVideo.value) return;
    const audio = ctx.videoAudioRef.value;
    if (!audio?.src) return;

    const drift = Math.abs((audio.currentTime || 0) - targetTime);
    if (drift > 0.35) audio.currentTime = targetTime;
  };

  ctx.syncAuthState = function syncAuthState(nextState) {
    const wasSignedIn = ctx.authState.value.signedIn;

    ctx.authState.value = {
      ...ctx.authState.value,
      ...nextState
    };

    if (wasSignedIn && nextState?.signedIn === false) {
      ctx.resetNavigation('home');
      ctx.updateSetupState?.({ welcomeCompleted: false });
      window.orchardApp?.showWelcome?.();
    }

    if (ctx.authState.value.error) ctx.errorMessage.value = ctx.authState.value.error;
  };

  ctx.selectView = function selectView(view) {
    if (!ctx.authState.value.signedIn && !['home', 'settings', 'support'].includes(view)) {
      ctx.resetNavigation('home');
      ctx.errorMessage.value = '';
      ctx.warningMessage.value = '';
      return;
    }

    ctx.navigateToView(view);
    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';

    if (view === 'home' && ctx.authState.value.signedIn && !ctx.hasHomeContent.value) {
      ctx.loadHomeLibrary();
    }

    if (view === 'search' && !ctx.flatResults.value.length && ctx.socket.value?.connected) {
      ctx.runSearch();
    }
  };

  ctx.showLibraryCategory = function showLibraryCategory(title, predicate) {
    if (!ctx.authState.value.signedIn) {
      ctx.selectView('home');
      return;
    }

    const items = ctx.flatHomeItems.value.filter(predicate);
    ctx.searchResult.value = {
      sections: [{ key: `library-${title.toLowerCase()}`, title, items }]
    };
    ctx.selectedFilter.value = 'all';
    ctx.navigateToView('search');
    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';
  };

  async function showRemoteLibraryCategory(title, event, errorFallback) {
    if (!ctx.authState.value.signedIn) {
      ctx.selectView('home');
      return;
    }

    const key = `library-${title.toLowerCase().replace(/\s+/g, '-')}`;
    const requestId = ++ctx.searchRequest;
    ctx.searchResult.value = { sections: [{ key, title, items: [] }] };
    ctx.navigateToView('search');
    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';
    ctx.loading.value = true;

    try {
      const items = await ctx.emitWithReply(event, { title });
      if (requestId !== ctx.searchRequest) return;
      ctx.searchResult.value = { sections: [{ key, title, items: items || [] }] };
    } catch (error) {
      if (requestId === ctx.searchRequest) {
        ctx.errorMessage.value = error.message || errorFallback;
      }
    } finally {
      if (requestId === ctx.searchRequest) ctx.loading.value = false;
    }
  }

  ctx.showLibraryAlbums = function showLibraryAlbums() {
    return showRemoteLibraryCategory('Albums', 'music:library-category', 'Could not load library albums.');
  };

  ctx.showLibrarySongs = function showLibrarySongs() {
    return showRemoteLibraryCategory('Songs', 'music:library-category', 'Could not load library songs.');
  };

  ctx.showSubscribedArtists = function showSubscribedArtists() {
    return showRemoteLibraryCategory('Artists', 'music:subscribed-artists', 'Could not load subscribed artists.');
  };

  ctx.runPresetSearch = async function runPresetSearch(searchQuery, filter = 'all') {
    if (!ctx.authState.value.signedIn) {
      ctx.selectView('home');
      return;
    }

    ctx.query.value = searchQuery;
    ctx.selectedFilter.value = filter;
    await ctx.runSearch();
  };

  ctx.runSearch = async function runSearch() {
    if (!ctx.authState.value.signedIn) {
      ctx.resetNavigation('home');
      return;
    }

    window.clearTimeout(ctx.searchDebounceTimer);
    const searchQuery = ctx.query.value.trim();
    if (!searchQuery || !ctx.socket.value?.connected) {
      if (!searchQuery) {
        ctx.searchRequest += 1;
        ctx.searchResult.value = { sections: [] };
        ctx.loading.value = false;
      }
      return;
    }

    if (await ctx.handleSearchLink(searchQuery)) return;

    const requestId = ++ctx.searchRequest;

    ctx.loading.value = true;
    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';

    try {
      const alias = ctx.customArtistAliasForQuery?.(searchQuery);
      const result = await ctx.emitWithReply('music:search', {
        query: searchQuery,
        filter: ctx.selectedFilter.value
      });
      if (alias && ['all', 'artists'].includes(ctx.selectedFilter.value)) {
        const aliasResult = await ctx.emitWithReply('music:search', {
          query: alias.canonicalQuery,
          filter: 'artists'
        });
        const artist = aliasResult.sections
          ?.find((section) => section.key === 'artists')
          ?.items
          ?.find((item) => ctx.itemBrowseId(item) === alias.browseId);
        if (artist) {
          const promotedArtist = ctx.withCustomArtistAliasMetadata(artist, alias);
          const sections = result.sections || [];
          let artistSection = sections.find((section) => section.key === 'artists');
          if (!artistSection) {
            artistSection = { key: 'artists', title: 'Artists', items: [] };
            sections.unshift(artistSection);
          }
          artistSection.items = [
            promotedArtist,
            ...artistSection.items.filter((item) => ctx.itemBrowseId(item) !== alias.browseId)
          ];
          result.sections = sections;
        }
      }
      if (requestId !== ctx.searchRequest) return;
      ctx.searchResult.value = result;
      ctx.navigateToView('search');
    } catch (error) {
      if (requestId === ctx.searchRequest) ctx.errorMessage.value = error.message;
    } finally {
      if (requestId === ctx.searchRequest) ctx.loading.value = false;
    }
  };

  ctx.queueSearch = function queueSearch() {
    window.clearTimeout(ctx.searchDebounceTimer);

    if (!ctx.authState.value.signedIn) {
      ctx.resetNavigation('home');
      return;
    }

    // Invalidate the previous response as soon as the query changes. Waiting
    // for the debounce would let a result for a partial query win the race.
    ctx.searchRequest += 1;

    if (!ctx.query.value.trim()) {
      if (ctx.activeView.value === 'search') ctx.searchResult.value = { sections: [] };
      ctx.loading.value = false;
      return;
    }

    if (ctx.searchLinkCandidate(ctx.query.value)) {
      ctx.searchDebounceTimer = window.setTimeout(() => {
        ctx.runSearch();
      }, ctx.SEARCH_DEBOUNCE_MS);
      return;
    }

    ctx.navigateToView('search');
    if (!ctx.socket.value?.connected) {
      ctx.loading.value = false;
      return;
    }

    ctx.searchDebounceTimer = window.setTimeout(() => {
      ctx.runSearch();
    }, ctx.SEARCH_DEBOUNCE_MS);
  };

  ctx.fetchAuthStatus = async function fetchAuthStatus() {
    if (!ctx.socket.value?.connected) return;

    try {
      const state = await ctx.emitWithReply('auth:status');
      ctx.syncAuthState(state);
      ctx.restoreStartupPage(state);
      if (state.signedIn) await ctx.loadHomeLibrary();
    } catch (error) {
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.startLogin = async function startLogin() {
    if (!ctx.socket.value?.connected) return;

    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';

    try {
      const state = await ctx.emitWithReply('auth:login');
      ctx.syncAuthState(state);
      ctx.resetNavigation('home');
      if (state.signedIn) await ctx.loadHomeLibrary();
    } catch (error) {
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.signOut = async function signOut() {
    ctx.accountMenuOpen.value = false;
    try {
      const state = await ctx.emitWithReply('auth:logout');
      ctx.syncAuthState(state);
      ctx.resetBrowseTrackPaging();
      ctx.browseDetail.value = null;
      ctx.sectionMoreDetail.value = null;
      ctx.resetNavigation('home');
      ctx.homeData.value = { home: { sections: [] }, library: { sections: [] } };
    } catch (error) {
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.switchAccount = async function switchAccount() {
    if (!ctx.socket.value?.connected || ctx.accountSwitching.value) return;

    ctx.accountMenuOpen.value = false;
    ctx.accountSwitching.value = true;
    ctx.errorMessage.value = '';
    try {
      await ctx.emitWithReply('auth:switch-account');
    } catch (error) {
      ctx.errorMessage.value = error.message;
    } finally {
      ctx.accountSwitching.value = false;
    }
  };

  ctx.loadHomeLibrary = async function loadHomeLibrary() {
    if (!ctx.socket.value?.connected) return;
    if (ctx.homeLoadPromise) return ctx.homeLoadPromise;

    ctx.homeLoadPromise = (async () => {
      ctx.homeLoading.value = true;
      ctx.errorMessage.value = '';
      ctx.warningMessage.value = '';

      try {
        const data = await ctx.emitWithReply('music:home');
        ctx.homeData.value = {
          home: data.home || { sections: [] },
          library: data.library || { sections: [] }
        };
        ctx.warningMessage.value = data.warnings?.join(' ') || '';
        ctx.syncAuthState(data.auth || { signedIn: true, status: 'signed_in' });
      } catch (error) {
        ctx.errorMessage.value = error.message;
      } finally {
        ctx.homeLoading.value = false;
        ctx.homeLoadPromise = null;
      }
    })();

    return ctx.homeLoadPromise;
  };

}
