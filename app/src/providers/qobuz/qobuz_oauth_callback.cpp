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

#include "qobuz_oauth_callback.h"

#include <QTcpSocket>
#include <QUrlQuery>

namespace {
constexpr int kLoginTimeoutMs = 5 * 60 * 1000;
constexpr qsizetype kMaximumRequestBytes = 16 * 1024;

QByteArray page(bool success) {
  const QByteArray title = success ? "Qobuz connected" : "Qobuz sign-in failed";
  const QByteArray detail = success
      ? "You can close this tab and return to Orchard."
      : "No authorization code arrived. Return to Orchard and try again.";
  return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + title +
         "</title></head><body style=\"font:16px system-ui;background:#101512;color:#f4f7f4;"
         "padding:48px\"><h1>" + title + "</h1><p>" + detail + "</p></body></html>";
}
} // namespace

QobuzOAuthCallback::QobuzOAuthCallback(QObject *parent) : QObject(parent) {
  m_timeout.setSingleShot(true);
  connect(&m_timeout, &QTimer::timeout, this, [this] {
    close();
    emit timedOut();
  });
  connect(&m_server, &QTcpServer::newConnection, this, [this] {
    while (QTcpSocket *socket = m_server.nextPendingConnection()) {
      connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
      connect(socket, &QTcpSocket::readyRead, this, [this, socket] {
        if (socket->property("answered").toBool())
          return;
        const QByteArray request = socket->peek(kMaximumRequestBytes + 1);
        if (!request.contains("\r\n\r\n") && request.size() <= kMaximumRequestBytes)
          return;
        socket->setProperty("answered", true);
        // Browsers also ask for /favicon.ico; only the redirect path counts.
        const bool expected = request.startsWith("GET " + m_path);
        const QString code = codeFromRequest(request, m_path);
        const QByteArray body = page(!code.isEmpty());
        socket->write((expected ? (code.isEmpty() ? "HTTP/1.1 400 Bad Request" : "HTTP/1.1 200 OK")
                                : "HTTP/1.1 404 Not Found") +
                      QByteArray("\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store"
                                 "\r\nConnection: close\r\nContent-Length: ") +
                      QByteArray::number(expected ? body.size() : 0) + "\r\n\r\n" +
                      (expected ? body : QByteArray()));
        socket->disconnectFromHost();
        if (code.isEmpty())
          return;
        close();
        emit codeReceived(code);
      });
    }
  });
}

QUrl QobuzOAuthCallback::listen() {
  close();
  if (!m_server.listen(QHostAddress::LocalHost, 0))
    return {};
  m_path = "/qobuz-callback";
  m_timeout.start(kLoginTimeoutMs);
  return QUrl(QStringLiteral("http://127.0.0.1:%1%2")
                  .arg(m_server.serverPort())
                  .arg(QString::fromLatin1(m_path)));
}

void QobuzOAuthCallback::close() {
  m_timeout.stop();
  m_server.close();
}

QString QobuzOAuthCallback::codeFromRequest(const QByteArray &request, const QByteArray &path) {
  const QList<QByteArray> line = request.left(request.indexOf("\r\n")).split(' ');
  if (line.size() != 3 || line.at(0) != "GET" || path.isEmpty())
    return {};
  const QUrl target(QString::fromLatin1(line.at(1)));
  if (target.path().toLatin1() != path)
    return {};
  return QUrlQuery(target).queryItemValue(QStringLiteral("code_autorisation"), QUrl::FullyDecoded);
}
