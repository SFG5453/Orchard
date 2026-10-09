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
#include <QLocalServer>
#include <QLocalSocket>
#include <QPointer>
#include <QTimer>
#include <functional>

// One login attempt, one private connection, one acknowledged session.
class AuthSessionServer final : public QObject {
  Q_OBJECT
public:
  explicit AuthSessionServer(std::function<bool(const QJsonObject &)> accept,
                             QObject *parent = nullptr);
  bool start();
  void close();
  QString name() const { return m_server.serverName(); }

private:
  void readSession();
  QLocalServer m_server;
  QPointer<QLocalSocket> m_socket;
  QTimer m_timeout;
  std::function<bool(const QJsonObject &)> m_accept;
  bool m_received{false};
};

class AuthSessionClient final : public QObject {
  Q_OBJECT
public:
  explicit AuthSessionClient(QObject *parent = nullptr);
  void send(const QString &serverName, const QJsonObject &session);

signals:
  void finished(bool accepted);

private:
  void finish(bool accepted);
  QLocalSocket m_socket;
  QTimer m_timeout;
  QByteArray m_message;
  bool m_finished{false};
};
