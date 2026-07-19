import { computed } from 'vue';
import { playlistArtworkDetection } from '../appearance/playlistArtwork.js';
import { sortBySearchPopularity, sortByTopMatch } from '../browse/searchRanking.js';

function sectionTitle(section) {
  return String(section?.title || '').trim().toLowerCase();
}

function itemKey(ctx, item) {
  return ctx.itemBrowseId(item) || item?.id || `${item?.type || 'item'}:${item?.title || ''}`;
}

function dedupeItems(ctx, items) {
  const seen = new Set();

  return items.filter((item) => {
    const key = itemKey(ctx, item);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function withoutItems(ctx, items, excludedItems) {
  const excluded = new Set(excludedItems.map((item) => itemKey(ctx, item)));
  return items.filter((item) => !excluded.has(itemKey(ctx, item)));
}

function itemMetadataText(item) {
  return [
    item?.subtitle,
    item?.itemCount,
    item?.duration,
    item?.year,
    item?.views
  ].filter(Boolean).join(' ').toLowerCase();
}

function isGeneratedMixItem(item) {
  const title = String(item?.title || '').toLowerCase();
  const browseId = String(item?.browsePayload?.browseId || item?.browseId || '');
  return /\b(mix|radio)\b/.test(title) || browseId.startsWith('RD');
}

function isSavedPlaylistItem(ctx, item) {
  if (!ctx.isPlaylistItem(item) || !ctx.itemBrowseId(item)) return false;
  const title = String(item?.title || '').toLowerCase();
  const metadata = itemMetadataText(item);
  const hasLibraryMetadata = /\b(auto playlist|playlist|tracks?|episodes?|queued)\b/.test(metadata);
  const isBuiltInLibraryPlaylist = /^(liked music|episodes for later)$/.test(title);
  const isProbablyGenerated = isGeneratedMixItem(item) && !hasLibraryMetadata && !isBuiltInLibraryPlaylist;

  return (isBuiltInLibraryPlaylist || hasLibraryMetadata) && !isProbablyGenerated;
}

function isLibraryPlaylistSection(section) {
  return ['library', 'playlists', 'from your library'].includes(sectionTitle(section));
}

export function installComputedState(ctx) {
  ctx.flatResults = computed(() => ctx.searchResult.value.sections.flatMap((section) => section.items));

  ctx.flatPlayableResults = computed(() => ctx.flatResults.value.filter(ctx.isPlayableTrack));

  ctx.searchTopResults = computed(() => {
    const sections = new Map(ctx.searchResult.value.sections.map((section) => [
      section.key,
      sortBySearchPopularity(section.items)
    ]));
    const artists = sortByTopMatch(sections.get('artists') || [], ctx.query.value);
    const candidates = [
      ...artists.slice(0, 3),
      ...(sections.get('songs') || []).slice(0, 3),
      ...(sections.get('albums') || []).slice(0, 2)
    ].filter(Boolean);
    const seen = new Set();

    return sortByTopMatch(candidates.filter((item) => {
      const key = item.id || ctx.itemBrowseId(item) || `${item.type}:${item.title}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    }), ctx.query.value).slice(0, 6);
  });

  ctx.searchCategorySections = computed(() => {
    const order = ['events', 'artists', 'albums', 'songs', 'playlists', 'videos'];
    return [...ctx.searchResult.value.sections]
      .filter((section) => section.items?.length)
      .map((section) => ({ ...section, items: sortBySearchPopularity(section.items) }))
      .sort((left, right) => order.indexOf(left.key) - order.indexOf(right.key));
  });

  ctx.flatHomeItems = computed(() => [
    ...ctx.homeData.value.library.sections.flatMap((section) => section.items),
    ...ctx.homeData.value.home.sections.flatMap((section) => section.items)
  ]);

  ctx.flatPlayableHomeItems = computed(() => ctx.flatHomeItems.value.filter(ctx.isPlayableTrack));

  ctx.hasHomeContent = computed(() =>
    ctx.homeData.value.library.sections.some((section) => section.items.length) ||
    ctx.homeData.value.home.sections.some((section) => section.items.length)
  );

  ctx.showAuthGate = computed(() => (
    !ctx.authState.value.signedIn && !['settings', 'support'].includes(ctx.activeView.value)
  ));

  ctx.authLabel = computed(() => {
    if (ctx.authState.value.signedIn) return 'Signed in';
    if (ctx.authState.value.status === 'pending') return 'Finish sign in';
    if (ctx.authState.value.status === 'starting') return 'Starting sign in';
    return 'Signed out';
  });

  ctx.activeArtist = computed(() => {
    if (!ctx.activeTrack.value) return '';
    return ctx.activeTrack.value.artist || ctx.activeTrack.value.artists?.join(', ') || '';
  });

  ctx.playbackStatusPopup = computed(() => {
    if (!ctx.activeTrack.value) return null;
    if (ctx.playbackError.value) return { icon: 'warning', tone: 'error', message: ctx.playbackError.value };
    return ctx.buffering.value ? { icon: 'hourglass_empty', tone: 'loading', message: 'Buffering...' } : null;
  });

  ctx.nowArtworkImage = computed(() => ctx.enhancedArtwork.value?.static || ctx.activeTrack.value?.thumbnail || '');

  ctx.fullscreenArtworkImage = computed(() => ctx.highResolutionArtworkImage(ctx.activeTrack.value?.thumbnail || ctx.nowArtworkImage.value));

  ctx.discordArtworkImage = computed(() =>
    ctx.enhancedArtwork.value?.static ||
    ctx.activeTrack.value?.thumbnail ||
    ''
  );

  ctx.nowArtworkVideo = computed(() => ctx.nowArtworkVideoFailed.value ? '' : ctx.enhancedArtwork.value?.videoUrl || '');

  ctx.detailArtworkImage = computed(() =>
    ctx.browseDetail.value?.customProfileArtwork ||
    ctx.detailEnhancedArtwork.value?.static ||
    ctx.browseDetail.value?.thumbnail ||
    ''
  );

  ctx.detailArtworkVideo = computed(() => ctx.detailArtworkVideoFailed.value ? '' : ctx.detailEnhancedArtwork.value?.videoUrl || '');

  ctx.playlistArtworkDetection = computed(() => playlistArtworkDetection(ctx.browseDetail.value));

  ctx.playlistArtworkCollageItems = computed(() => {
    const items = ctx.playlistArtworkCollage.value;
    return ctx.playlistArtworkDetection.value.canUseGeneratedCover && items.length === 4 ? items : [];
  });

  ctx.detailHeroBackdrop = computed(() => ctx.heroBackdropStyle(
    ctx.browseDetail.value,
    ctx.browseDetail.value?.customHeroArtwork || ctx.detailArtworkImage.value
  ));

  ctx.browseDetailVideoSection = computed(() => {
    const detail = ctx.browseDetail.value;
    if (detail?.kind !== 'album') return null;

    const sectionVideos = (detail.sections || [])
      .filter(ctx.isVideoShelfSection)
      .flatMap((section) => section.items || []);
    const trackVideos = (detail.tracks || [])
      .filter((track) => ctx.isPlayableTrack(track) && ctx.trackHasVideoVersion(track))
      .map((track) => ctx.albumVideoItem(track, detail));
    const items = ctx.dedupeAlbumVideos([...sectionVideos, ...trackVideos]);

    return items.length ? { key: 'album-music-videos', title: 'Music Videos', items } : null;
  });

  ctx.browseDetailSections = computed(() => {
    const sections = ctx.browseDetail.value?.sections || [];
    if (ctx.browseDetail.value?.kind !== 'album' || !ctx.browseDetailVideoSection.value) return sections;
    return sections.filter((section) => !ctx.isVideoShelfSection(section));
  });

  ctx.customBrowseImmersiveArtwork = computed(() =>
    ctx.activeView.value === 'browse' ? ctx.browseDetail.value?.customImmersiveArtwork || '' : ''
  );

  ctx.backdropArtworkImage = computed(() =>
    ctx.nowArtworkImage.value ||
    ctx.customBrowseImmersiveArtwork.value ||
    (ctx.activeView.value === 'browse' ? ctx.detailArtworkImage.value : '') ||
    ''
  );

  ctx.immersiveArtworkImage = computed(() => (
    ctx.immersiveBackgroundsEnabled.value
      ? (ctx.customBrowseImmersiveArtwork.value || ctx.backdropArtworkImage.value || ctx.activeTrack.value?.thumbnail || ctx.lastImmersiveArtworkImage.value)
      : ''
  ));
  ctx.immersiveArtworkVideo = computed(() => ctx.immersiveBackgroundsEnabled.value && !ctx.customBrowseImmersiveArtwork.value
    ? (ctx.enhancedArtwork.value?.videoUrl || ctx.enhancedArtwork.value?.animated || '')
    : '');

  ctx.playerBarStyle = computed(() => ({
    '--player-accent-rgb': ctx.rgbToken(ctx.playerBarAccent.value.rgb),
    '--player-accent-soft-rgb': ctx.rgbToken(ctx.playerBarAccent.value.softRgb),
    '--player-accent-deep-rgb': ctx.rgbToken(ctx.playerBarAccent.value.deepRgb),
    '--player-artwork': ctx.cssImageUrl(ctx.nowArtworkImage.value)
  }));

  ctx.fullscreenPlayerStyle = computed(() => ({
    ...ctx.playerBarStyle.value,
    '--player-artwork': ctx.cssImageUrl(ctx.fullscreenArtworkImage.value || ctx.nowArtworkImage.value)
  }));

  ctx.pageStyle = computed(() => ({
    ...ctx.playerBarStyle.value,
    '--page-artwork': 'none',
    '--page-artwork-opacity': 0
  }));

  ctx.crossfadeProgressStyle = computed(() => {
    const length = Number(ctx.duration.value) || 0;
    const nextTrack = ctx.queue.value[0];
    if (!nextTrack?.id) {
      return {
        '--crossfade-left': '100%',
        '--crossfade-right': '0%',
        '--crossfade-opacity': 0
      };
    }

    const plan = ctx.autoCrossfade.transitionPlan({
      analysis: ctx.crossfadeAnalysis.value,
      currentTime: 0,
      currentTrack: ctx.activeTrack.value,
      duration: length,
      nextAnalysis: ctx.nextCrossfadeAnalysis.value,
      nextTrack
    });
    const start = length > 0
      ? Math.max(0, Math.min(100, (Number(plan.transitionStart) / length) * 100))
      : 100;
    return {
      '--crossfade-left': `${start}%`,
      '--crossfade-right': '0%',
      '--crossfade-opacity': ctx.crossfadeEnabled.value &&
        ctx.activeTrack.value &&
        plan.markerVisible ? 1 : 0
    };
  });

  ctx.rawHomeShelfSections = computed(() =>
    [...ctx.homeData.value.library.sections, ...ctx.homeData.value.home.sections]
      .filter((section) => section.items?.length)
  );

  ctx.libraryPlaylistItems = computed(() => dedupeItems(
    ctx,
    [
      ...ctx.homeData.value.library.sections,
      ...ctx.homeData.value.home.sections.filter(isLibraryPlaylistSection)
    ]
      .flatMap((section) => section.items)
      .filter((item) => isSavedPlaylistItem(ctx, item))
  ));

  ctx.homeShelfSections = computed(() => {
    const playlists = ctx.libraryPlaylistItems.value;
    const sections = ctx.rawHomeShelfSections.value;
    const libraryIndex = sections.findIndex((section) => sectionTitle(section) === 'library');
    const mergedSections = sections.map((section, index) => {
      if (index === libraryIndex) {
        return {
          ...section,
          items: dedupeItems(ctx, [...playlists, ...section.items])
        };
      }

      if (sectionTitle(section) === 'from your library') {
        return {
          ...section,
          items: withoutItems(ctx, section.items, playlists)
        };
      }

      return section;
    });

    if (libraryIndex < 0 && playlists.length) {
      mergedSections.unshift({ key: 'library-playlists', title: 'Library', items: playlists });
    }

    return mergedSections.filter((section) => section.items?.length);
  });

  ctx.sidebarLibraryItems = computed(() => {
    return ctx.libraryPlaylistItems.value.slice(0, 12);
  });

  ctx.userPlaylistItems = computed(() => {
    return ctx.libraryPlaylistItems.value.slice(0, 18);
  });

  ctx.queuePreview = computed(() => ctx.queue.value.slice(0, 6));

  ctx.activeQueueOriginLabel = computed(() => ctx.trackQueueOriginLabel(ctx.activeTrack.value));

  ctx.activeTrackIsVideo = computed(() => ctx.activeMediaKind.value === 'video' || ctx.activeTrack.value?.mediaKind === 'video');

  ctx.showVideoPlayer = computed(() => Boolean(ctx.activeTrack.value?.streamUrl && ctx.activeTrackIsVideo.value));

  ctx.activeTrackIsLive = computed(() => Boolean(
    ctx.activeTrack.value?.isLive &&
    (!Number.isFinite(ctx.duration.value) || ctx.duration.value <= 0)
  ));

  ctx.durationLabel = computed(() => ctx.activeTrackIsLive.value ? 'Live' : ctx.formatTime(ctx.duration.value));

  ctx.displayedTime = computed(() => (ctx.isSeeking.value ? ctx.seekPosition.value : ctx.currentTime.value));

  ctx.displayedLyricTime = computed(() => ctx.displayedTime.value);

  ctx.lyricsStatusText = computed(() => {
    if (!ctx.activeTrack.value) return 'Pick a track to load lyrics';
    if (ctx.lyricsState.value.status === 'loading') return 'Loading lyrics';
    if (ctx.lyricsState.value.status === 'ready') {
      if (ctx.lyricsState.value.mode !== 'synced') return 'Unsynced lyrics';
      return ctx.lyricsState.value.lines.some((line) => line.words?.length || line.adlibs?.length) ? 'Word-synced lyrics' : 'Synced lyrics';
    }
    return 'No Lyrics :/';
  });

  ctx.LYRIC_PAUSE_MIN_SECONDS = 7;

  ctx.LYRIC_PAUSE_LINE_TAIL_SECONDS = 2.4;

  ctx.LYRIC_PAUSE_NEXT_LEAD_SECONDS = 0;

  ctx.lyricPauseWindow = function lyricPauseWindow(line, nextLine) {
    if (typeof line?.startTime !== 'number' || typeof nextLine?.startTime !== 'number') return null;

    const gapLength = nextLine.startTime - line.startTime;
    if (gapLength < ctx.LYRIC_PAUSE_MIN_SECONDS) return null;

    const startTime = line.startTime + ctx.LYRIC_PAUSE_LINE_TAIL_SECONDS;
    const endTime = nextLine.startTime - ctx.LYRIC_PAUSE_NEXT_LEAD_SECONDS;
    if (startTime >= endTime) return null;

    return { startTime, endTime };
  };

  ctx.activeLyricIndex = computed(() => {
    if (ctx.lyricsState.value.mode !== 'synced') return -1;

    const time = ctx.displayedLyricTime.value + 0.25;
    let activeIndex = -1;

    ctx.lyricsState.value.lines.forEach((line, index) => {
      if (typeof line.startTime === 'number' && line.startTime <= time) {
        activeIndex = index;
      }
    });

    return activeIndex;
  });

  ctx.activeLyricPauseIndex = computed(() => {
    if (ctx.lyricsState.value.mode !== 'synced') return -1;

    const time = ctx.displayedLyricTime.value;
    let activeIndex = -1;

    ctx.lyricsState.value.lines.forEach((line, index) => {
      const pauseWindow = ctx.lyricPauseWindow(line, ctx.lyricsState.value.lines[index + 1]);
      if (pauseWindow && time >= pauseWindow.startTime && time < pauseWindow.endTime) {
        activeIndex = index;
      }
    });

    return activeIndex;
  });

  ctx.activeLyricKey = computed(() => (
    ctx.activeLyricPauseIndex.value >= 0
      ? `lyric-pause-${ctx.activeLyricPauseIndex.value}`
      : ctx.activeLyricIndex.value >= 0 ? `lyric-line-${ctx.activeLyricIndex.value}` : ''
  ));

  ctx.lyricWordItems = function lyricWordItems(words = []) {
    const timedWords = words.filter((word) => word?.text && typeof word.startTime === 'number');
    const time = ctx.displayedLyricTime.value + 0.08;

    return timedWords.map((word, index) => {
      const nextWord = timedWords[index + 1];
      const startTime = Number(word.startTime);
      const explicitEnd = Number(word.endTime);
      const nextStart = Number(nextWord?.startTime);
      const endTime = Number.isFinite(explicitEnd) && explicitEnd > startTime
        ? explicitEnd
        : Number.isFinite(nextStart) && nextStart > startTime ? nextStart : startTime + 0.4;
      const progress = Math.max(0, Math.min(1, (time - startTime) / Math.max(endTime - startTime, 0.1)));
      const active = time >= startTime && time < endTime;

      return {
        key: `${index}-${startTime}`,
        text: String(word.text || '').trim(),
        state: active ? 'active' : time >= endTime ? 'past' : 'future',
        progress: `${Math.round((time >= endTime ? 1 : progress) * 100)}%`
      };
    }).filter((word) => word.text);
  };

  ctx.lyricDisplayItems = computed(() => {
    if (ctx.lyricsState.value.status !== 'ready') return [];

    if (ctx.lyricsState.value.mode !== 'synced') {
      return ctx.lyricsState.value.lines.map((line, index) => ({
        key: `lyric-line-${index}`,
        type: 'line',
        index,
        text: line.text,
        words: [],
        adlibs: [],
        agentLane: line.agentLane || '',
        canSeek: false,
        active: false
      }));
    }

    const items = [];

    ctx.lyricsState.value.lines.forEach((line, index) => {
      const nextLine = ctx.lyricsState.value.lines[index + 1];
      const activePause = ctx.activeLyricPauseIndex.value === index;
      items.push({
        key: `lyric-line-${index}`,
        type: 'line',
        index,
        text: line.text,
        words: ctx.lyricWordItems(line.words || []),
        adlibs: ctx.lyricWordItems(line.adlibs || []),
        agentLane: line.agentLane || '',
        seekTime: line.startTime,
        canSeek: typeof line.startTime === 'number',
        active: ctx.activeLyricIndex.value === index && ctx.activeLyricPauseIndex.value < 0
      });

      const pauseWindow = ctx.lyricPauseWindow(line, nextLine);
      if (!pauseWindow || !activePause) return;

      items.push({
        key: `lyric-pause-${index}`,
        type: 'pause',
        active: true
      });
    });

    return items;
  });

  ctx.pageTitle = computed(() => {
    if (ctx.showAuthGate.value) return 'Sign in';
    if (ctx.activeView.value === 'sectionMore') return ctx.sectionMoreDetail.value?.title || 'More';
    if (ctx.activeView.value === 'browse') return '';
    if (ctx.activeView.value === 'pins') return 'Pins';
    if (ctx.activeView.value === 'queue') return 'Queue';
    if (ctx.activeView.value === 'history') return 'Recently Played';
    if (ctx.activeView.value === 'replay') return 'Replay';
    if (ctx.activeView.value === 'releaseRadar') return 'Release Radar';
    if (ctx.activeView.value === 'podcasts') return 'Podcasts';
    if (ctx.activeView.value === 'search') return 'Search';
    if (ctx.activeView.value === 'support') return 'Support';
    if (ctx.activeView.value === 'settings') return 'Settings';
    return 'Home';
  });

  ctx.pageSubtitle = computed(() => {
    if (ctx.showAuthGate.value) return 'YouTube Music account required';

    if (ctx.activeView.value === 'sectionMore') {
      const count = ctx.sectionMoreDetail.value?.items?.length || 0;
      const source = ctx.sectionMoreDetail.value?.sourceTitle;
      return [count ? `${count} items` : '', source].filter(Boolean).join(' from ');
    }

    if (ctx.activeView.value === 'browse') {
      return '';
    }

    if (ctx.activeView.value === 'queue') return 'Upcoming tracks and listening history';
    if (ctx.activeView.value === 'history') return 'Tracks played on this device';
    if (ctx.activeView.value === 'pins') return 'Songs you want close at hand';
    if (ctx.activeView.value === 'replay') return 'Your local listening stats';
    if (ctx.activeView.value === 'releaseRadar') return 'New and upcoming albums from followed artists';
    if (ctx.activeView.value === 'podcasts') return 'Shows and episodes from YouTube Music';
    if (ctx.activeView.value === 'search') return 'Pull results from the InnerTube bridge';
    if (ctx.activeView.value === 'support') return 'Private reports and replies from Orchard Support';
    if (ctx.activeView.value === 'settings') return 'Playback, appearance, and app preferences';
    return ctx.authState.value.signedIn ? 'Your library, mixes, and recent picks' : 'Sign in to load your YouTube Music library';
  });
}
