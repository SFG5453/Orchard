/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "instance_coordinator.h"

#include <QCryptographicHash>
#include <QDir>
#include <QLocalSocket>
#include <QStandardPaths>
#include <QThread>
#include <utility>

namespace {
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
      // Connecting is the whole request. No command parser for one button.
      socket->deleteLater();
      activate();
    }
  });
}

InstanceCoordinator::StartResult InstanceCoordinator::start() {
  if (!QDir().mkpath(dataDirectory())) {
    qWarning("Could not create Orchard's instance directory");
    return StartResult::Failed;
  }

  if (!m_lock.tryLock(0)) {
    // The first launch may still be opening its socket. Give it a moment to
    // answer before declaring that the window has gone on a coffee break.
    for (int attempt = 0; attempt < 20; ++attempt) {
      if (requestActivation())
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
  return StartResult::Primary;
}

bool InstanceCoordinator::requestActivation() const {
  QLocalSocket socket;
  socket.connectToServer(m_serverName);
  return socket.waitForConnected(100);
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
