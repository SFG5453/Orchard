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

#pragma once

#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QString>

class YouTubeProvider;

class YouTubeCatalog final : public QObject {
  Q_OBJECT

public:
  explicit YouTubeCatalog(YouTubeProvider *provider, QObject *parent = nullptr);

  quint64 fetchHome(const QJsonObject &session);
  quint64 fetchLibrary(const QJsonObject &session);
  quint64 fetchPlaylists(const QJsonObject &session);
  quint64 fetchSearch(const QString &query, const QString &filter,
                     const QJsonObject &session);
  quint64 fetchArtist(const QString &browseId, const QJsonObject &session);
  quint64 fetchAlbum(const QString &browseId, const QJsonObject &session);
  quint64 fetchPlaylist(const QString &browseId, const QJsonObject &session);
  quint64 fetchPlaylistPage(const QString &continuation, int startIndex,
                            const QJsonObject &session);

signals:
  void homeReady(quint64 requestId, const QJsonObject &home);
  void libraryReady(quint64 requestId, const QJsonObject &library);
  void playlistsReady(quint64 requestId, const QJsonArray &playlists);
  void searchReady(quint64 requestId, const QJsonObject &search);
  void artistReady(quint64 requestId, const QJsonObject &artist);
  void albumReady(quint64 requestId, const QJsonObject &album);
  void playlistReady(quint64 requestId, const QJsonObject &playlist);
  void playlistPageReady(quint64 requestId, const QJsonObject &page);
  void requestFailed(quint64 requestId, const QString &message);

private:
  enum class RequestKind { Home, Library, Playlists, Search, Artist, Album, Playlist, PlaylistPage };

  quint64 invoke(RequestKind kind, const QString &method, QJsonObject payload);
  void handleResult(quint64 requestId, const QJsonValue &result);
  void handleFailure(quint64 requestId, const QString &message);

  YouTubeProvider *m_provider;
  QHash<quint64, RequestKind> m_requests;
};
