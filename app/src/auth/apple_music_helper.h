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

#include <QObject>
#include <QString>

#include <functional>

// Apple Music modes of orchard-auth-helper. Login and logout return the process exit
// code and send their result to the parent over the private auth socket.

// Reads {developerToken} from stdin, runs MusicKit JS sign-in and returns {token}
// (the Music User Token) or {error}. Apple's cookies stay in the AppleMusic profile.
int runAppleMusicLogin(const QString &socketName);

// Clears the AppleMusic profile's cookies and MusicKit storage, then returns {ok}.
int runAppleMusicLogout(const QString &socketName);

// Receives the Apple Music sign-in page's messages.
class AppleMusicPageBridge final : public QObject {
  Q_OBJECT

public:
  explicit AppleMusicPageBridge(std::function<void(const QString &)> receive, QObject *parent = nullptr)
      : QObject(parent), m_receive(std::move(receive)) {}

  Q_INVOKABLE void receive(const QString &message) { m_receive(message); }

private:
  std::function<void(const QString &)> m_receive;
};
