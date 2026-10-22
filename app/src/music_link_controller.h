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

#include "music_link.h"
#include <QJsonValue>
#include <QObject>
#include <QVariant>

class AuthManager;
class YouTubeProvider;

class MusicLinkController final : public QObject {
  Q_OBJECT
public:
  MusicLinkController(YouTubeProvider *provider, AuthManager *auth, QObject *parent = nullptr);
  void open(const QString &uri);

signals:
  void songRequested(const QVariantMap &track);
  void collectionRequested(const QVariantList &tracks, int startIndex, const QString &playlistId,
                           const QString &continuation);
  void noticeRequested(const QString &message);

private:
  void resolve();
  void receiveResult(quint64 requestId, const QJsonValue &result);
  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  std::optional<MusicLink> m_pending;
  quint64 m_request = 0;
};
