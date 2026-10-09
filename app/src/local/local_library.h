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

#include "local_store.h"

#include <QObject>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

namespace local {
struct ImportResult;
struct CoverResult;
} // namespace local

// Songs and playlists that live on this computer instead of YouTube Music.
// Files are referenced in place; Orchard only keeps tags, covers and lyrics.
class LocalLibrary final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QVariantList playlists READ playlists NOTIFY changed)
  Q_PROPERTY(QVariantList songs READ songs NOTIFY changed)
  // The playlist opened with openPlaylist(), in the shape PlaylistView reads.
  Q_PROPERTY(QVariantMap detail READ detail NOTIFY detailChanged)
  Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
  Q_PROPERTY(QStringList audioNameFilters READ audioNameFilters CONSTANT)
  Q_PROPERTY(QStringList imageNameFilters READ imageNameFilters CONSTANT)
  Q_PROPERTY(QStringList lyricsNameFilters READ lyricsNameFilters CONSTANT)

public:
  explicit LocalLibrary(QString rootDir = defaultRoot(), QObject *parent = nullptr);
  ~LocalLibrary() override;

  static QString defaultRoot();

  [[nodiscard]] QVariantList playlists() const;
  [[nodiscard]] QVariantList songs() const;
  [[nodiscard]] QVariantMap detail() const { return m_detail; }
  [[nodiscard]] bool busy() const { return m_jobs > 0; }
  [[nodiscard]] QStringList audioNameFilters() const;
  [[nodiscard]] QStringList imageNameFilters() const;
  [[nodiscard]] QStringList lyricsNameFilters() const;

  // Playlists. createPlaylist returns the new id, so callers can open it.
  Q_INVOKABLE QString createPlaylist(const QString &title, const QVariantList &files = {});
  Q_INVOKABLE void renamePlaylist(const QString &id, const QString &title);
  Q_INVOKABLE void setPlaylistDescription(const QString &id, const QString &description);
  Q_INVOKABLE void deletePlaylist(const QString &id);
  Q_INVOKABLE void openPlaylist(const QString &id);
  Q_INVOKABLE void closePlaylist();

  // Files and folders, as URLs or paths. Folders are scanned recursively.
  Q_INVOKABLE void importFiles(const QVariantList &files, const QString &playlistId = {});
  Q_INVOKABLE void addTrackToPlaylist(const QString &playlistId, const QString &trackId);
  Q_INVOKABLE void removeTrack(const QString &playlistId, int index);
  // Same, for callers that know the song but not its row (context menus).
  Q_INVOKABLE void removeTrackById(const QString &playlistId, const QString &trackId);
  Q_INVOKABLE void moveTrack(const QString &playlistId, int from, int to);
  Q_INVOKABLE void removeSong(const QString &trackId);

  // Art and lyrics from the user. Pictures may be PNG, JPEG, WebP, GIF or MP4.
  Q_INVOKABLE void setPlaylistCover(const QString &playlistId, const QVariant &file);
  Q_INVOKABLE void clearPlaylistCover(const QString &playlistId);
  Q_INVOKABLE void setTrackCover(const QString &trackId, const QVariant &file);
  Q_INVOKABLE void clearTrackCover(const QString &trackId);
  Q_INVOKABLE void setTrackLyrics(const QString &trackId, const QVariant &file);
  Q_INVOKABLE void clearTrackLyrics(const QString &trackId);

  // Lyrics in LyricsController's format; status "unavailable" when there are none.
  Q_INVOKABLE QVariantMap lyricsFor(const QString &trackId) const;
  Q_INVOKABLE QVariantMap track(const QString &trackId) const;
  // The playlist picker's rows for one song: { id, title, containsTrack }.
  Q_INVOKABLE QVariantList targetsFor(const QString &trackId) const;
  Q_INVOKABLE bool hasPlaylists() const { return !m_store.playlists.isEmpty(); }

  // Where the picture or lyrics file of a QML url or plain path points.
  static QString pathFrom(const QVariant &file);

signals:
  void changed();
  void detailChanged();
  void busyChanged();
  void noticeRequested(const QString &message);
  void playlistCreated(const QString &playlistId);

private:
  void beginJob();
  void endJob();
  void commit(const QString &playlistId = {});
  void refreshCollage(local::PlaylistRecord &playlist);
  void refreshDetail();
  void scheduleSave();
  void finishImport(const local::ImportResult &result, const QString &playlistId);
  void applyCover(const QString &ownerId, bool playlist, const local::CoverResult &result);
  void dropCoverFiles(const QString &still, const QString &animated);

  local::LocalStore m_store;
  QVariantMap m_detail;
  QString m_openId;
  QTimer m_saveTimer;
  int m_jobs{0};
};
