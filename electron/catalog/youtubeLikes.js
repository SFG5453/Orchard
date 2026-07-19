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

export function createYouTubeLikesService({ ensureSignedIn, refreshBrowserAuth }) {
  async function trackInfo(id) {
    const cleanId = videoId(id);
    if (!cleanId) throw new Error('A YouTube video ID is required.');

    await refreshBrowserAuth();
    const yt = await ensureSignedIn();
    return { yt, info: await yt.music.getInfo(cleanId), videoId: cleanId };
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
