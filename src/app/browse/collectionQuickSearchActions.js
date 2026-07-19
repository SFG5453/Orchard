import { computed, nextTick, ref, watch } from 'vue';

const SCROLL_DELAY = 420;
const MAX_VISIBLE_MATCHES = 7;

function normalizedText(value = '') {
  return String(value || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim();
}

function wordStartsWith(text, query) {
  return text.split(/\s+/).some((word) => word.startsWith(query));
}

function matchScore(text, query, baseScore = 0) {
  if (!text || !query) return null;
  if (text === query) return baseScore;
  if (text.startsWith(query)) return baseScore + 1;
  if (wordStartsWith(text, query)) return baseScore + 2;
  if (text.includes(query)) return baseScore + 3;
  return null;
}

function trackArtist(track = {}, detail = {}) {
  if (track.artist) return track.artist;
  if (Array.isArray(track.artists)) {
    return track.artists.map((artist) => artist?.name || artist).filter(Boolean).join(', ');
  }
  return detail.artist || detail.subtitle || '';
}

export function collectionTrackMatch(track, detail, query, index) {
  const title = normalizedText(track?.title);
  const artist = normalizedText(trackArtist(track, detail));
  const album = detail?.kind === 'playlist' ? normalizedText(track?.album) : '';
  const scores = [
    matchScore(title, query, 0),
    matchScore(artist, query, 4),
    matchScore(album, query, 8)
  ].filter((score) => score !== null);

  if (!scores.length) return null;
  return {
    id: `${track?.id || track?.index || track?.title || 'track'}:${index}`,
    index,
    score: Math.min(...scores),
    track,
    title: track?.title || 'Untitled',
    subtitle: [trackArtist(track, detail), detail?.kind === 'playlist' ? track?.album : '']
      .filter(Boolean)
      .join(' • '),
    artwork: track?.thumbnail || (detail?.kind === 'album' ? detail?.thumbnail || '' : '')
  };
}

export function installCollectionQuickSearchActions(ctx) {
  ctx.collectionQuickSearchOpen = ref(false);
  ctx.collectionQuickSearchQuery = ref('');
  ctx.collectionQuickSearchInputRef = ref(null);
  ctx.collectionQuickSearchActiveIndex = ref(0);
  ctx.collectionQuickSearchFocusedTrackIndex = ref(null);
  ctx.collectionQuickSearchTimer = 0;
  ctx.collectionQuickSearchScrollHandler = null;

  ctx.collectionQuickSearchMatches = computed(() => {
    const detail = ctx.browseDetail.value;
    const query = normalizedText(ctx.collectionQuickSearchQuery.value);
    if (!query || !['album', 'playlist'].includes(detail?.kind)) return [];

    return (detail.tracks || [])
      .map((track, index) => collectionTrackMatch(track, detail, query, index))
      .filter(Boolean)
      .sort((left, right) => left.score - right.score || left.index - right.index);
  });

  ctx.collectionQuickSearchVisibleMatches = computed(() =>
    ctx.collectionQuickSearchMatches.value.slice(0, MAX_VISIBLE_MATCHES)
  );

  ctx.closeCollectionQuickSearch = function closeCollectionQuickSearch() {
    window.clearTimeout(ctx.collectionQuickSearchTimer);
    ctx.collectionQuickSearchOpen.value = false;
    ctx.collectionQuickSearchQuery.value = '';
    ctx.collectionQuickSearchActiveIndex.value = 0;
    ctx.collectionQuickSearchFocusedTrackIndex.value = null;
  };

  ctx.scrollToCollectionQuickSearchMatch = function scrollToCollectionQuickSearchMatch(match) {
    if (!match) return;
    ctx.collectionQuickSearchFocusedTrackIndex.value = match.index;
    ctx.collectionQuickSearchScrollHandler?.(match.index);
  };

  ctx.queueCollectionQuickSearchScroll = function queueCollectionQuickSearchScroll() {
    window.clearTimeout(ctx.collectionQuickSearchTimer);
    ctx.collectionQuickSearchActiveIndex.value = 0;
    const matches = ctx.collectionQuickSearchMatches.value;

    if (!matches.length) {
      ctx.collectionQuickSearchFocusedTrackIndex.value = null;
      return;
    }
    if (matches.length === 1) {
      ctx.scrollToCollectionQuickSearchMatch(matches[0]);
      return;
    }

    ctx.collectionQuickSearchTimer = window.setTimeout(() => {
      ctx.scrollToCollectionQuickSearchMatch(
        ctx.collectionQuickSearchVisibleMatches.value[ctx.collectionQuickSearchActiveIndex.value]
      );
    }, SCROLL_DELAY);
  };

  ctx.openCollectionQuickSearch = async function openCollectionQuickSearch(initialQuery = '') {
    ctx.collectionQuickSearchOpen.value = true;
    ctx.collectionQuickSearchQuery.value = initialQuery;
    ctx.queueCollectionQuickSearchScroll();
    await nextTick();
    ctx.collectionQuickSearchInputRef.value?.focus();
  };

  ctx.moveCollectionQuickSearchSelection = function moveCollectionQuickSearchSelection(offset) {
    const matches = ctx.collectionQuickSearchVisibleMatches.value;
    if (!matches.length) return;
    const next = (ctx.collectionQuickSearchActiveIndex.value + offset + matches.length) % matches.length;
    ctx.collectionQuickSearchActiveIndex.value = next;
    ctx.scrollToCollectionQuickSearchMatch(matches[next]);
  };

  ctx.handleCollectionQuickSearchShortcut = function handleCollectionQuickSearchShortcut(event) {
    const detail = ctx.browseDetail.value;
    if (event.defaultPrevented || event.repeat) return false;

    if (ctx.collectionQuickSearchOpen.value) {
      if (ctx.isEditableKeyboardTarget?.(event.target) || event.altKey || event.ctrlKey || event.metaKey) return false;

      if (event.key === 'Backspace') {
        event.preventDefault();
        ctx.collectionQuickSearchQuery.value = ctx.collectionQuickSearchQuery.value.slice(0, -1);
      } else if (event.key.length === 1 && (event.key.trim() || ctx.collectionQuickSearchQuery.value)) {
        event.preventDefault();
        ctx.collectionQuickSearchQuery.value += event.key;
      } else {
        return false;
      }

      ctx.queueCollectionQuickSearchScroll();
      void nextTick(() => ctx.collectionQuickSearchInputRef.value?.focus());
      return true;
    }

    if (ctx.activeView.value !== 'browse' || !['album', 'playlist'].includes(detail?.kind)) return false;
    if (!detail?.tracks?.length || event.altKey || event.ctrlKey || event.metaKey) return false;
    if (ctx.isEditableKeyboardTarget?.(event.target) || event.key.length !== 1 || !event.key.trim()) return false;
    if (document.querySelector('.q-dialog--modal, [role="dialog"][aria-modal="true"]')) return false;

    event.preventDefault();
    void ctx.openCollectionQuickSearch(event.key);
    return true;
  };

  watch(() => [ctx.activeView.value, ctx.browseDetail.value?.browseId], () => {
    if (ctx.collectionQuickSearchOpen.value) ctx.closeCollectionQuickSearch();
  });
}
