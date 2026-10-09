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

// Saved playlists and albums, mirrored into the detail map the YouTube pages use.

#include "offline/offline_library.h"
#include "playlist_controller.h"

void PlaylistController::setOfflineLibrary(OfflineLibrary *library) {
  m_offline = library;
  if (library)
    connect(library, &OfflineLibrary::changed, this, &PlaylistController::syncOffline);
}

void PlaylistController::openOffline(const QVariantMap &item) {
  m_localOpen = false;
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
  m_offlineId = m_offline->collectionIdFor(item);
  emit paletteChanged();
  if (m_offlineId.isEmpty()) {
    m_errorMessage = tr("This playlist is not available offline.");
    emit stateChanged();
    emit detailChanged();
    return;
  }
  syncOffline();
  // The palette comes from the saved cover.
  requestArtwork(m_detail.value(QStringLiteral("thumbnail")).toString());
}

void PlaylistController::syncOffline() {
  if (m_offlineId.isEmpty() || !m_offline)
    return;
  const QVariantMap detail = m_offline->collectionDetail(m_offlineId);
  if (detail.isEmpty()) {
    m_sourceTracks.clear();
    m_errorMessage = tr("This playlist is not available offline.");
    emit stateChanged();
    emit detailChanged();
    return;
  }
  m_errorMessage.clear();
  m_sourceTracks = detail.value(QStringLiteral("tracks")).toList();
  m_detail = detail;
  applySort();
  emit stateChanged();
  emit detailChanged();
}
