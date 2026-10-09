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

#include "playlist_controller.h"

#include "auth/auth_manager.h"
#include "local/local_library.h"
#include "local/local_track.h"
#include "offline/offline_library.h"
#include "offline/offline_store.h"
#include "orchard_core.h"
#include "providers/youtube/catalog/youtube_catalog.h"

#include <QByteArray>
#include <QCollator>
#include <QFutureWatcher>
#include <QImage>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QRegularExpression>
#include <QSharedPointer>
#include <QUrl>
#include <QtConcurrent/QtConcurrentRun>
#include <algorithm>
#include <cstdlib>
#include <numeric>
#include <utility>

namespace {
QVariantList rgb(int red, int green, int blue) { return {red, green, blue}; }

QString browseIdFromItem(const QVariantMap &item) {
  const QVariantMap payload =
      item.value(QStringLiteral("browsePayload")).toMap();
  return payload.value(QStringLiteral("browseId"))
                 .toString()
                 .trimmed()
                 .isEmpty()
             ? item.value(QStringLiteral("browseId")).toString().trimmed()
             : payload.value(QStringLiteral("browseId")).toString().trimmed();
}

int trackCount(const QVariantMap &item) {
  static const QRegularExpression countPattern(
      QStringLiteral("([\\d,]+)\\s+(?:songs?|tracks?|videos?)\\b"),
      QRegularExpression::CaseInsensitiveOption);
  int count = item.value(QStringLiteral("totalTrackCount")).toInt();
  for (const auto &key :
       {QStringLiteral("itemCount"), QStringLiteral("subtitle")}) {
    const auto match = countPattern.match(item.value(key).toString());
    if (match.hasMatch())
      count = qMax(count, match.captured(1).remove(',').toInt());
  }
  return count;
}

void copyIfMissing(QVariantMap &target, const QVariantMap &source,
                   const QString &key) {
  if (!target.value(key).isValid() ||
      target.value(key).toString().trimmed().isEmpty())
    target.insert(key, source.value(key));
}

bool isSortKey(const QString &key) {
  return key.isEmpty() || key == QStringLiteral("title") ||
         key == QStringLiteral("artist") || key == QStringLiteral("album") ||
         key == QStringLiteral("duration");
}

QString sortText(const QVariantMap &track, const QString &key) {
  if (key != QStringLiteral("artist"))
    return track.value(key).toString().trimmed();
  const QString artist = track.value(QStringLiteral("artist")).toString();
  return (artist.trimmed().isEmpty()
              ? track.value(QStringLiteral("artists")).toStringList().join(
                    QStringLiteral(", "))
              : artist)
      .trimmed();
}

} // namespace

PlaylistController::PlaylistController(YouTubeCatalog *catalog,
                                       AuthManager *auth, LocalLibrary *local,
                                       QObject *parent)
    : QObject(parent), m_catalog(catalog), m_auth(auth), m_local(local), m_network(this),
      m_palette(defaultPalette()) {
  Q_ASSERT(m_catalog);
  Q_ASSERT(m_auth);

  connect(m_catalog, &YouTubeCatalog::playlistReady, this,
          &PlaylistController::receivePlaylist);
  connect(m_catalog, &YouTubeCatalog::playlistPageReady, this,
          &PlaylistController::receivePlaylistPage);
  connect(m_catalog, &YouTubeCatalog::requestFailed, this,
          &PlaylistController::receiveFailure);
  connect(m_auth, &AuthManager::sessionChanged, this,
          &PlaylistController::clear);
  if (m_local)
    connect(m_local, &LocalLibrary::detailChanged, this,
            &PlaylistController::syncLocal);
}

QVariantMap PlaylistController::defaultPalette() {
  return {
      {QStringLiteral("seam"), rgb(38, 48, 43)},
      {QStringLiteral("accent"), rgb(127, 190, 144)},
      {QStringLiteral("accentSoft"), rgb(150, 202, 164)},
      {QStringLiteral("deep"), rgb(15, 21, 18)},
      {QStringLiteral("ink"), rgb(8, 12, 10)},
      {QStringLiteral("surface"), rgb(24, 31, 27)},
      {QStringLiteral("surfaceRaised"), rgb(50, 62, 53)},
      {QStringLiteral("onAccent"), rgb(9, 16, 11)},
  };
}

void PlaylistController::openPlaylist(const QVariantMap &item) {
  if (m_local && local::isLocalPlaylistId(item.value(QStringLiteral("id")).toString())) {
    openLocal(item);
    return;
  }
  if (m_offline && (m_offline->offline() ||
                    offline::isCollectionId(item.value(QStringLiteral("id")).toString()))) {
    openOffline(item);
    return;
  }
  m_localOpen = false;
  m_offlineId.clear();
  m_requestId = 0;
  cancelArtwork();
  resetSort();
  m_detail.clear();
  m_sourceTracks.clear();
  m_continuation.clear();
  m_loadedContinuations.clear();
  const QString browseId = browseIdFromItem(item);
  if (browseId.isEmpty()) {
    m_pendingItem = item;
    m_loading = false;
    m_loadingPage = false;
    m_errorMessage = tr("This page is missing its YouTube browse ID.");
    emit stateChanged();
    emit detailChanged();
    return;
  }
  if (!m_auth->isSignedIn()) {
    m_pendingItem = item;
    m_loading = false;
    m_loadingPage = false;
    m_errorMessage = tr("Sign in to open playlists.");
    emit stateChanged();
    emit detailChanged();
    return;
  }

  m_pendingItem = item;
  m_detail = item;
  m_palette = defaultPalette();
  m_errorMessage.clear();
  m_loading = true;
  m_loadingPage = false;
  m_auth->refreshSession();
  m_requestId = m_catalog->fetchPlaylist(browseId, m_auth->sessionObject());
  emit stateChanged();
  emit detailChanged();
  emit paletteChanged();
}

void PlaylistController::fetchNextPage() {
  if (m_continuation.isEmpty() || m_loadingPage || m_loading)
    return;

  m_errorMessage.clear();
  m_loadingPage = true;
  const auto startIndex = static_cast<int>(m_sourceTracks.size());
  m_requestId = m_catalog->fetchPlaylistPage(m_continuation, startIndex,
                                             m_auth->sessionObject());
  emit stateChanged();
}

void PlaylistController::retry() {
  if (!m_continuation.isEmpty() && !m_detail.isEmpty())
    fetchNextPage();
  else if (!m_pendingItem.isEmpty())
    openPlaylist(m_pendingItem);
}

void PlaylistController::clear() {
  m_localOpen = false;
  m_offlineId.clear();
  m_requestId = 0;
  cancelArtwork();
  resetSort();
  m_pendingItem.clear();
  m_detail.clear();
  m_sourceTracks.clear();
  m_continuation.clear();
  m_loadedContinuations.clear();
  m_palette = defaultPalette();
  m_errorMessage.clear();
  m_loading = false;
  m_loadingPage = false;
  emit stateChanged();
  emit detailChanged();
  emit paletteChanged();
}

void PlaylistController::setSort(const QString &key, bool descending) {
  if (!isSortKey(key))
    return;
  // Playlist order has no direction worth flipping.
  descending = descending && !key.isEmpty();
  if (key == m_sortKey && descending == m_sortDescending)
    return;
  m_sortKey = key;
  m_sortDescending = descending;
  emit sortChanged();
  if (!m_detail.isEmpty()) {
    applySort();
    emit detailChanged();
  }
}

void PlaylistController::resetSort() {
  if (m_sortKey.isEmpty() && !m_sortDescending)
    return;
  m_sortKey.clear();
  m_sortDescending = false;
  emit sortChanged();
}

void PlaylistController::applySort() {
  if (m_sortKey.isEmpty()) {
    m_detail.insert(QStringLiteral("tracks"), m_sourceTracks);
    return;
  }

  const auto count = m_sourceTracks.size();
  const bool byDuration = m_sortKey == QStringLiteral("duration");
  QList<QString> texts(byDuration ? 0 : count);
  QList<double> seconds(byDuration ? count : 0);
  for (qsizetype index = 0; index < count; ++index) {
    const QVariantMap track = m_sourceTracks.at(index).toMap();
    if (byDuration)
      seconds[index] =
          track.value(QStringLiteral("durationSeconds")).toDouble();
    else
      texts[index] = sortText(track, m_sortKey);
  }

  QCollator collator;
  collator.setCaseSensitivity(Qt::CaseInsensitive);
  // "Track 2" before "Track 10", as nature intended.
  collator.setNumericMode(true);

  QList<qsizetype> order(count);
  std::iota(order.begin(), order.end(), 0);
  // Stable so ties keep playlist order; blanks sink in both directions.
  std::stable_sort(order.begin(), order.end(), [&](qsizetype a, qsizetype b) {
    const bool missingA = byDuration ? seconds[a] <= 0 : texts[a].isEmpty();
    const bool missingB = byDuration ? seconds[b] <= 0 : texts[b].isEmpty();
    if (missingA || missingB)
      return !missingA && missingB;
    const int compared =
        byDuration ? (seconds[a] > seconds[b]) - (seconds[a] < seconds[b])
                   : collator.compare(texts[a], texts[b]);
    return m_sortDescending ? compared > 0 : compared < 0;
  });

  QVariantList sorted;
  sorted.reserve(count);
  for (const qsizetype index : order)
    sorted.append(m_sourceTracks.at(index));
  m_detail.insert(QStringLiteral("tracks"), sorted);
}

void PlaylistController::receivePlaylist(quint64 requestId,
                                         const QJsonObject &playlist) {
  if (requestId != m_requestId)
    return;

  m_requestId = 0;
  m_loading = false;
  m_errorMessage.clear();
  m_detail = playlist.toVariantMap();
  const int knownTotal = trackCount(m_pendingItem) > 0
                             ? trackCount(m_pendingItem)
                             : trackCount(m_detail);
  m_detail.insert(QStringLiteral("totalTrackCount"), knownTotal);
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("title"));
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("author"));
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("thumbnail"));
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("audioPlaylistId"));
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("playlistId"));
  if (m_detail.value(QStringLiteral("browseId")).toString().isEmpty())
    m_detail.insert(QStringLiteral("browseId"),
                    browseIdFromItem(m_pendingItem));

  m_continuation = m_detail.value(QStringLiteral("continuation")).toString();

  QVariantList tracks = m_detail.value(QStringLiteral("tracks")).toList();
  for (QVariant &value : tracks) {
    QVariantMap track = value.toMap();
    copyIfMissing(track, m_detail, QStringLiteral("thumbnail"));
    // A playlist track can have its own album, so we don't override
    // album/albumId like album_controller does.
    value = track;
  }
  m_sourceTracks = tracks;
  applySort();
  m_detail.insert(QStringLiteral("kind"), QStringLiteral("playlist"));
  m_detail.insert(QStringLiteral("totalTrackCount"),
                  qMax(knownTotal, static_cast<int>(tracks.size())));
  // Queue the next page before QML rebuilds the list.
  fetchNextPage();
  requestArtwork(m_detail.value(QStringLiteral("thumbnail")).toString());
  emit stateChanged();
  emit detailChanged();
}

void PlaylistController::receivePlaylistPage(quint64 requestId,
                                             const QJsonObject &page) {
  if (requestId != m_requestId)
    return;

  m_requestId = 0;
  m_loadingPage = false;
  m_loadedContinuations.insert(m_continuation);
  m_continuation = page.value(QStringLiteral("continuation")).toString();
  m_errorMessage.clear();
  if (m_loadedContinuations.contains(m_continuation)) {
    m_continuation.clear();
    m_errorMessage = tr(
        "YouTube repeated a playlist page. Reopen the playlist to try again.");
  }

  QVariantList newTracks =
      page.value(QStringLiteral("tracks")).toArray().toVariantList();

  for (QVariant &value : newTracks) {
    QVariantMap track = value.toMap();
    copyIfMissing(track, m_detail, QStringLiteral("thumbnail"));
    value = track;
  }

  m_sourceTracks.append(newTracks);
  applySort();
  m_detail.insert(
      QStringLiteral("totalTrackCount"),
      qMax(m_detail.value(QStringLiteral("totalTrackCount")).toInt(),
           static_cast<int>(m_sourceTracks.size())));

  m_detail.insert(QStringLiteral("continuation"), m_continuation);
  m_detail.insert(QStringLiteral("hasMoreTracks"), !m_continuation.isEmpty());
  // Keep turning pages, and turn the next one before QML reads this one. The
  // playlist is not a book with a fifty-song cliffhanger.
  fetchNextPage();
  emit stateChanged();
  emit detailChanged();
}

void PlaylistController::receiveFailure(quint64 requestId,
                                        const QString &message) {
  if (requestId != m_requestId)
    return;

  m_requestId = 0;
  if (m_loadingPage) {
    m_loadingPage = false;
    m_errorMessage = message;
  } else {
    m_loading = false;
    m_errorMessage = message;
  }
  emit stateChanged();
}

void PlaylistController::removeTrack(const QString &playlistId, const QString &setVideoId,
                                     const QString &videoId) {
  QString shownId = m_detail.value(QStringLiteral("playlistId")).toString();
  if (shownId.startsWith(QStringLiteral("VL")))
    shownId = shownId.mid(2);
  if (playlistId.isEmpty() || shownId != playlistId)
    return;

  for (qsizetype index = 0; index < m_sourceTracks.size(); ++index) {
    const QVariantMap track = m_sourceTracks.at(index).toMap();
    const bool match = setVideoId.isEmpty()
        ? track.value(QStringLiteral("id")).toString() == videoId
        : track.value(QStringLiteral("setVideoId")).toString() == setVideoId;
    if (!match)
      continue;
    m_sourceTracks.removeAt(index);
    const int total = m_detail.value(QStringLiteral("totalTrackCount")).toInt();
    if (total > 0)
      m_detail.insert(QStringLiteral("totalTrackCount"), total - 1);
    applySort();
    emit detailChanged();
    return;
  }
}
