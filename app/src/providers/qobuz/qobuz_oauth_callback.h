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
#include <QObject>
#include <QTcpServer>
#include <QTimer>
#include <QUrl>

// One-shot loopback redirect target for Qobuz sign-in in the system browser.
class QobuzOAuthCallback final : public QObject {
  Q_OBJECT
public:
  explicit QobuzOAuthCallback(QObject *parent = nullptr);
  // Returns the redirect URL, or an empty URL when no port could be opened.
  QUrl listen();
  void close();
  bool listening() const { return m_server.isListening(); }

  // Pulls Qobuz's authorization code out of a raw HTTP request head.
  static QString codeFromRequest(const QByteArray &request, const QByteArray &path);

signals:
  void codeReceived(const QString &code);
  void timedOut();

private:
  QTcpServer m_server;
  QTimer m_timeout;
  QByteArray m_path;
};
