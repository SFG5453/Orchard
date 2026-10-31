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

#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QTimer>

class AuthManager;
class AuthSessionServer;
class QProcess;
class YouTubeCatalog;

// Keeps the saved YouTube session from going stale. On Windows the app holds a
// frozen copy of the cookies taken at sign-in; Google eventually stops taking
// it while the sign-in browser profile stays signed in. This asks the auth
// helper to open YouTube Music hidden in that profile and absorbs the fresh
// cookies when they belong to the same account. It runs when YouTube rejects
// the session and, as prevention, every few hours.
class YouTubeSessionKeeper final : public QObject {
  Q_OBJECT

public:
  YouTubeSessionKeeper(AuthManager *auth, YouTubeCatalog *catalog, QObject *parent = nullptr);

  void renew(const QString &reason);

private:
  void finish(int exitCode);

  QPointer<AuthManager> m_auth;
  QProcess *m_helper{nullptr};
  AuthSessionServer *m_server{nullptr};
  QJsonObject m_result;
  QTimer m_periodic;
  qint64 m_lastAttemptMs{0};
  QString m_reason;
};
