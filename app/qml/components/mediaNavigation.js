/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
.pragma library

function albumId(media) {
    return media.type === "album" || media.kind === "album"
        ? (media.browsePayload || {}).browseId || media.browseId || media.albumId || ""
        : media.albumId || "";
}

function artistIds(media) {
    return media.type === "artist" || media.kind === "artist"
        ? [media.browseId || (media.browsePayload || {}).browseId].filter(Boolean)
        : (media.artistBrowseIds && media.artistBrowseIds.length ? media.artistBrowseIds
           : (media.artistBrowseId || media.artistId ? [media.artistBrowseId || media.artistId] : []));
}

function artistAt(media, index) {
    return {browseId: artistIds(media)[index], type: "artist",
            title: (media.artists || [])[index] || media.artist || media.title || ""};
}
