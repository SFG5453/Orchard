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

// Playlists that live on this computer. The library owns the data; this just
// mirrors it into the same detail map the YouTube pages use.

#include "local/local_library.h"
#include "playlist_controller.h"

void PlaylistController::openLocal(const QVariantMap &item) {
  m_offlineId.clear();
  m_requestId = 0;
  cancelArtwork();
  resetSort();
  m_sourceTracks.clear();
  m_continuation.clear();
  m_loadedContinuations.clear();
  m_pendingItem = item;
  m_detail = item;
  m_palette = defaultPalette();
  m_errorMessage.clear();
  m_loading = false;
  m_loadingPage = false;
  m_localOpen = true;
  emit stateChanged();
  emit detailChanged();
  emit paletteChanged();
  // The library answers with detailChanged, which lands in syncLocal().
  m_local->openPlaylist(item.value(QStringLiteral("id")).toString());
}

void PlaylistController::syncLocal() {
  if (!m_localOpen || !m_local)
    return;
  const QVariantMap detail = m_local->detail();
  if (detail.isEmpty()) {
    // Deleted from somewhere else; the page shows the message instead of a ghost.
    m_detail.clear();
    m_sourceTracks.clear();
    m_errorMessage = tr("This playlist no longer exists.");
    emit stateChanged();
    emit detailChanged();
    return;
  }
  const QString previousCover = m_detail.value(QStringLiteral("thumbnail")).toString();
  m_errorMessage.clear();
  m_sourceTracks = detail.value(QStringLiteral("tracks")).toList();
  m_detail = detail;
  applySort();
  emit stateChanged();
  emit detailChanged();

  const QString cover = detail.value(QStringLiteral("thumbnail")).toString();
  if (cover == previousCover)
    return;
  cancelArtwork();
  if (cover.isEmpty()) {
    m_palette = defaultPalette();
    emit paletteChanged();
    emit stateChanged();
    return;
  }
  requestArtwork(cover);
}
