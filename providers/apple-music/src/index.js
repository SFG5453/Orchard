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

import { createAnalysisCatalog } from './analysis.js';
import { createArtistCatalog } from './artist.js';
import { createArtworkCatalog } from './artwork.js';
import { createCatalog } from './catalog.js';
import { createHomeCatalog } from './home.js';
import { createLibraryCatalog } from './library.js';
import { createLibraryWrites } from './libraryWrite.js';
import { createLyricsCatalog } from './lyrics.js';
import { createMusicVideoCatalog } from './musicVideos.js';
import { createPlayback } from './playback.js';
import { createStationCatalog } from './stations.js';
import { createAppleMusicClient } from './client.js';

export { AppleMusicError, apiUrl } from './client.js';
export { APPLE_MUSIC_SOURCE, browseId, isAppleMusicId, parseBrowseId } from './ids.js';
export { ARTIST_VIEWS, heroVideo } from './artist.js';
export { artworkUrl, releaseInfo } from './normalize.js';
export { parsePlaylist, playbackId } from './playback.js';

/**
 * Host-neutral Apple Music catalog. The host owns sign-in, token storage and
 * networking; [session] returns {developerToken, userToken, storefront, origin}.
 */
export function createAppleMusic({ fetchImpl = fetch, session }) {
  if (typeof session !== 'function') throw new TypeError('createAppleMusic requires a session() function');
  const client = createAppleMusicClient({ fetchImpl, session });
  const library = createLibraryCatalog(client);
  return {
    ...createAnalysisCatalog(client),
    ...createArtistCatalog(client),
    ...createArtworkCatalog(client),
    ...createCatalog(client, library),
    ...createHomeCatalog(client),
    ...library,
    ...createLibraryWrites(client),
    ...createLyricsCatalog(client),
    ...createMusicVideoCatalog(client),
    ...createStationCatalog(client),
    playback: createPlayback(client, fetchImpl)
  };
}
