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

#include "connect_hub_socket.h"

#include "account/orchard_account.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkRequest>
#include <QUrl>
#include <QWebSocketHandshakeOptions>

#include <algorithm>

namespace {

// The account client reuses a token until shortly before it expires, so poll
// often and forward only new tokens; the hub closes sockets past expiry.
constexpr int kRefreshMs = 30 * 1000;
constexpr int kMaxRetryMs = 60 * 1000;

QUrl hubUrl(QUrl service) {
  service.setScheme(service.scheme() == QStringLiteral("http") ? QStringLiteral("ws") : QStringLiteral("wss"));
  service.setPath(QStringLiteral("/connect/hub"));
  service.setQuery(QString());
  return service;
}

} // namespace

ConnectHubSocket::ConnectHubSocket(OrchardAccount *account, QObject *parent)
    : QObject(parent), m_account(account) {
  m_retry.setSingleShot(true);
  connect(&m_retry, &QTimer::timeout, this, &ConnectHubSocket::open);
  m_refresh.setInterval(kRefreshMs);
  connect(&m_refresh, &QTimer::timeout, this, &ConnectHubSocket::refreshToken);

  connect(&m_socket, &QWebSocket::connected, this, [this] {
    m_open = true;
    m_attempt = 0;
    m_refresh.start();
    emit opened();
  });
  connect(&m_socket, &QWebSocket::textMessageReceived, this, &ConnectHubSocket::message);
  connect(&m_socket, &QWebSocket::disconnected, this, [this] {
    const bool wasOpen = m_open;
    m_open = false;
    m_refresh.stop();
    if (wasOpen)
      emit closed();
    scheduleRetry();
  });
}

void ConnectHubSocket::start() {
  if (m_wanted)
    return;
  m_wanted = true;
  m_attempt = 0;
  open();
}

void ConnectHubSocket::stop() {
  m_wanted = false;
  ++m_generation;
  m_retry.stop();
  m_refresh.stop();
  m_socket.close();
}

void ConnectHubSocket::send(const QString &text) {
  if (m_open)
    m_socket.sendTextMessage(text);
}

void ConnectHubSocket::open() {
  if (!m_wanted || !m_account || m_open || m_socket.state() != QAbstractSocket::UnconnectedState)
    return;
  const quint64 generation = ++m_generation;
  m_account->withAccessToken([this, generation](const QString &token) {
    if (generation != m_generation || !m_wanted)
      return;
    if (token.isEmpty()) {
      scheduleRetry();
      return;
    }
    m_token = token;
    // Clients cannot set headers on a WebSocket; the token rides as a subprotocol.
    QWebSocketHandshakeOptions options;
    options.setSubprotocols({QStringLiteral("orchard-connect.2"), QStringLiteral("bearer.") + token});
    m_socket.open(QNetworkRequest(hubUrl(m_account->serviceUrl())), options);
  });
}

void ConnectHubSocket::scheduleRetry() {
  if (!m_wanted || m_retry.isActive())
    return;
  const int delay = std::min(kMaxRetryMs, 2000 << std::min(m_attempt, 5));
  ++m_attempt;
  m_retry.start(delay);
}

void ConnectHubSocket::refreshToken() {
  if (!m_account || !m_open)
    return;
  const quint64 generation = m_generation;
  m_account->withAccessToken([this, generation](const QString &token) {
    if (generation != m_generation || token.isEmpty() || token == m_token)
      return;
    m_token = token;
    send(QString::fromUtf8(QJsonDocument(QJsonObject{{QStringLiteral("type"), QStringLiteral("refresh")},
                                                     {QStringLiteral("token"), token}})
                               .toJson(QJsonDocument::Compact)));
  });
}
