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

#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QNetworkAccessManager>
#include <QObject>
#include <functional>

class QProcess;
class AuthSessionServer;

// Spotify Canvas loops as an animated-artwork mirror. Spotify only shows them
// to signed-in listeners, so this keeps the user's sp_dc cookie in the keychain.
class SpotifyCanvas final : public QObject {
  Q_OBJECT
  // restoring, disconnected, connecting, connected
  Q_PROPERTY(QString status READ status NOTIFY changed)
  Q_PROPERTY(bool connected READ connected NOTIFY changed)
  // The login window needs Qt WebEngine; pasting the cookie works everywhere.
  Q_PROPERTY(bool loginAvailable READ loginAvailable CONSTANT)
  Q_PROPERTY(QString message READ message NOTIFY changed)
  Q_PROPERTY(bool messageIsError READ messageIsError NOTIFY changed)

public:
  // `url` is the canvas loop or empty. `failed` marks an empty answer caused by
  // an error; a song without a canvas is a plain miss.
  using CanvasReply = std::function<void(const QString &url, bool failed)>;

  explicit SpotifyCanvas(QObject *parent = nullptr, bool restore = true);
  ~SpotifyCanvas() override;

  QString status() const { return m_status; }
  bool connected() const { return m_status == QStringLiteral("connected"); }
  bool loginAvailable() const;
  QString message() const { return m_message; }
  bool messageIsError() const { return m_messageIsError; }

  Q_INVOKABLE void connectAccount();
  Q_INVOKABLE void cancelConnection();
  // Accepts a bare sp_dc value or a whole Cookie header.
  Q_INVOKABLE bool saveCookie(const QString &input);
  Q_INVOKABLE void disconnectAccount();

  void canvasFor(const QString &title, const QString &artist, CanvasReply done);

  static QString extractSpdc(const QString &input);
  // Pulls the first canvas loop URL out of a canvaz-cache protobuf reply.
  static QString canvasUrlFromProtobuf(const QByteArray &body);
  // canvaz-cache request body: one entity with the track URI.
  static QByteArray canvasRequest(const QString &trackId);
  // Picks the search hit whose title and artist match; empty when none does.
  static QString matchingTrackId(const QJsonObject &pathfinder, const QString &title,
                                 const QString &artist);

signals:
  void changed();
  // Connecting makes old "no motion art" answers worth asking again.
  void accountConnected();

private:
  using TokenReply = std::function<void(const QString &token)>;

  void setStatus(const QString &status);
  void setMessage(const QString &message, bool error = false);
  void restoreSession();
  void applyCookie(const QString &spdc, bool persist);
  void runHelper(const QString &mode, const QByteArray &input,
                 std::function<void(const QJsonObject &)> done);
  void withAccessToken(TokenReply done);
  // spotify_canvas_api.cpp
  void withClientToken(TokenReply done);
  void searchTrack(const QString &accessToken, const QString &clientToken, const QString &title,
                   const QString &artist, std::function<void(const QString &id, bool failed)> done);
  void fetchCanvas(const QString &trackId, const QString &accessToken, const QString &clientToken,
                   CanvasReply done);
  void tokenRejected();

  QNetworkAccessManager m_network;
  QProcess *m_helper{nullptr};
  AuthSessionServer *m_helperServer{nullptr};
  QJsonObject m_helperResult;
  std::function<void(const QJsonObject &)> m_helperDone;
  QString m_spdc;
  QString m_accessToken;
  qint64 m_accessTokenExpiresMs{0};
  // A failed harvest launches a browser engine; don't retry it every song.
  qint64 m_tokenRetryAfterMs{0};
  QList<TokenReply> m_tokenWaiters;
  QString m_clientToken;
  qint64 m_clientTokenExpiresMs{0};
  // Spotify throttles lookups, so every answer is kept, misses included.
  QHash<QString, QString> m_canvases;
  QString m_status{QStringLiteral("disconnected")};
  QString m_message;
  bool m_messageIsError{false};
};
