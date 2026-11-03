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
#include "local_api.h"

#include <QJsonDocument>
#include <QPointer>
#include <QTcpSocket>
#include <QUrlQuery>
#include <QWebSocket>
#include <memory>

namespace {
constexpr qsizetype maxBody = 1024 * 1024;
constexpr qsizetype maxHeaders = 16384;
constexpr int maxClients = 32;
QByteArray json(const QJsonObject &object) { return QJsonDocument(object).toJson(QJsonDocument::Compact); }
}

void LocalApi::respond(QTcpSocket *socket, int status, const QJsonObject &body) {
  if (!socket || socket->state() != QAbstractSocket::ConnectedState) return;
  const QByteArray data = json(body);
  const QByteArray reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found" : "Error";
  socket->write("HTTP/1.1 " + QByteArray::number(status) + ' ' + reason +
      "\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " +
      QByteArray::number(data.size()) + "\r\n\r\n" + data);
  socket->disconnectFromHost();
}

void LocalApi::acceptHttp() {
  while (auto *socket = m_http.nextPendingConnection()) {
    if (m_httpClients.size() >= maxClients) {
      respond(socket, 503, failure("busy", "Too many connections"));
      connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
      continue;
    }
    socket->setReadBufferSize(maxBody + maxHeaders + 1);
    m_httpClients.insert(socket, {});
    connect(socket, &QTcpSocket::readyRead, this, [this, socket] { readHttp(socket); });
    connect(socket, &QTcpSocket::disconnected, this, [this, socket] {
      m_httpClients.remove(socket);
      socket->deleteLater();
    });
    QTimer::singleShot(35000, socket, [socket] {
      respond(socket, 408, failure("timeout", "Request timed out"));
    });
  }
}

void LocalApi::readHttp(QTcpSocket *socket) {
  auto it = m_httpClients.find(socket);
  if (it == m_httpClients.end() || socket->property("apiHandled").toBool()) return;
  QByteArray &buffer = it.value();
  buffer += socket->readAll();
  const qsizetype end = buffer.indexOf("\r\n\r\n");
  if ((end < 0 && buffer.size() > maxHeaders) || end > maxHeaders || buffer.size() > maxBody + maxHeaders) {
    socket->setProperty("apiHandled", true);
    respond(socket, 413, failure("too_large", "Request is too large"));
    return;
  }
  if (end < 0) return;
  const auto lines = buffer.left(end).split('\n');
  const auto request = lines.first().trimmed().split(' ');
  const auto reject = [socket](int status, const QString &code, const QString &message) {
    socket->setProperty("apiHandled", true);
    respond(socket, status, failure(code, message));
  };
  if (request.size() != 3 || request[2] != "HTTP/1.1") {
    reject(400, "invalid_request", "Expected an HTTP/1.1 request"); return;
  }
  QHash<QByteArray, QByteArray> headers;
  for (qsizetype i = 1; i < lines.size(); ++i) {
    const auto line = lines[i].trimmed();
    const auto colon = line.indexOf(':');
    const auto key = line.left(colon).toLower();
    if (colon < 1 || headers.contains(key)) {
      reject(400, "invalid_request", "Invalid or repeated header"); return;
    }
    headers.insert(key, line.mid(colon + 1).trimmed());
  }
  if (headers.contains("origin")) {
    reject(403, "origin_denied", "Use the API from a local application or script"); return;
  }
  if (headers.value("authorization") != "Bearer " + m_token.toUtf8()) {
    reject(401, "unauthorized", "A valid Bearer token is required"); return;
  }
  bool valid = true;
  const qint64 length = headers.contains("content-length") ? headers.value("content-length").toLongLong(&valid) : 0;
  if (!valid || length < 0 || length > maxBody || headers.contains("transfer-encoding")) {
    reject(400, "invalid_request", "Invalid Content-Length or unsupported Transfer-Encoding"); return;
  }
  if (buffer.size() < end + 4 + length) return;
  socket->setProperty("apiHandled", true);
  const QUrl url = QUrl::fromEncoded(request[1]);
  if (url.isRelative() == false || !url.path().startsWith("/api/v1")) {
    respond(socket, 404, failure("not_found", "Use /api/v1")); return;
  }
  const QString path = url.path().mid(7);
  QJsonObject params;
  QString method;
  if (request[0] == "GET") {
    const QHash<QString, QString> routes{{"", "api"}, {"/", "api"}, {"/state", "state"}, {"/queue", "queue"},
        {"/history", "history"}, {"/mix", "mix.state"}, {"/mixes", "mixes"}, {"/search", "search"},
        {"/home", "home"}, {"/playlist", "playlist"}, {"/album", "album"}, {"/artist", "artist"},
        {"/library", "library"}, {"/playlists", "playlists"}, {"/connect", "connect"}};
    method = routes.value(path);
    for (const auto &entry : QUrlQuery(url).queryItems()) params.insert(entry.first, entry.second);
  } else if (request[0] == "POST") {
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(buffer.mid(end + 4, length), &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) {
      respond(socket, 400, failure("invalid_json", "Expected a JSON object")); return;
    }
    params = document.object();
    if (path == "/rpc") {
      method = params.value("method").toString();
      if (params.contains("params") && !params.value("params").isObject()) {
        respond(socket, 400, failure("invalid_params", "params must be an object")); return;
      }
      params = params.value("params").toObject();
    } else if (path.startsWith('/')) method = path.mid(1).replace('/', '.');
  }
  if (method.isEmpty()) {
    respond(socket, 404, failure("not_found", "Unknown route or HTTP method")); return;
  }
  const QPointer<QTcpSocket> guard(socket);
  dispatch(method, params, [guard](int status, const QJsonObject &body) { if (guard) respond(guard, status, body); });
}

void LocalApi::acceptWebSocket() {
  while (auto *socket = m_webSocket.nextPendingConnection()) {
    if (m_wsClients.size() >= maxClients) {
      socket->close(QWebSocketProtocol::CloseCodePolicyViolated, "Too many connections");
      socket->deleteLater(); continue;
    }
    socket->setMaxAllowedIncomingMessageSize(maxBody);
    socket->setMaxAllowedIncomingFrameSize(maxBody);
    m_wsClients.insert(socket, false);
    connect(socket, &QWebSocket::disconnected, this, [this, socket] {
      m_wsClients.remove(socket); socket->deleteLater();
    });
    QTimer::singleShot(5000, socket, [this, socket] {
      if (!m_wsClients.value(socket)) socket->close(QWebSocketProtocol::CloseCodePolicyViolated, "Authentication required");
    });
    connect(socket, &QWebSocket::textMessageReceived, this, [this, socket](const QString &text) {
      QJsonParseError error;
      const auto document = QJsonDocument::fromJson(text.toUtf8(), &error);
      if (error.error != QJsonParseError::NoError || !document.isObject()) {
        socket->sendTextMessage(QString::fromUtf8(json(failure("invalid_json", "Expected a JSON object")))); return;
      }
      const QJsonObject message = document.object();
      if (!m_wsClients.value(socket)) {
        if (message.value("method") != "authenticate" || message.value("token").toString() != m_token) {
          socket->close(QWebSocketProtocol::CloseCodePolicyViolated, "Invalid token"); return;
        }
        m_wsClients[socket] = true;
        socket->sendTextMessage(QString::fromUtf8(json({{"event", "ready"}, {"version", 1}, {"data", state()}})));
        return;
      }
      const QPointer<QWebSocket> guard(socket);
      if (message.contains("params") && !message.value("params").isObject()) {
        socket->sendTextMessage(QString::fromUtf8(json(failure("invalid_params", "params must be an object")))); return;
      }
      const auto inFlight = socket->property("apiInFlight").toInt();
      if (inFlight >= 16) {
        socket->close(QWebSocketProtocol::CloseCodePolicyViolated, "Too many pending requests"); return;
      }
      socket->setProperty("apiInFlight", inFlight + 1);
      dispatch(message.value("method").toString(), message.value("params").toObject(),
          [guard, id = message.value("id")](int status, QJsonObject body) {
            if (!guard) return;
            guard->setProperty("apiInFlight", guard->property("apiInFlight").toInt() - 1);
            body.insert("id", id); body.insert("status", status);
            guard->sendTextMessage(QString::fromUtf8(json(body)));
          });
    });
  }
}
