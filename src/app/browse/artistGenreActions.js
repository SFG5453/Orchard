export function installArtistGenreActions(ctx) {
  ctx.artistGenreRequest = 0;

  ctx.artistGenreAlbumHint = function artistGenreAlbumHint(detail) {
    if (!detail || detail.kind !== 'artist') return '';

    const sections = Array.isArray(detail.sections) ? detail.sections : [];
    const releaseSections = [
      ...sections.filter((section) => /\balbums?\b/i.test(section.title || '')),
      ...sections.filter((section) => /singles?|eps?|releases?|discography/i.test(section.title || ''))
    ];

    for (const section of releaseSections) {
      const release = (section.items || []).find((item) => {
        const title = String(item?.title || '').trim();
        return title && ctx.normalizedLookupText(title) !== ctx.normalizedLookupText(detail.title);
      });
      if (release?.title) return release.title;
    }

    const typedRelease = sections
      .flatMap((section) => section.items || [])
      .find((item) => item?.type === 'album' && item?.title);
    if (typedRelease?.title) return typedRelease.title;

    return (detail.tracks || []).map((track) => track?.album).find(Boolean) || '';
  };

  ctx.resetArtistGenre = function resetArtistGenre(detail = null) {
    ctx.artistGenreRequest += 1;
    ctx.artistGenre.value = {
      status: 'idle',
      browseId: detail?.browseId || '',
      album: '',
      genre: '',
      source: '',
      error: ''
    };
  };

  ctx.loadArtistGenre = async function loadArtistGenre(detail = ctx.browseDetail.value) {
    if (!detail || detail.kind !== 'artist' || !detail.browseId || !detail.title) {
      ctx.resetArtistGenre(detail);
      return;
    }

    const album = ctx.artistGenreAlbumHint(detail);
    if (!album) {
      ctx.artistGenre.value = {
        status: 'waiting',
        browseId: detail.browseId,
        album: '',
        genre: '',
        source: '',
        error: ''
      };
      return;
    }

    const key = `${detail.browseId}:${ctx.normalizedLookupText(album)}`;
    if (
      ctx.artistGenre.value.status === 'loading' &&
      ctx.artistGenre.value.browseId === detail.browseId &&
      ctx.artistGenre.value.album === album
    ) {
      return;
    }
    const cached = ctx.artistGenreCache.get(key);
    if (cached) {
      ctx.artistGenre.value = cached;
      return;
    }

    const requestId = ++ctx.artistGenreRequest;
    ctx.artistGenre.value = {
      status: 'loading',
      browseId: detail.browseId,
      album,
      genre: '',
      source: 'iTunes',
      error: ''
    };

    try {
      const payload = await ctx.emitWithReply('music:itunes-artist-genre', {
        artist: detail.title,
        album,
        browseId: detail.browseId,
        country: 'US'
      });
      if (requestId !== ctx.artistGenreRequest || ctx.browseDetail.value?.browseId !== detail.browseId) return;

      const result = {
        status: payload.matched && payload.genre ? 'ready' : 'missing',
        browseId: detail.browseId,
        album,
        genre: payload.genre || '',
        source: 'iTunes',
        providerArtistId: payload.providerArtistId || '',
        confirmedAlbum: payload.confirmedAlbum || album,
        error: ''
      };
      ctx.artistGenreCache.set(key, result);
      ctx.artistGenre.value = result;
    } catch (error) {
      if (requestId !== ctx.artistGenreRequest || ctx.browseDetail.value?.browseId !== detail.browseId) return;
      ctx.artistGenre.value = {
        status: 'error',
        browseId: detail.browseId,
        album,
        genre: '',
        source: 'iTunes',
        error: error instanceof Error ? error.message : String(error)
      };
    }
  };
}
