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

#include "music_link_controller.h"
#include "auth/auth_manager.h"
#include "providers/youtube/youtube_provider.h"

#include <QJsonArray>
#include <QJsonObject>
#include <QTimer>

MusicLinkController::MusicLinkController(YouTubeProvider *provider, AuthManager *auth, QObject *parent)
    : QObject(parent), m_provider(provider), m_auth(auth) {
  connect(provider, &YouTubeProvider::resultReady, this, &MusicLinkController::receiveResult);
  connect(provider, &YouTubeProvider::requestFailed, this, [this](quint64 id, const QString &message) {
    if (!m_request || id != m_request)
      return;
    m_request = 0;
    m_pending.reset();
    emit noticeRequested(tr("Could not open music link: %1").arg(message));
  });
  const auto sessionChanged = [this] {
    m_request = 0;
    // Sign-in can emit several signals while its session is being installed.
    QTimer::singleShot(0, this, &MusicLinkController::resolve);
  };
  connect(auth, &AuthManager::sessionChanged, this, sessionChanged);
  connect(auth, &AuthManager::statusChanged, this, sessionChanged);
}

void MusicLinkController::open(const QString &uri) {
  const auto link = parseMusicLink(uri);
  if (!link) {
    emit noticeRequested(tr("This music link is not supported."));
    return;
  }
  m_request = 0;
  m_pending = link;
  if (!m_auth->isSignedIn())
    emit noticeRequested(tr("Sign in to YouTube Music to open this link."));
  resolve();
}

void MusicLinkController::resolve() {
  if (!m_pending || m_request || !m_auth->isSignedIn())
    return;
  const bool song = m_pending->kind == MusicLink::Kind::Song;
  const QString method = song ? QStringLiteral("catalog.track")
      : m_pending->kind == MusicLink::Kind::Album ? QStringLiteral("catalog.album")
                                                : QStringLiteral("catalog.playlist");
  m_request = m_provider->invoke(method, QJsonObject{
      {QStringLiteral("session"), m_auth->sessionObject()},
      {song ? QStringLiteral("videoId") : QStringLiteral("browseId"), m_pending->id}});
}

void MusicLinkController::receiveResult(quint64 requestId, const QJsonValue &result) {
  if (!m_request || requestId != m_request || !m_pending)
    return;
  const auto link = *m_pending;
  m_request = 0;
  m_pending.reset();
  const QJsonObject item = result.toObject();
  if (link.kind == MusicLink::Kind::Song) {
    const QString type = item.value(QStringLiteral("type")).toString();
    if (item.value(QStringLiteral("id")).toString() != link.id ||
        (type != QLatin1String("song") && type != QLatin1String("track") && type != QLatin1String("video")) ||
        item.value(QStringLiteral("unplayable")).toBool()) {
      emit noticeRequested(tr("This song is unavailable."));
      return;
    }
    emit songRequested(item.toVariantMap());
  } else {
    const QVariantList tracks = item.value(QStringLiteral("tracks")).toArray().toVariantList();
    int startIndex = 0;
    for (; startIndex < tracks.size(); ++startIndex) {
      const auto track = tracks.at(startIndex).toMap();
      const auto type = track.value(QStringLiteral("type")).toString();
      if (!track.value(QStringLiteral("id")).toString().isEmpty() &&
          !track.value(QStringLiteral("unplayable")).toBool() &&
          (type == QLatin1String("song") || type == QLatin1String("track") || type == QLatin1String("video")))
        break;
    }
    if (startIndex == tracks.size()) {
      emit noticeRequested(tr("This collection has no playable songs."));
      return;
    }
    emit collectionRequested(tracks, startIndex, link.kind == MusicLink::Kind::Playlist ? link.id : QString(),
                             item.value(QStringLiteral("continuation")).toString());
  }
}
