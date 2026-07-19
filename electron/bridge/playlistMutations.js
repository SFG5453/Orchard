function playlistId(value = '') {
  const id = String(value || '').trim();
  return id.startsWith('VL') ? id.slice(2) : id;
}

function mutationError(error) {
  const message = String(error?.message || 'Playlist update failed.');
  if (/signed in|sign in|logged_in/i.test(message)) {
    return new Error('Sign in again before editing playlists.');
  }
  return error;
}

function textValue(value) {
  if (typeof value === 'string') return value;
  if (typeof value?.simpleText === 'string') return value.simpleText;
  if (Array.isArray(value?.runs)) return value.runs.map((run) => run.text || '').join('');
  return '';
}

function collectPlaylistOptions(value, results = [], seen = new Set(), depth = 0) {
  if (!value || typeof value !== 'object' || depth > 18 || seen.has(value)) return results;
  seen.add(value);

  const option = value.playlistAddToOptionRenderer;
  if (option?.playlistId) {
    results.push({
      id: playlistId(option.playlistId),
      title: textValue(option.title) || 'Playlist',
      privacy: String(option.privacy || '').toLowerCase(),
      containsTrack: option.containsSelectedVideos === 'ALL'
    });
  }

  for (const child of Object.values(value)) {
    if (child && typeof child === 'object') collectPlaylistOptions(child, results, seen, depth + 1);
  }
  return results;
}

function findPlaylistSetVideoId(value, videoId, seen = new Set(), depth = 0) {
  if (!value || typeof value !== 'object' || depth > 18 || seen.has(value)) return '';
  seen.add(value);
  const item = value.playlistItemData;
  if (item?.videoId === videoId && item.playlistSetVideoId) return item.playlistSetVideoId;

  for (const child of Object.values(value)) {
    const found = findPlaylistSetVideoId(child, videoId, seen, depth + 1);
    if (found) return found;
  }
  return '';
}

// Registers authenticated playlist mutations while keeping credentials in the main process.
export function createPlaylistMutations({ ensureSignedIn, refreshBrowserAuth }) {
  async function signedInClient() {
    await refreshBrowserAuth();
    return ensureSignedIn();
  }

  async function canEditWithClient(yt, id) {
    const normalizedId = playlistId(id);
    if (!normalizedId || normalizedId.startsWith('RD')) return false;

    try {
      const playlist = await yt.music.getPlaylist(normalizedId);
      return Boolean(playlist.header?.edit_header || playlist.header?.playlist_id);
    } catch {
      return false;
    }
  }

  async function canEdit(id, videoId = '') {
    const yt = await signedInClient();
    if (videoId) {
      try {
        const options = await addToPlaylistOptions(yt, videoId);
        if (options.some((item) => item.id === playlistId(id))) return true;
      } catch {
        // Fall through to playlist header detection.
      }
    }
    return canEditWithClient(yt, id);
  }

  async function addToPlaylistOptions(yt, videoId) {
    const response = await yt.actions.execute('/playlist/get_add_to_playlist', {
      videoIds: [videoId],
      excludeWatchLater: true,
      client: 'YTMUSIC'
    });
    const unique = new Map();
    collectPlaylistOptions(response.data).forEach((item) => unique.set(item.id, item));
    return [...unique.values()];
  }

  async function playlistContainsTrack(yt, id, videoId) {
    let page = await yt.music.getPlaylist(playlistId(id));
    for (let pageIndex = 0; pageIndex < 40; pageIndex += 1) {
      if (page.items?.some((item) => item.id === videoId)) return true;
      if (!page.has_continuation) return false;
      page = await page.getContinuation();
    }
    return false;
  }

  async function editableTargets({ playlists = [], videoId = '' } = {}) {
    const yt = await signedInClient();
    const candidates = new Map(playlists
      .filter((item) => playlistId(item?.id))
      .slice(0, 40)
      .map((item) => [playlistId(item.id), { ...item, id: playlistId(item.id) }]));
    let options = [];

    if (videoId) {
      try {
        options = await addToPlaylistOptions(yt, videoId);
      } catch {
        // Older or alternate clients may not expose the picker endpoint.
      }
    }

    const results = options.map((option) => ({
      ...candidates.get(option.id),
      ...option,
      editable: true
    }));
    const knownIds = new Set(results.map((item) => item.id));
    const missing = [...candidates.values()].filter((item) => !knownIds.has(item.id));

    for (let index = 0; index < missing.length; index += 4) {
      const group = missing.slice(index, index + 4);
      const checks = await Promise.all(group.map(async (item) => ({
        ...item,
        editable: await canEditWithClient(yt, item.id),
        containsTrack: videoId ? await playlistContainsTrack(yt, item.id, videoId).catch(() => false) : false
      })));
      results.push(...checks.filter((item) => item.editable));
    }

    return results;
  }

  async function create({ title, videoId }) {
    const cleanTitle = String(title || '').trim();
    const cleanVideoId = String(videoId || '').trim();
    if (!cleanTitle) throw new Error('Enter a playlist name.');
    if (!cleanVideoId) throw new Error('This track cannot be added to a playlist.');

    const yt = await signedInClient();
    try {
      const result = await yt.playlist.create(cleanTitle, [cleanVideoId]);
      if (!result.success || !result.playlist_id) throw new Error('YouTube did not create the playlist.');
      return { id: result.playlist_id, title: cleanTitle };
    } catch (error) {
      throw mutationError(error);
    }
  }

  async function add({ playlistId: targetId, videoId }) {
    const id = playlistId(targetId);
    const cleanVideoId = String(videoId || '').trim();
    if (!id || !cleanVideoId) throw new Error('Playlist or track information is missing.');

    const yt = await signedInClient();
    try {
      const options = await addToPlaylistOptions(yt, cleanVideoId).catch(() => []);
      const target = options.find((item) => item.id === id);
      if (target?.containsTrack) throw new Error('This song is already in that playlist.');
      if (options.length && !target) throw new Error('This playlist cannot be edited.');
      if (!options.length && !await canEditWithClient(yt, id)) throw new Error('This playlist cannot be edited.');
      await yt.playlist.addVideos(id, [cleanVideoId]);
      return { id };
    } catch (error) {
      throw mutationError(error);
    }
  }

  async function remove({ playlistId: targetId, videoId }) {
    const id = playlistId(targetId);
    const cleanVideoId = String(videoId || '').trim();
    if (!id || !cleanVideoId) throw new Error('Playlist or track information is missing.');

    const yt = await signedInClient();
    try {
      try {
        await yt.playlist.removeVideos(id, [cleanVideoId]);
      } catch (error) {
        const response = await yt.actions.execute('/browse', {
          browseId: `VL${id}`,
          client: 'YTMUSIC'
        });
        const setVideoId = findPlaylistSetVideoId(response.data, cleanVideoId);
        if (!setVideoId) throw error;
        await yt.actions.execute('/browse/edit_playlist', {
          playlistId: id,
          actions: [{ action: 'ACTION_REMOVE_VIDEO', setVideoId }],
          client: 'YTMUSIC'
        });
      }
      return { id };
    } catch (error) {
      throw mutationError(error);
    }
  }

  async function deletePlaylist({ playlistId: targetId, videoId = '' }) {
    const id = playlistId(targetId);
    if (!id) throw new Error('Playlist information is missing.');

    const yt = await signedInClient();
    let editable = await canEditWithClient(yt, id);
    if (!editable && videoId) {
      const options = await addToPlaylistOptions(yt, videoId).catch(() => []);
      editable = options.some((item) => item.id === id);
    }
    if (!editable) throw new Error('This playlist cannot be deleted.');
    try {
      const result = await yt.actions.execute('/playlist/delete', {
        playlistId: id,
        client: 'YTMUSIC'
      });
      if (result.success === false) throw new Error('YouTube did not delete the playlist.');
      return { id };
    } catch (error) {
      throw mutationError(error);
    }
  }

  function register(socket, bridgeError) {
    const handle = (event, action) => {
      socket.on(event, async (payload, reply) => {
        try {
          reply({ ok: true, data: await action(payload || {}) });
        } catch (error) {
          reply({ ok: false, error: bridgeError(error) });
        }
      });
    };

    handle('music:playlists:editable', editableTargets);
    handle('music:playlist:create', create);
    handle('music:playlist:add-track', add);
    handle('music:playlist:remove-track', remove);
    handle('music:playlist:delete', deletePlaylist);
  }

  return { canEdit, register };
}
