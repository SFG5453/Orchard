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

#include "instance_coordinator.h"

#include <QCryptographicHash>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLocalSocket>
#include <QStandardPaths>
#include <QThread>
#include <QTimer>
#include <memory>
#include <utility>

namespace {
constexpr qsizetype maxRequestBytes = 64 * 1024;
QString dataDirectory() {
  return QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
}

QString serverName() {
  // The data path scopes the socket to this user, even on shared Windows hosts.
  const auto hash = QCryptographicHash::hash(dataDirectory().toUtf8(), QCryptographicHash::Sha256).toHex();
  return QStringLiteral("orchard-window-%1").arg(QString::fromLatin1(hash.left(16)));
}
}

InstanceCoordinator::InstanceCoordinator()
    : m_serverName(serverName()), m_lock(QDir(dataDirectory()).filePath(QStringLiteral("instance.lock"))) {
  m_server.setSocketOptions(QLocalServer::UserAccessOption);
  connect(&m_server, &QLocalServer::newConnection, this, [this] {
    while (auto *socket = m_server.nextPendingConnection()) {
      socket->setReadBufferSize(maxRequestBytes + 1);
      auto buffer = std::make_shared<QByteArray>();
      auto received = std::make_shared<bool>(false);
      const auto read = [this, socket, buffer, received] {
        if (*received)
          return;
        buffer->append(socket->readAll());
        if (buffer->size() > maxRequestBytes) {
          *received = true;
          socket->abort();
          return;
        }
        if (!buffer->contains('\n'))
          return;
        *received = true;
        const auto document = QJsonDocument::fromJson(buffer->trimmed());
        if (document.isArray()) {
          QStringList arguments;
          for (const auto &value : document.array()) {
            if (!value.isString())
              return;
            arguments.append(value.toString());
          }
          receiveArguments(arguments);
          activate();
        }
        socket->disconnectFromServer();
      };
      connect(socket, &QLocalSocket::readyRead, socket, read);
      connect(socket, &QLocalSocket::disconnected, socket, [socket, read] {
        read();
        socket->deleteLater();
      });
      QTimer::singleShot(2000, socket, [socket] { socket->abort(); socket->deleteLater(); });
      read();
    }
  });
}

InstanceCoordinator::StartResult InstanceCoordinator::start(const QStringList &arguments) {
  if (!QDir().mkpath(dataDirectory())) {
    qWarning("Could not create Orchard's instance directory");
    return StartResult::Failed;
  }

  if (!m_lock.tryLock(0)) {
    // The first launch may still be opening its socket. Give it a moment to
    // answer before declaring that the window has gone on a coffee break.
    for (int attempt = 0; attempt < 20; ++attempt) {
      if (requestActivation(arguments))
        return StartResult::Forwarded;
      if (m_lock.tryLock(0))
        break;
      QThread::msleep(100);
    }
    if (!m_lock.isLocked()) {
      qWarning("Orchard is already running, but its window could not be reached");
      return StartResult::Failed;
    }
  }

  QLocalServer::removeServer(m_serverName); // Only the lock owner may clear yesterday's socket.
  if (!m_server.listen(m_serverName)) {
    qWarning("Could not listen for Orchard window requests: %s", qPrintable(m_server.errorString()));
    return StartResult::Failed;
  }
  receiveArguments(arguments);
  if (!m_pendingLinks.isEmpty())
    activate();
  return StartResult::Primary;
}

bool InstanceCoordinator::requestActivation(const QStringList &arguments) const {
  const QByteArray request = QJsonDocument(QJsonArray::fromStringList(arguments)).toJson(QJsonDocument::Compact) + '\n';
  if (request.size() > maxRequestBytes)
    return false;
  QLocalSocket socket;
  socket.connectToServer(m_serverName);
  if (!socket.waitForConnected(100))
    return false;
  if (socket.write(request) != request.size())
    return false;
  return socket.waitForBytesWritten(1000);
}

void InstanceCoordinator::receiveArguments(const QStringList &arguments) {
  for (const auto &argument : arguments) {
    if (!argument.contains(QLatin1String("://")))
      continue;
    if (m_linkHandler)
      m_linkHandler(argument);
    else
      m_pendingLinks.append(argument);
  }
}

void InstanceCoordinator::setLinkHandler(std::function<void(const QString &)> handler) {
  m_linkHandler = std::move(handler);
  if (!m_linkHandler)
    return;
  const auto pending = std::exchange(m_pendingLinks, {});
  for (const auto &uri : pending)
    m_linkHandler(uri);
}

void InstanceCoordinator::setActivationHandler(std::function<void()> handler) {
  m_activationHandler = std::move(handler);
  if (m_activationPending)
    activate();
}

void InstanceCoordinator::activate() {
  if (!m_activationHandler) {
    m_activationPending = true;
    return;
  }
  m_activationPending = false;
  m_activationHandler();
}
