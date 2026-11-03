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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Collects an album's music videos into one shelf: its video shelves plus tracks that are videos.

const VIDEO_TYPE = /MUSIC_VIDEO_TYPE_(OMV|UGC)/;
const VIDEO_TITLE = /\b(official (music )?video|music video|visualizer)\b/i;

export function isVideoShelf(section) {
  return /\bvideos?\b/i.test(`${section?.key || ''} ${section?.title || ''}`);
}

function isVideoTrack(track) {
  return Boolean(track?.id) && !track.unplayable && !track.isAudioOnly &&
    (track.type === 'video' || VIDEO_TYPE.test(track.musicVideoType || '') || VIDEO_TITLE.test(track.title || ''));
}

/** [sections] with every video shelf folded into a leading "Music Videos" shelf. */
export function withAlbumVideos(sections, tracks) {
  const seen = new Set();
  const items = [
    ...sections.filter(isVideoShelf).flatMap((section) => section.items || []),
    // Album rows carry the album cover; the video's own frame reads as a video.
    ...tracks.filter(isVideoTrack).map((track) => ({ ...track, thumbnail: `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg` }))
  ].filter((item) => {
    if (!item?.id || seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  }).map((item) => ({ ...item, type: 'video', isAudioOnly: false }));
  const rest = sections.filter((section) => !isVideoShelf(section));
  return items.length ? [{ key: 'album-music-videos', title: 'Music Videos', items, browsePayload: null }, ...rest] : rest;
}
