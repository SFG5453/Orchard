// Encapsulates authenticated YouTube like-state reads and mutations.
function videoId(value) {
  return String(value || '').trim();
}

function likedFromAction(action) {
  const status = String(
    action?.like_status ||
    action?.like_status_entity?.like_status ||
    action?.like_button?.like_status_entity?.like_status ||
    ''
  ).toUpperCase();

  if (status) return status === 'LIKE';
  if (action?.icon_type === 'LIKE' && typeof action?.is_toggled === 'boolean') {
    return action.is_toggled;
  }

  return undefined;
}

function likedFromInfo(info) {
  if (typeof info?.basic_info?.is_liked === 'boolean') return info.basic_info.is_liked;

  for (const action of info?.player_overlays?.actions || []) {
    const liked = likedFromAction(action);
    if (typeof liked === 'boolean') return liked;
  }

  const memo = info?.page?.[1]?.contents_memo;
  for (const type of ['MusicLikeButton', 'LikeButton', 'LikeButtonView', 'ToggleButton']) {
    for (const action of memo?.get?.(type) || []) {
      const liked = likedFromAction(action);
      if (typeof liked === 'boolean') return liked;
    }
  }

  return false;
}

function isAuthenticationCredentialError(error) {
  return Number(error?.status) === 401 || /\b401\b|required authentication credential/i.test(error?.message || '');
}

export function createYouTubeLikesService({ ensureSignedIn, refreshBrowserAuth, getBrowserInnertube }) {
  async function trackInfo(id) {
    const cleanId = videoId(id);
    if (!cleanId) throw new Error('A YouTube video ID is required.');

    await refreshBrowserAuth();
    const yt = await ensureSignedIn();

    try {
      return { yt, info: await yt.music.getInfo(cleanId), videoId: cleanId };
    } catch (error) {
      if (!isAuthenticationCredentialError(error)) throw error;

      await refreshBrowserAuth();
      const browserYt = await getBrowserInnertube();
      if (browserYt && browserYt !== yt) {
        return { yt: browserYt, info: await browserYt.music.getInfo(cleanId), videoId: cleanId };
      }
      throw error;
    }
  }

  async function status({ videoId: id } = {}) {
    const { info, videoId: cleanId } = await trackInfo(id);
    return { videoId: cleanId, liked: likedFromInfo(info) };
  }

  async function set({ videoId: id, liked } = {}) {
    const { yt, info, videoId: cleanId } = await trackInfo(id);
    const currentLiked = likedFromInfo(info);
    const shouldLike = Boolean(liked);

    if (currentLiked !== shouldLike) {
      const endpoint = shouldLike ? '/like/like' : '/like/removelike';
      await yt.actions.execute(endpoint, { target: { videoId: cleanId } });
    }

    return { videoId: cleanId, liked: shouldLike };
  }

  return { status, set };
}
