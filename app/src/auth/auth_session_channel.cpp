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

#include "auth_session_channel.h"

#include <QJsonDocument>
#include <QUuid>
#include <utility>

namespace {
constexpr qint64 maximumSessionBytes = 1024 * 1024;
constexpr int transferTimeoutMs = 10000;
}

AuthSessionServer::AuthSessionServer(
    std::function<bool(const QJsonObject &)> accept, QObject *parent)
    : QObject(parent), m_accept(std::move(accept)) {
  m_server.setSocketOptions(QLocalServer::UserAccessOption);
  m_server.setMaxPendingConnections(1);
  m_timeout.setSingleShot(true);
  connect(&m_timeout, &QTimer::timeout, this, &AuthSessionServer::close);
  connect(&m_server, &QLocalServer::newConnection, this, [this] {
    while (auto *socket = m_server.nextPendingConnection()) {
      if (m_socket || m_received) {
        socket->abort();
        socket->deleteLater();
        continue;
      }
      m_socket = socket;
      socket->setReadBufferSize(maximumSessionBytes + 1);
      connect(socket, &QLocalSocket::readyRead, this, &AuthSessionServer::readSession);
      m_timeout.start(transferTimeoutMs);
      readSession();
    }
  });
}

bool AuthSessionServer::start() {
  close();
  m_received = false;
  // A new address on every attempt keeps yesterday's cookie courier out.
  return m_server.listen(QStringLiteral("orchard-auth-%1")
      .arg(QUuid::createUuid().toString(QUuid::WithoutBraces)));
}

void AuthSessionServer::close() {
  m_timeout.stop();
  m_server.close();
  if (m_socket) {
    disconnect(m_socket, nullptr, this, nullptr);
    m_socket->abort();
    m_socket->deleteLater();
    m_socket = nullptr;
  }
}

void AuthSessionServer::readSession() {
  if (!m_socket || m_received) return;
  if (m_socket->bytesAvailable() > maximumSessionBytes) {
    close();
    return;
  }
  // Local sockets are streams: a large cookie jar can arrive in several reads.
  if (!m_socket->canReadLine()) return;
  m_received = true;
  m_timeout.stop();
  m_server.close();
  const QJsonDocument document = QJsonDocument::fromJson(m_socket->readLine());
  const bool accepted = document.isObject() && m_accept(document.object());
  if (!m_socket) return;
  m_socket->write(accepted ? "accepted\n" : "rejected\n");
  m_socket->disconnectFromServer();
}

AuthSessionClient::AuthSessionClient(QObject *parent) : QObject(parent) {
  m_timeout.setSingleShot(true);
  connect(&m_timeout, &QTimer::timeout, this, [this] { finish(false); });
  connect(&m_socket, &QLocalSocket::connected, this, [this] {
    if (m_socket.write(m_message) != m_message.size()) finish(false);
    m_message.clear();
  });
  connect(&m_socket, &QLocalSocket::readyRead, this, [this] {
    if (m_socket.canReadLine()) finish(m_socket.readLine() == "accepted\n");
  });
  connect(&m_socket, &QLocalSocket::errorOccurred, this,
          [this](QLocalSocket::LocalSocketError) { finish(false); });
  connect(&m_socket, &QLocalSocket::disconnected, this, [this] { finish(false); });
}

void AuthSessionClient::send(const QString &serverName, const QJsonObject &session) {
  m_message = QJsonDocument(session).toJson(QJsonDocument::Compact) + '\n';
  if (serverName.isEmpty() || m_message.size() > maximumSessionBytes) {
    finish(false);
    return;
  }
  m_timeout.start(transferTimeoutMs);
  m_socket.connectToServer(serverName);
}

void AuthSessionClient::finish(bool accepted) {
  if (m_finished) return;
  m_finished = true;
  m_timeout.stop();
  m_message.clear();
  m_socket.abort();
  emit finished(accepted);
}
