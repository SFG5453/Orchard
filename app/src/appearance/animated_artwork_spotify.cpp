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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Spotify Canvas as the mirror of last resort. Vertical phone video in a square
// frame: the crop is the price of having anything move at all.
#include "animated_artwork_lookup.h"
#include "integrations/spotify_canvas.h"

void AnimatedArtworkService::setSpotifyCanvas(SpotifyCanvas *spotify) {
  m_spotify = spotify;
  if (spotify)
    connect(spotify, &SpotifyCanvas::accountConnected, this, &AnimatedArtworkService::forgetMisses);
}

void AnimatedArtworkService::forgetMisses() {
  m_store.forgetMisses();
  for (auto it = m_cache.begin(); it != m_cache.end();) {
    if (it.value().isEmpty()) {
      m_retryAfterMs.remove(it.key());
      it = m_cache.erase(it);
    } else {
      ++it;
    }
  }
}

void AnimatedArtworkService::fetchSpotify(std::shared_ptr<LookupState> state) {
  if (!m_spotify || !m_spotify->connected()) {
    queryNextMirror(state);
    return;
  }
  m_spotify->canvasFor(state->title, state->artist, [this, state](const QString &url, bool failed) {
    if (failed)
      state->mirrorFailed = true;
    if (url.isEmpty())
      queryNextMirror(state);
    else
      acceptMotionUrl(url, state);
  });
}
