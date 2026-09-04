/*
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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { computed, nextTick, ref } from 'vue';

export const RECENTLY_PLAYED_HOME_SECTION_ID = 'home:recently-played';
export const LIBRARY_HOME_SECTION_ID = 'home:library';

export const SIDEBAR_NAV_GROUPS = [
  {
    id: 'youtube-music',
    label: 'YouTube Music',
    items: [
      { id: 'home', label: 'Home', icon: 'home', mini: true },
      { id: 'new', label: 'New', icon: 'grid_view', mini: true, requiresAuth: true },
      { id: 'radio', label: 'Radio', icon: 'sensors', mini: true, requiresAuth: true },
      { id: 'concerts', label: 'Concerts', icon: 'local_activity', mini: true, requiresAuth: true }
    ]
  },
  {
    id: 'library',
    label: 'Library',
    items: [
      { id: 'queue', label: 'Queue', icon: 'playlist_play' },
      { id: 'history', label: 'Recently Added', icon: 'schedule', requiresAuth: true },
      { id: 'replay', label: 'Replay', icon: 'leaderboard', requiresAuth: true },
      { id: 'playlists', label: 'Playlists', icon: 'queue_music', requiresAuth: true },
      { id: 'songs', label: 'Songs', icon: 'music_note', requiresAuth: true },
      { id: 'albums', label: 'Albums', icon: 'album', requiresAuth: true },
      { id: 'artists', label: 'Artists', icon: 'mic', requiresAuth: true },
      { id: 'podcasts', label: 'Podcasts', icon: 'podcasts', mini: true, requiresAuth: true }
    ]
  },
  {
    id: 'pins',
    label: 'Pins',
    items: [
      { id: 'pins', label: 'Pins', icon: 'push_pin', mini: true, requiresAuth: true }
    ]
  }
];

export const SIDEBAR_NAV_ITEM_IDS = SIDEBAR_NAV_GROUPS.flatMap((group) =>
  group.items.map((item) => item.id)
);

export function normalizeLayoutIds(value) {
  if (!Array.isArray(value)) return [];
  return [...new Set(value
    .filter((id) => typeof id === 'string')
    .map((id) => id.trim())
    .filter(Boolean))];
}

export function homeSectionId(section = {}) {
  if (section.isHistory || section.key === 'recently-played') {
    return RECENTLY_PLAYED_HOME_SECTION_ID;
  }

  const title = String(section.title || '').trim().toLowerCase();
  if (title) return `home:${title}`;

  const key = String(section.key || '').trim().toLowerCase();
  return key ? `home-key:${key}` : '';
}

export function orderLayoutItems(items, preferredOrder = []) {
  const rank = new Map(normalizeLayoutIds(preferredOrder).map((id, index) => [id, index]));
  return items
    .map((item, sourceIndex) => ({ item, sourceIndex }))
    .sort((left, right) => {
      const leftRank = rank.get(left.item.id);
      const rightRank = rank.get(right.item.id);
      if (leftRank !== undefined && rightRank !== undefined) return leftRank - rightRank;
      if (leftRank !== undefined) return -1;
      if (rightRank !== undefined) return 1;
      return left.sourceIndex - right.sourceIndex;
    })
    .map(({ item }) => item);
}

export function visibleLayoutItems(items, preferredOrder = [], hiddenIds = []) {
  const hidden = new Set(normalizeLayoutIds(hiddenIds));
  return orderLayoutItems(items, preferredOrder).filter((item) => !hidden.has(item.id));
}

function replaceVisibility(hiddenIds, id, visible) {
  const hidden = new Set(normalizeLayoutIds(hiddenIds));
  if (visible) hidden.delete(id);
  else hidden.add(id);
  return [...hidden];
}

function moveItem(items, id, direction) {
  const ids = items.map((item) => item.id);
  const index = ids.indexOf(id);
  const target = index + direction;
  if (index < 0 || target < 0 || target >= ids.length) return ids;
  [ids[index], ids[target]] = [ids[target], ids[index]];
  return ids;
}

function baseHomeLayoutItems(ctx) {
  const items = [
    { id: LIBRARY_HOME_SECTION_ID, label: 'Library', description: 'Your saved playlists and music.' },
    { id: RECENTLY_PLAYED_HOME_SECTION_ID, label: 'Recently Played', description: 'Tracks played during this session.' }
  ];

  for (const section of ctx.homeShelfSections?.value || []) {
    const id = homeSectionId(section);
    if (!id || items.some((item) => item.id === id)) continue;
    items.push({ id, label: section.title || 'Untitled section', description: 'YouTube Music recommendation shelf.' });
  }

  return items;
}

export function installNavigationLayout(ctx) {
  ctx.homeSectionOrder = ref(ctx.initialUserPreferences.homeSectionOrder);
  ctx.hiddenHomeSectionIds = ref(ctx.initialUserPreferences.hiddenHomeSectionIds);
  ctx.sidebarItemOrder = ref(ctx.initialUserPreferences.sidebarItemOrder);
  ctx.hiddenSidebarItemIds = ref(ctx.initialUserPreferences.hiddenSidebarItemIds);

  ctx.homeLayoutItems = computed(() => {
    const hidden = new Set(ctx.hiddenHomeSectionIds.value);
    return orderLayoutItems(baseHomeLayoutItems(ctx), ctx.homeSectionOrder.value)
      .map((item) => ({ ...item, visible: !hidden.has(item.id) }));
  });

  ctx.sidebarLayoutGroups = computed(() => {
    const hidden = new Set(ctx.hiddenSidebarItemIds.value);
    return SIDEBAR_NAV_GROUPS.map((group) => ({
      ...group,
      items: orderLayoutItems(group.items, ctx.sidebarItemOrder.value)
        .map((item) => ({ ...item, visible: !hidden.has(item.id) }))
    }));
  });

  ctx.visibleSidebarNavGroups = computed(() => ctx.sidebarLayoutGroups.value
    .map((group) => ({ ...group, items: group.items.filter((item) => item.visible) }))
    .filter((group) => group.items.length));

  ctx.visibleMiniSidebarItems = computed(() => {
    const groups = new Map(ctx.visibleSidebarNavGroups.value.map((group) => [group.id, group.items]));
    return ['youtube-music', 'pins', 'library']
      .flatMap((groupId) => groups.get(groupId) || [])
      .filter((item) => item.mini);
  });

  ctx.applyHomeSectionLayout = function applyHomeSectionLayout(sections) {
    const items = sections.map((section) => ({ ...section, id: homeSectionId(section) }));
    return visibleLayoutItems(items, ctx.homeSectionOrder.value, ctx.hiddenHomeSectionIds.value);
  };

  ctx.setHomeSectionVisible = function setHomeSectionVisible(id, visible) {
    ctx.hiddenHomeSectionIds.value = replaceVisibility(ctx.hiddenHomeSectionIds.value, id, visible);
  };

  ctx.moveHomeSection = function moveHomeSection(id, direction) {
    ctx.homeSectionOrder.value = moveItem(ctx.homeLayoutItems.value, id, direction);
  };

  ctx.resetHomeLayout = function resetHomeLayout() {
    ctx.homeSectionOrder.value = [];
    ctx.hiddenHomeSectionIds.value = [];
  };

  ctx.setSidebarItemVisible = function setSidebarItemVisible(id, visible) {
    ctx.hiddenSidebarItemIds.value = replaceVisibility(ctx.hiddenSidebarItemIds.value, id, visible);
  };

  ctx.moveSidebarItem = function moveSidebarItem(groupId, id, direction) {
    const group = ctx.sidebarLayoutGroups.value.find((candidate) => candidate.id === groupId);
    if (!group) return;
    const movedGroupIds = moveItem(group.items, id, direction);
    const movedGroup = new Set(movedGroupIds);
    const currentIds = ctx.sidebarLayoutGroups.value.flatMap((candidate) => candidate.items.map((item) => item.id));
    let groupIndex = 0;
    ctx.sidebarItemOrder.value = currentIds.map((itemId) => (
      movedGroup.has(itemId) ? movedGroupIds[groupIndex++] : itemId
    ));
  };

  ctx.resetSidebarLayout = function resetSidebarLayout() {
    ctx.sidebarItemOrder.value = [];
    ctx.hiddenSidebarItemIds.value = [];
  };

  ctx.sidebarItemActive = function sidebarItemActive(item) {
    const sectionKey = ctx.searchResult.value.sections?.[0]?.key;
    switch (item.id) {
      case 'home': return ctx.activeView.value === 'home';
      case 'new': return ctx.activeView.value === 'releaseRadar';
      case 'radio': return ctx.activeView.value === 'browse' && ctx.browseDetail.value?.title === 'My Supermix';
      case 'concerts': return ctx.activeView.value === 'search' && ctx.searchResult.value.source === 'ticketmaster';
      case 'queue': return ctx.activeView.value === 'queue';
      case 'history': return ctx.activeView.value === 'search' && sectionKey === 'library-recently-added';
      case 'replay': return ctx.activeView.value === 'replay';
      case 'playlists': return ctx.activeView.value === 'search' && sectionKey === 'library-playlists';
      case 'songs': return ctx.activeView.value === 'search' && sectionKey === 'library-songs';
      case 'albums': return ctx.activeView.value === 'search' && sectionKey === 'library-albums';
      case 'artists': return ctx.activeView.value === 'search' && sectionKey === 'library-artists';
      case 'podcasts': return ctx.activeView.value === 'podcasts';
      case 'pins': return ctx.activeView.value === 'pins';
      default: return false;
    }
  };

  ctx.openSidebarItem = function openSidebarItem(item) {
    switch (item.id) {
      case 'home': return ctx.selectView('home');
      case 'new': return ctx.showReleaseRadar();
      case 'radio': return ctx.openPersonalizedRadio();
      case 'concerts': return ctx.openLiveShows();
      case 'queue': return ctx.selectView('queue');
      case 'history': return ctx.showRecentlyAdded();
      case 'replay': return ctx.selectView('replay');
      case 'playlists': return ctx.showLibraryPlaylists();
      case 'songs': return ctx.showLibrarySongs();
      case 'albums': return ctx.showLibraryAlbums();
      case 'artists': return ctx.showSubscribedArtists();
      case 'podcasts': return ctx.loadPodcasts();
      case 'pins': return ctx.showPins();
      default: return undefined;
    }
  };

  ctx.showLayoutSettings = async function showLayoutSettings() {
    ctx.selectView('settings');
    await nextTick();
    document.getElementById('settings-layout')?.scrollIntoView({ block: 'start' });
  };
}
