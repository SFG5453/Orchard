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

#pragma once

#include <QByteArray>
#include <QCache>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QElapsedTimer>
#include <QObject>
#include <QSet>
#include <QVariantList>
#include <QVariantMap>

class AuthManager;
class LocalLibrary;
class OfflineLibrary;
class YouTubeCatalog;
class QNetworkReply;
class AppleMusic;
class CatalogSource;

class PlaylistController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool loading READ loading NOTIFY stateChanged)
  Q_PROPERTY(bool loadingPage READ loadingPage NOTIFY stateChanged)
  Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY stateChanged)
  // Separate notifiers so flag flips don't reconvert every track for QML.
  Q_PROPERTY(QVariantMap detail READ detail NOTIFY detailChanged)
  Q_PROPERTY(QVariantMap palette READ palette NOTIFY paletteChanged)
  Q_PROPERTY(bool artworkLoading READ artworkLoading NOTIFY stateChanged)
  // { title, artist } for the four albums a collage cover is stitched from.
  // Empty unless the cover's quadrants really match the first four tracks' art.
  Q_PROPERTY(QVariantList collageAlbums READ collageAlbums NOTIFY collageChanged)
  // Empty key means the playlist's own order.
  Q_PROPERTY(QString sortKey READ sortKey NOTIFY sortChanged)
  Q_PROPERTY(bool sortDescending READ sortDescending NOTIFY sortChanged)

public:
  // `local` serves playlists whose ids start with "local-playlist:"; without it
  // only YouTube playlists open.
  explicit PlaylistController(YouTubeCatalog *catalog, AuthManager *auth,
                              LocalLibrary *local = nullptr, QObject *parent = nullptr);

  // A cached copy stays on screen, without spinners or errors, while its refresh loads.
  [[nodiscard]] bool loading() const { return m_loading && m_heldDetail.isEmpty(); }
  [[nodiscard]] bool loadingPage() const { return m_loadingPage && m_heldDetail.isEmpty(); }
  [[nodiscard]] QString errorMessage() const { return m_heldDetail.isEmpty() ? m_errorMessage : QString(); }
  [[nodiscard]] QVariantMap detail() const { return m_heldDetail.isEmpty() ? m_detail : m_heldDetail; }
  [[nodiscard]] QVariantMap palette() const { return m_palette; }
  [[nodiscard]] bool artworkLoading() const { return m_artworkLoading; }
  [[nodiscard]] QVariantList collageAlbums() const { return m_collageAlbums; }
  [[nodiscard]] QString sortKey() const { return m_sortKey; }
  [[nodiscard]] bool sortDescending() const { return m_sortDescending; }

  // Serves saved playlists and albums, and every playlist while offline.
  void setOfflineLibrary(OfflineLibrary *library);

  Q_INVOKABLE void openPlaylist(const QVariantMap &item);
  // Opens am.playlists pages from Apple Music artist pages.
  void setAppleMusic(AppleMusic *appleMusic, CatalogSource *source) {
    m_appleMusic = appleMusic;
    m_source = source;
  }
  Q_INVOKABLE void fetchNextPage();
  Q_INVOKABLE void retry();
  Q_INVOKABLE void clear();
  // key: "", "title", "artist", "album" or "duration".
  Q_INVOKABLE void setSort(const QString &key, bool descending);
  // Drops a removed entry locally; matches setVideoId, else the first row with videoId.
  void removeTrack(const QString &playlistId, const QString &setVideoId,
                   const QString &videoId);

signals:
  void stateChanged();
  void detailChanged();
  void paletteChanged();
  void sortChanged();
  void collageChanged();

private:
  // playlist_local.cpp: playlists stored on this computer.
  void openLocal(const QVariantMap &item);
  void syncLocal();
  // playlist_offline.cpp: downloaded playlists and albums.
  void openOffline(const QVariantMap &item);
  void syncOffline();
  void receivePlaylist(quint64 requestId, const QJsonObject &playlist);
  void receivePlaylistPage(quint64 requestId, const QJsonObject &page);
  void receiveFailure(quint64 requestId, const QString &message);
  void requestArtwork(const QString &url);
  void sampleArtwork(QByteArray bytes);
  void cancelArtwork();
  // Compares the cover's four quadrants with the first four tracks' art.
  void detectCollage(const QByteArray &coverBytes);
  void setCollageAlbums(QVariantList albums);
  // playlist_cache.cpp: the last complete copy of each playlist, shown while it refreshes.
  bool showCached(const QString &browseId);
  // Drops the cached copy once the refresh completes or stops matching it.
  enum class Settled { NotHeld, Holding, Replaced, Unchanged };
  Settled settleCached(bool complete);
  void cacheComplete();
  void resetSort();
  void applySort();
  [[nodiscard]] QVariantList sortedTracks(const QVariantList &source) const;
  void publishPages(bool force);
  static QVariantMap defaultPalette();

  YouTubeCatalog *m_catalog;
  AuthManager *m_auth;
  LocalLibrary *m_local;
  AppleMusic *m_appleMusic{nullptr};
  CatalogSource *m_source{nullptr};
  quint64 m_appleRequests{0};
  OfflineLibrary *m_offline{nullptr};
  QString m_offlineId;
  bool m_localOpen{false};
  QNetworkAccessManager m_network;
  QNetworkReply *m_artworkReply{nullptr};
  QVariantMap m_pendingItem;
  QVariantMap m_detail;
  QVariantMap m_palette;
  QVariantList m_collageAlbums;
  // Tracks in playlist order; m_detail.tracks holds the sorted view.
  QVariantList m_sourceTracks;
  // The cached copy shown while a refresh loads; empty once the fresh copy takes over.
  QVariantMap m_heldDetail;
  QVariantList m_heldTracks;
  // Account-scoped name of the open playlist's cache entry.
  QString m_cacheKey;
  QString m_sortKey;
  QString m_errorMessage;
  QString m_continuation;
  QElapsedTimer m_publishTimer;
  bool m_pagesUnpublished{false};
  QSet<QString> m_loadedContinuations;
  quint64 m_requestId{0};
  quint64 m_artworkGeneration{0};
  quint64 m_collageGeneration{0};
  bool m_loading{false};
  bool m_loadingPage{false};
  bool m_artworkLoading{false};
  bool m_sortDescending{false};
};
