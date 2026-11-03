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
#include <QObject>
#include <QTcpServer>
#include <QTimer>
#include <QWebSocketServer>
#include <functional>

class AppleMusic;
class AuthManager;
class CatalogSource;
class ConnectService;
class HomeController;
class LibraryActions;
class OfflineLibrary;
class PlaybackController;
class QTcpSocket;
class QWebSocket;
class YouTubeProvider;

class LocalApi final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY changed)
  // HTTP port; the WebSocket listens on port + 1.
  Q_PROPERTY(int port READ port NOTIFY changed)
  Q_PROPERTY(bool running READ running NOTIFY changed)
  Q_PROPERTY(QString error READ error NOTIFY changed)

public:
  using Reply = std::function<void(int, const QJsonObject &)>;
  static constexpr int kDefaultPort = 32145;
  LocalApi(PlaybackController *playback, HomeController *home, YouTubeProvider *provider,
           AuthManager *auth, AppleMusic *apple, CatalogSource *source,
           OfflineLibrary *offline, LibraryActions *library, ConnectService *connect,
           QObject *parent = nullptr);
  ~LocalApi() override;
  // Listens when enabled; a bind failure leaves the API off and sets error.
  bool start();
  void stop();
  [[nodiscard]] bool enabled() const { return m_enabled; }
  void setEnabled(bool enabled);
  [[nodiscard]] int port() const { return m_port; }
  // Saves the port and rebinds when running; false when outside 1024..65534.
  Q_INVOKABLE bool setPort(int port);
  [[nodiscard]] bool running() const { return m_http.isListening(); }
  [[nodiscard]] QString error() const { return m_error; }

signals:
  void changed();

private:
  bool fail(const QString &message);
  void acceptHttp();
  void readHttp(QTcpSocket *socket);
  void acceptWebSocket();
  void dispatch(const QString &method, const QJsonObject &params, Reply reply);
  bool control(const QString &method, const QJsonObject &params, const Reply &reply);
  bool catalog(const QString &method, const QJsonObject &params, const Reply &reply);
  void providerCall(const QString &method, QJsonObject params, Reply reply);
  void songRequest(const QJsonObject &params, Reply reply);
  QString trackUnavailable(const QJsonObject &track) const;
  void publish();
  QJsonObject state() const;
  QJsonObject mixState() const;
  QJsonObject description() const;
  double volume() const;
  void setVolume(double volume);
  static QJsonObject success(const QJsonValue &result);
  static QJsonObject failure(const QString &code, const QString &message);
  static void respond(QTcpSocket *socket, int status, const QJsonObject &body);

  PlaybackController *m_playback;
  HomeController *m_home;
  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  AppleMusic *m_apple;
  CatalogSource *m_source;
  OfflineLibrary *m_offline;
  LibraryActions *m_library;
  ConnectService *m_connect;
  QTcpServer m_http;
  QWebSocketServer m_webSocket;
  QTimer m_publish;
  QHash<QTcpSocket *, QByteArray> m_httpClients;
  QHash<QWebSocket *, bool> m_wsClients;
  QHash<quint64, Reply> m_pending;
  QByteArray m_lastState;
  QByteArray m_lastQueue;
  QString m_token;
  QString m_discoveryFile;
  double m_unmutedVolume{1.0};
  QString m_error;
  int m_port{kDefaultPort};
  bool m_enabled{true};
};
