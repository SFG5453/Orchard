import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { fetchCustomArtistConfig, cachedCustomArtistIndex } from '../../app/appearance/customArtistPacks.js';
import { albumWallBannerTiles } from '../../custom-artists/shared/albumWallBanner.js';
import { setupConfiguredArtist } from '../../custom-artists/shared/configuredArtist.js';

export function setupBrowseDetailView(props) {
  const detailPageRef = ref(null);
  const virtualPlaylistRef = ref(null);

  function scrollToCollectionTrack(index) {
    if (props.app.browseDetail.value?.kind === 'playlist') {
      virtualPlaylistRef.value?.scrollToIndex(index);
      return;
    }

    void nextTick(() => {
      detailPageRef.value
        ?.querySelector(`[data-collection-track-index="${index}"]`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }

  onMounted(() => {
    props.app.collectionQuickSearchScrollHandler = scrollToCollectionTrack;
  });

  const descriptionDialogOpen = ref(false);
  const descriptionDialogText = computed(() => {
    const detail = props.app.browseDetail.value;
    if (!detail) return '';
    return detail.description || (detail.kind === 'artist' ? detail.subtitle : '') || '';
  });
  const canOpenDescription = computed(() =>
    ['album', 'artist'].includes(props.app.browseDetail.value?.kind) &&
    Boolean(descriptionDialogText.value)
  );
  const descriptionDialogTitle = computed(() => {
    const detail = props.app.browseDetail.value;
    if (!detail) return 'Description';
    return detail.kind === 'artist' ? `About ${detail.title}` : `About ${detail.title}`;
  });
  const descriptionActionLabel = computed(() =>
    props.app.browseDetail.value?.kind === 'artist' ? 'Read full bio' : 'Read full description'
  );

  const highlightWords = ref([]);

  function createHighlightedSegments(text) {
    if (!text) return [];
    const words = highlightWords.value;
    if (!words || !words.length) {
      return [{ text, highlight: false }];
    }

    const escapedWords = words.map((word) => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    const pattern = new RegExp(`(${escapedWords.join('|')})`, 'gi');

    return text
      .split(pattern)
      .map((part) => ({
        text: part,
        highlight: words.some((word) => word.toLowerCase() === part.toLowerCase())
      }))
      .filter((part) => part.text.length > 0);
  }

  const descriptionSegments = computed(() => {
    const detail = props.app.browseDetail.value;
    if (!detail) return [];
    return createHighlightedSegments(detail.description || '');
  });

  const subtitleSegments = computed(() => {
    const detail = props.app.browseDetail.value;
    if (!detail) return [];
    const text = detail.kind === 'artist' ? detail.subtitle : detail.artist || detail.subtitle;
    return createHighlightedSegments(text || '');
  });

  const descriptionDialogSegments = computed(() => createHighlightedSegments(descriptionDialogText.value));
  const artistGenreLabel = computed(() => {
    const detail = props.app.browseDetail.value;
    const genre = props.app.artistGenre?.value;
    if (!detail || detail.kind !== 'artist' || genre?.browseId !== detail.browseId || genre?.status !== 'ready') return '';
    return genre.genre || '';
  });

  function openDescriptionDialog() {
    if (canOpenDescription.value) descriptionDialogOpen.value = true;
  }

  let currentCustomArtistCleanup = null;
  const customArtistPagesEnabled = () => props.app.customArtistPagesEnabled?.value !== false;

  watch(
    () => props.app.browseDetail.value,
    (detail) => props.app.loadArtistGenre?.(detail),
    { immediate: true }
  );

  watch(() => [
    props.app.browseDetail.value?.browseId,
    props.app.browseDetail.value,
    customArtistPagesEnabled()
  ], async ([newId, detail, pagesEnabled]) => {
    if (currentCustomArtistCleanup) {
      currentCustomArtistCleanup();
      currentCustomArtistCleanup = null;
    }
    highlightWords.value = [];

    if (!pagesEnabled || !detail || detail.kind !== 'artist' || !newId) return;

    try {
      const config = await fetchCustomArtistConfig(newId);
      if (config && customArtistPagesEnabled() && props.app.browseDetail.value === detail) {
        highlightWords.value = config.features?.highlightWords || [];
        currentCustomArtistCleanup = setupConfiguredArtist(props.app, config);
      }
    } catch (error) {
      console.error(`Failed to load hosted custom artist ${newId}:`, error);
    }
  }, { immediate: true });

  onBeforeUnmount(() => {
    if (props.app.collectionQuickSearchScrollHandler === scrollToCollectionTrack) {
      props.app.collectionQuickSearchScrollHandler = null;
    }
    if (currentCustomArtistCleanup) {
      currentCustomArtistCleanup();
      currentCustomArtistCleanup = null;
    }
  });

  const isCustomArtistPage = computed(() => {
    if (!customArtistPagesEnabled()) return false;
    const detail = props.app.browseDetail.value;
    if (!detail || detail.kind !== 'artist') return false;
    return Boolean(cachedCustomArtistIndex()?.artists?.[detail.browseId]);
  });

  const customArtistAlbumWallTiles = computed(() =>
    customArtistPagesEnabled()
      ? albumWallBannerTiles(props.app.browseDetail.value, props.app.mediaThumbnail)
      : []
  );

  return {
    ...props.app,
    app: props.app,
    canOpenDescription,
    descriptionActionLabel,
    descriptionDialogOpen,
    descriptionDialogText,
    descriptionDialogTitle,
    descriptionSegments,
    detailPageRef,
    artistGenreLabel,
    descriptionDialogSegments,
    subtitleSegments,
    openDescriptionDialog,
    virtualPlaylistRef,
    isCustomArtistPage,
    customArtistAlbumWallTiles
  };
}
