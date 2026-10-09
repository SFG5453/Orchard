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

#include "youtube_catalog.h"

#include "providers/youtube/youtube_provider.h"

#include <QJsonDocument>
#include <QTextStream>
#include <QtGlobal>
#include <utility>

YouTubeCatalog::YouTubeCatalog(YouTubeProvider *provider, QObject *parent)
    : QObject(parent), m_provider(provider) {
  Q_ASSERT(m_provider);

  // Auth and catalog share one runtime. Only claim request IDs that belong to
  // us, or a profile response could suddenly become a very strange home feed.
  connect(m_provider, &YouTubeProvider::resultReady, this,
          &YouTubeCatalog::handleResult);
  connect(m_provider, &YouTubeProvider::requestFailed, this,
          &YouTubeCatalog::handleFailure);
}

quint64 YouTubeCatalog::fetchHome(const QJsonObject &session) {
  return invoke(RequestKind::Home, QStringLiteral("catalog.home"),
                {{QStringLiteral("session"), session}});
}

quint64 YouTubeCatalog::fetchPlaylists(const QJsonObject &session) {
  return invoke(RequestKind::Playlists, QStringLiteral("catalog.playlists"),
                {{QStringLiteral("session"), session}});
}

quint64 YouTubeCatalog::fetchLibrary(const QJsonObject &session) {
  return invoke(RequestKind::Library, QStringLiteral("catalog.library"),
                {{QStringLiteral("session"), session}});
}

quint64 YouTubeCatalog::fetchSearch(const QString &query, const QString &filter,
                                    const QJsonObject &session) {
  return invoke(RequestKind::Search, QStringLiteral("catalog.search"),
                {{QStringLiteral("session"), session},
                 {QStringLiteral("query"), query.trimmed()},
                 {QStringLiteral("filter"), filter.trimmed().toLower()}});
}

quint64 YouTubeCatalog::fetchArtist(const QString &browseId, const QJsonObject &session) {
  return invoke(RequestKind::Artist, QStringLiteral("catalog.artist"),
                {{QStringLiteral("session"), session},
                 {QStringLiteral("browseId"), browseId.trimmed()}});
}

quint64 YouTubeCatalog::fetchAlbum(const QString &browseId,
                                   const QJsonObject &session) {
  return invoke(RequestKind::Album, QStringLiteral("catalog.album"),
                {{QStringLiteral("session"), session},
                 {QStringLiteral("browseId"), browseId.trimmed()}});
}

quint64 YouTubeCatalog::fetchPlaylist(const QString &browseId,
                                      const QJsonObject &session) {
  return invoke(RequestKind::Playlist, QStringLiteral("catalog.playlist"),
                {{QStringLiteral("session"), session},
                 {QStringLiteral("browseId"), browseId.trimmed()}});
}

quint64 YouTubeCatalog::fetchPlaylistPage(const QString &continuation,
                                          int startIndex,
                                          const QJsonObject &session) {
  return invoke(RequestKind::PlaylistPage,
                QStringLiteral("catalog.playlist.more"),
                {{QStringLiteral("session"), session},
                 {QStringLiteral("continuation"), continuation.trimmed()},
                 {QStringLiteral("startIndex"), qMax(0, startIndex)}});
}

quint64 YouTubeCatalog::invoke(RequestKind kind, const QString &method,
                               QJsonObject payload) {
  const quint64 requestId = m_provider->invoke(method, std::move(payload));
  m_requests.insert(requestId, kind);
  return requestId;
}

void YouTubeCatalog::handleResult(quint64 requestId, const QJsonValue &result) {
  const auto request = m_requests.constFind(requestId);
  if (request == m_requests.cend())
    return;

  const RequestKind kind = request.value();
  m_requests.remove(requestId);

  if (kind == RequestKind::Playlists) {
    if (result.isArray()) {
      emit playlistsReady(requestId, result.toArray());
      return;
    }
  } else if (result.isObject()) {
    const QJsonObject object = result.toObject();
    switch (kind) {
    case RequestKind::Home:
      emit homeReady(requestId, object);
      return;
    case RequestKind::Library:
      emit libraryReady(requestId, object);
      return;
    case RequestKind::Search:
      emit searchReady(requestId, object);
      return;
    case RequestKind::Artist:
      emit artistReady(requestId, object);
      return;
    case RequestKind::Album:
      emit albumReady(requestId, object);
      return;
    case RequestKind::Playlist:
      emit playlistReady(requestId, object);
      return;
    case RequestKind::PlaylistPage:
      emit playlistPageReady(requestId, object);
      return;
    case RequestKind::Playlists:
      break;
    }
  }

  // A shape mismatch is a provider bug, not an empty feed. Call it what it is
  // so the eventual UI doesn't cheerfully cache corrupted state.
  emit requestFailed(
      requestId,
      QStringLiteral("YouTube returned an unexpected catalog response."));
}

void YouTubeCatalog::handleFailure(quint64 requestId, const QString &message) {
  if (!m_requests.remove(requestId))
    return;
  emit requestFailed(requestId, message);
}
