import { computed, nextTick, ref } from 'vue';

function cleanText(value = '') {
  return String(value || '').trim();
}

function rowText(row) {
  return [row.title, row.subtitle, row.type].map(cleanText).join(' ').toLowerCase();
}

function mediaRowId(ctx, item, section, index) {
  return [
    'media',
    section.key || 'section',
    item.id || ctx.itemBrowseId(item) || item.title || index
  ].join(':');
}

export function installSpotlightActions(ctx) {
  ctx.spotlightOpen = ref(false);
  ctx.spotlightQuery = ref('');
  ctx.spotlightResult = ref({ sections: [] });
  ctx.spotlightLoading = ref(false);
  ctx.spotlightActiveIndex = ref(0);
  ctx.spotlightInputRef = ref(null);
  ctx.spotlightRequest = 0;
  ctx.spotlightSearchTimer = 0;

  ctx.spotlightCommandRows = computed(() => {
    const query = cleanText(ctx.spotlightQuery.value);
    const rows = [
      {
        id: 'search',
        title: query ? `Search for "${query}"` : 'Search Orchard',
        subtitle: query ? 'Open full results' : 'Songs, albums, artists, videos, and playlists',
        icon: 'search',
        disabled: !ctx.authState.value.signedIn || !query,
        run: () => ctx.openSpotlightFullSearch()
      },
      {
        id: 'home',
        title: 'Home',
        subtitle: 'Library and recommendations',
        icon: 'home',
        run: () => ctx.openSpotlightView('home')
      },
      {
        id: 'radio',
        title: 'Radio',
        subtitle: 'Your personalized Supermix',
        icon: 'radio',
        disabled: !ctx.authState.value.signedIn,
        run: () => {
          ctx.closeSpotlightSearch();
          ctx.openPersonalizedRadio();
        }
      },
      {
        id: 'live-shows',
        title: 'Live Shows',
        subtitle: 'Nearby concerts from Ticketmaster',
        icon: 'confirmation_number',
        disabled: !ctx.authState.value.signedIn,
        run: () => {
          ctx.closeSpotlightSearch();
          ctx.openLiveShows();
        }
      },
      {
        id: 'pins',
        title: 'Pins',
        subtitle: 'Saved songs',
        icon: 'push_pin',
        disabled: !ctx.authState.value.signedIn,
        run: () => ctx.openSpotlightView('pins')
      },
      {
        id: 'release-radar',
        title: 'New',
        subtitle: 'Release Radar',
        icon: 'new_releases',
        disabled: !ctx.authState.value.signedIn,
        run: () => {
          ctx.closeSpotlightSearch();
          ctx.showReleaseRadar();
        }
      },
      {
        id: 'podcasts',
        title: 'Podcasts',
        subtitle: 'Episodes and shows',
        icon: 'podcasts',
        disabled: !ctx.authState.value.signedIn,
        run: () => {
          ctx.closeSpotlightSearch();
          ctx.loadPodcasts();
        }
      },
      {
        id: 'settings',
        title: 'Settings',
        subtitle: 'Playback, appearance, backup, and diagnostics',
        icon: 'settings',
        run: () => ctx.openSpotlightView('settings')
      },
      {
        id: 'support',
        title: 'Support',
        subtitle: ctx.supportUnreadCount?.value ? `${ctx.supportUnreadCount.value} unread` : 'Reports and replies',
        icon: 'support_agent',
        run: () => ctx.openSpotlightView('support')
      }
    ];

    if (!query) return rows.filter((row) => !row.disabled).slice(0, 7);
    return rows.filter((row) => !row.disabled && rowText(row).includes(query.toLowerCase())).slice(0, 4);
  });

  ctx.spotlightMediaRows = computed(() => ctx.spotlightResult.value.sections
    .flatMap((section) => (section.items || []).map((item, index) => ({
      id: mediaRowId(ctx, item, section, index),
      title: item.title || 'Untitled',
      subtitle: [ctx.itemMeta(item), section.title].filter(Boolean).join(' - '),
      type: ctx.itemTypeLabel(item),
      icon: ctx.isArtistItem(item) ? 'person' : ctx.isPlayableTrack(item) ? 'music_note' : 'album',
      artwork: item.thumbnail || '',
      item,
      source: section.items || [],
      run: () => {
        ctx.closeSpotlightSearch();
        ctx.openMedia(item, section.items || []);
      }
    })))
    .slice(0, 8));

  ctx.spotlightRows = computed(() => [
    ...ctx.spotlightCommandRows.value,
    ...ctx.spotlightMediaRows.value
  ].slice(0, 10));

  ctx.openSpotlightSearch = async function openSpotlightSearch(initialQuery = '') {
    const nextQuery = cleanText(initialQuery || ctx.query.value);
    ctx.spotlightOpen.value = true;
    ctx.spotlightQuery.value = nextQuery;
    ctx.spotlightActiveIndex.value = 0;
    ctx.queueSpotlightSearch();
    await nextTick();
    ctx.spotlightInputRef.value?.focus();
    ctx.spotlightInputRef.value?.select();
  };

  ctx.closeSpotlightSearch = function closeSpotlightSearch() {
    ctx.spotlightOpen.value = false;
    ctx.spotlightActiveIndex.value = 0;
    window.clearTimeout(ctx.spotlightSearchTimer);
  };

  ctx.openSpotlightView = function openSpotlightView(view) {
    ctx.closeSpotlightSearch();
    ctx.selectView(view);
  };

  ctx.openSpotlightRow = function openSpotlightRow(row = ctx.spotlightRows.value[ctx.spotlightActiveIndex.value]) {
    if (!row?.run || row.disabled) return;
    row.run();
  };

  ctx.openSpotlightFullSearch = async function openSpotlightFullSearch() {
    const query = cleanText(ctx.spotlightQuery.value);
    if (!query) return;

    ctx.query.value = query;
    ctx.closeSpotlightSearch();
    await ctx.runSearch();
  };

  ctx.submitSpotlightSearch = async function submitSpotlightSearch() {
    const query = cleanText(ctx.spotlightQuery.value);
    const row = ctx.spotlightRows.value[ctx.spotlightActiveIndex.value];
    if (row && (row.id !== 'search' || query)) {
      ctx.openSpotlightRow(row);
      return;
    }

    if (query) await ctx.openSpotlightFullSearch();
  };

  ctx.queueSpotlightSearch = function queueSpotlightSearch() {
    window.clearTimeout(ctx.spotlightSearchTimer);
    ctx.spotlightActiveIndex.value = 0;

    const query = cleanText(ctx.spotlightQuery.value);
    if (!query || !ctx.authState.value.signedIn || !ctx.socket.value?.connected || ctx.searchLinkCandidate(query)) {
      ctx.spotlightRequest += 1;
      ctx.spotlightLoading.value = false;
      ctx.spotlightResult.value = { sections: [] };
      return;
    }

    ctx.spotlightLoading.value = true;
    ctx.spotlightSearchTimer = window.setTimeout(() => {
      ctx.runSpotlightSearch(query);
    }, 180);
  };

  ctx.runSpotlightSearch = async function runSpotlightSearch(query = ctx.spotlightQuery.value) {
    const searchQuery = cleanText(query);
    const requestId = ++ctx.spotlightRequest;

    try {
      const result = await ctx.emitWithReply('music:search', {
        query: searchQuery,
        filter: 'all'
      });
      if (requestId !== ctx.spotlightRequest) return;
      ctx.spotlightResult.value = result || { sections: [] };
    } catch {
      if (requestId === ctx.spotlightRequest) ctx.spotlightResult.value = { sections: [] };
    } finally {
      if (requestId === ctx.spotlightRequest) ctx.spotlightLoading.value = false;
    }
  };

  ctx.moveSpotlightSelection = function moveSpotlightSelection(offset) {
    const rows = ctx.spotlightRows.value;
    if (!rows.length) return;

    const next = (ctx.spotlightActiveIndex.value + offset + rows.length) % rows.length;
    ctx.spotlightActiveIndex.value = next;
  };

  ctx.handleSpotlightShortcut = function handleSpotlightShortcut(event) {
    if (event.defaultPrevented || event.repeat) return false;
    if (ctx.spotlightOpen.value || ctx.isEditableKeyboardTarget?.(event.target)) return false;

    const isSlash = event.key === '/' && !event.altKey && !event.ctrlKey && !event.metaKey;
    const isControlK = event.key.toLowerCase() === 'k' && event.ctrlKey && !event.altKey && !event.metaKey;
    if (!isSlash && !isControlK) return false;

    event.preventDefault();
    ctx.openSpotlightSearch('');
    return true;
  };
}
