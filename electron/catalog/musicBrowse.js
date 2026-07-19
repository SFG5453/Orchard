// Provides retry and fallback rules for YouTube Music browse requests.
function normalizeBrowseId(kind, browseId) {
  if (kind === 'playlist' && browseId && ['PL', 'OLAK', 'RD'].some((prefix) => browseId.startsWith(prefix))) {
    return `VL${browseId}`;
  }

  return browseId;
}

function playlistBrowseIdVariants(browseId) {
  if (!browseId) return [];

  const variants = [normalizeBrowseId('playlist', browseId)];
  if (browseId.startsWith('VL')) variants.push(browseId.slice(2));
  variants.push(browseId);

  return [...new Set(variants.filter(Boolean))];
}

function browseIdVariants(kind, browseId) {
  if (kind === 'playlist') return playlistBrowseIdVariants(browseId);
  return [browseId].filter(Boolean);
}

export function musicBrowseRequest(kind, payload = {}) {
  const request = {
    browseId: normalizeBrowseId(kind, payload.browseId),
    client: 'YTMUSIC'
  };

  if (payload.params) request.params = payload.params;
  if (payload.continuation) request.continuation = payload.continuation;

  return request;
}

export function musicBrowseRequests(kind, payload = {}) {
  const requests = [];

  for (const browseId of browseIdVariants(kind, payload.browseId)) {
    requests.push(musicBrowseRequest(kind, { ...payload, browseId }));

    if (payload.params) {
      const { params: _params, ...withoutParams } = payload;
      requests.push(musicBrowseRequest(kind, { ...withoutParams, browseId }));
    }
  }

  return [...new Map(requests.map((request) => [JSON.stringify(request), request])).values()];
}

export function createMusicBrowse({
  getGuestInnertube,
  hasBrowserLoginCookie,
  rawBrowserMusicBrowse,
  resolveMusicCollectionWithBrowserAuth
}) {
  async function resolveMusicCollection(yt, kind, payload = {}) {
  let lastError;
  const attempted = [];

  for (const request of musicBrowseRequests(kind, payload)) {
    attempted.push(`${request.browseId}${request.params ? '+params' : ''}`);

    try {
      const response = await yt.actions.execute('/browse', request);
      return {
        data: response.data,
        browseId: request.browseId,
        browse: async (browsePayload) => {
          const browseResponse = await yt.actions.execute('/browse', musicBrowseRequest('artist', browsePayload));
          return browseResponse.data;
        },
        search: (query, filters) => yt.music.search(query, filters),
        continue: async (continuation) => {
          const continuationResponse = await yt.actions.execute('/browse', {
            continuation,
            client: 'YTMUSIC'
          });
          return continuationResponse.data;
        }
      };
    } catch (error) {
      lastError = error;
    }
  }

  const error = lastError || new Error('No browse request could be built for this collection.');
  error.browseContext = `${kind}; tried ${attempted.join(', ') || 'no browseId'}`;
  throw error;
}

  async function resolveMusicCollectionWithFallback(yt, kind, payload = {}) {
  try {
    return await resolveMusicCollection(yt, kind, payload);
  } catch (primaryError) {
    if (hasBrowserLoginCookie()) {
      try {
        return await resolveMusicCollectionWithBrowserAuth(kind, payload);
      } catch (browserError) {
        console.warn(`Browser-cookie browse fallback failed: ${browserError.message}`);
      }
    }

    const guest = await getGuestInnertube();
    if (guest === yt) throw primaryError;

    try {
      return await resolveMusicCollection(guest, kind, payload);
    } catch (guestError) {
      guestError.browseContext = `${primaryError.browseContext || `${kind}; signed-in browse failed`}; guest fallback failed`;
      throw guestError;
    }
  }
}

  async function continueMusicPlaylistWithFallback(yt, continuation) {
  const request = { continuation, client: 'YTMUSIC' };

  try {
    const response = await yt.actions.execute('/browse', request);
    return response.data;
  } catch (primaryError) {
    if (hasBrowserLoginCookie()) {
      try {
        return await rawBrowserMusicBrowse({
          browseId: '',
          ...request
        });
      } catch (browserError) {
        console.warn(`Browser-cookie playlist continuation failed: ${browserError.message}`);
      }
    }

    const guest = await getGuestInnertube();
    if (guest === yt) throw primaryError;

    try {
      const response = await guest.actions.execute('/browse', request);
      return response.data;
    } catch (guestError) {
      guestError.browseContext = `${primaryError.browseContext || 'playlist continuation failed'}; guest fallback failed`;
      throw guestError;
    }
  }
}

  return {
    continueMusicPlaylistWithFallback,
    resolveMusicCollectionWithFallback
  };
}
