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

#include "auth/auth_manager.h"
#include "connect/connect_service.h"
#include "home/home_controller.h"
#include "integrations/catalog_source.h"
#include "library/library_actions.h"
#include "playback/playback_controller.h"
#include "providers/youtube/youtube_provider.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRandomGenerator>
#include <QSaveFile>
#include <QSettings>
#include <QStandardPaths>
#include <QTcpSocket>
#include <QWebSocket>
#include <algorithm>

LocalApi::LocalApi(PlaybackController *playback, HomeController *home, YouTubeProvider *provider,
                   AuthManager *auth, AppleMusic *apple, CatalogSource *source,
                   OfflineLibrary *offline, LibraryActions *library, ConnectService *connectService,
                   QObject *parent)
    : QObject(parent), m_playback(playback), m_home(home), m_provider(provider), m_auth(auth),
      m_apple(apple), m_source(source), m_offline(offline), m_library(library), m_connect(connectService),
      m_webSocket(QStringLiteral("Orchard local API"), QWebSocketServer::NonSecureMode) {
  connect(&m_http, &QTcpServer::newConnection, this, &LocalApi::acceptHttp);
  connect(&m_webSocket, &QWebSocketServer::newConnection, this, &LocalApi::acceptWebSocket);
  connect(m_provider, &YouTubeProvider::resultReady, this, [this](quint64 id, const QJsonValue &value) {
    if (const auto reply = m_pending.take(id)) reply(200, success(value));
  });
  connect(m_provider, &YouTubeProvider::requestFailed, this, [this](quint64 id, const QString &message) {
    if (const auto reply = m_pending.take(id)) reply(502, failure("provider_error", message));
  });
  m_publish.setInterval(250);
  connect(&m_publish, &QTimer::timeout, this, &LocalApi::publish);
  const QSettings settings;
  m_enabled = settings.value(QStringLiteral("api/enabled"), true).toBool();
  const int saved = settings.value(QStringLiteral("api/port"), kDefaultPort).toInt();
  m_port = saved >= 1024 && saved <= 65534 ? saved : kDefaultPort;
  bool validPort = false;
  const int override = qEnvironmentVariableIntValue("ORCHARD_API_PORT", &validPort);
  if (validPort) m_port = override;
}

LocalApi::~LocalApi() {
  m_http.close();
  m_webSocket.close();
  for (auto *socket : m_httpClients.keys()) delete socket;
  for (auto *socket : m_wsClients.keys()) delete socket;
}

void LocalApi::setEnabled(bool enabled) {
  if (enabled == m_enabled) return;
  m_enabled = enabled;
  QSettings().setValue(QStringLiteral("api/enabled"), enabled);
  if (enabled) start();
  else stop();
  emit changed();
}

bool LocalApi::setPort(int port) {
  if (port < 1024 || port > 65534) return false;
  if (port == m_port) return true;
  m_port = port;
  QSettings().setValue(QStringLiteral("api/port"), port);
  if (m_enabled) { stop(); start(); }
  emit changed();
  return true;
}

void LocalApi::stop() {
  m_publish.stop();
  m_http.close();
  m_webSocket.close();
  // Disconnect handlers remove each client and schedule its deletion.
  for (auto *socket : m_httpClients.keys()) socket->abort();
  for (auto *socket : m_wsClients.keys()) socket->abort();
  if (!m_discoveryFile.isEmpty()) QFile::remove(m_discoveryFile);
  m_lastState.clear();
  m_lastQueue.clear();
  m_error.clear();
  emit changed();
}

bool LocalApi::fail(const QString &message) {
  m_http.close();
  m_webSocket.close();
  m_error = message;
  qWarning("Orchard API: %s", qPrintable(message));
  emit changed();
  return false;
}

bool LocalApi::start() {
  if (!m_enabled || running()) return running();
  if (qEnvironmentVariableIsSet("ORCHARD_API_DISABLED")) return fail(tr("Disabled by ORCHARD_API_DISABLED."));
  const QString directory = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation);
  m_discoveryFile = QDir(directory).filePath(QStringLiteral("api.json"));
  if (!QDir().mkpath(directory)) return fail(tr("Could not create %1.").arg(directory));
  QFile existing(m_discoveryFile);
  if (existing.open(QIODevice::ReadOnly))
    m_token = QJsonDocument::fromJson(existing.readAll()).object().value("token").toString();
  if (m_token.size() != 64) {
    QByteArray random;
    for (int i = 0; i < 8; ++i) random += QByteArray::number(QRandomGenerator::system()->generate(), 16).rightJustified(8, '0');
    m_token = QString::fromLatin1(random);
  }
  const int port = m_port;
  if (port < 1024 || port > 65534 || !m_http.listen(QHostAddress::LocalHost, port) ||
      !m_webSocket.listen(QHostAddress::LocalHost, port + 1))
    return fail(tr("Ports %1 and %2 are unavailable. Choose another port.").arg(port).arg(port + 1));
  const QJsonObject discovery{{"version", 1}, {"http", QString("http://127.0.0.1:%1/api/v1").arg(port)},
                              {"websocket", QString("ws://127.0.0.1:%1").arg(port + 1)}, {"token", m_token}};
  QSaveFile file(m_discoveryFile);
  if (!file.open(QIODevice::WriteOnly) ||
      !file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner) ||
      file.write(QJsonDocument(discovery).toJson()) == -1 || !file.commit())
    return fail(tr("Could not save %1.").arg(m_discoveryFile));
  m_error.clear();
  m_publish.start();
  emit changed();
  qInfo("Orchard API: http://127.0.0.1:%d/api/v1 (credentials: %s)", port, qPrintable(m_discoveryFile));
  return true;
}

QJsonObject LocalApi::success(const QJsonValue &result) { return {{"ok", true}, {"result", result}}; }
QJsonObject LocalApi::failure(const QString &code, const QString &message) {
  return {{"ok", false}, {"error", QJsonObject{{"code", code}, {"message", message}}}};
}

double LocalApi::volume() const { return m_connect->controlling() ? m_connect->remoteVolume() : m_home->volume(); }
void LocalApi::setVolume(double level) {
  level = std::clamp(level, 0.0, 1.0);
  if (m_connect->controlling()) m_connect->setRemoteVolume(level);
  else m_home->setVolume(level);
}

QJsonObject LocalApi::mixState() const {
  const auto *adaptive = m_playback->adaptiveMix();
  const auto *best = m_playback->bestMix();
  return {{"scope", "desktop"}, {"crossfadeEnabled", m_playback->crossfadeEnabled()}, {"crossfadeDuration", m_playback->crossfadeDuration()},
          {"gaplessEnabled", m_playback->gaplessEnabled()},
          {"active", m_playback->crossfadeActive()}, {"progress", m_playback->crossfadeProgress()},
          {"start", m_playback->mixStart()}, {"duration", m_playback->mixDuration()},
          {"incomingTrack", QJsonObject::fromVariantMap(PlaybackController::sanitizeTrack(m_playback->transitionTrack()))},
          {"mode", adaptive->mode()}, {"available", adaptive->available()}, {"status", adaptive->status()},
          {"ready", adaptive->ready()}, {"style", adaptive->mixStyle()}, {"bpm", adaptive->mixBpm()},
          {"bestMix", QJsonObject{{"busy", best->busy()}, {"sorted", m_playback->bestMixSorted()},
              {"downloading", best->downloading()}, {"downloaded", best->downloaded()},
              {"completed", best->completed()}, {"total", best->total()}, {"error", best->error()},
              {"tempos", QJsonObject::fromVariantMap(best->tempos())}}}};
}

QJsonObject LocalApi::state() const {
  return {{"track", QJsonObject::fromVariantMap(PlaybackController::sanitizeTrack(m_playback->shownTrack()))},
          {"playing", m_playback->shownPlaying()}, {"loading", m_playback->shownLoading()},
          {"position", m_playback->shownPosition()}, {"duration", m_playback->shownDuration()},
          {"volume", volume()}, {"shuffle", m_playback->shownShuffle()}, {"repeat", m_playback->shownRepeatMode()},
          {"canGoNext", m_playback->shownCanGoNext()}, {"canGoPrevious", m_playback->shownCanGoPrevious()},
          {"queueLength", m_playback->shownQueue().size()}, {"source", m_source->source()},
          {"autoplay", m_playback->autoplayEnabled()},
          {"remote", m_playback->remoteActive()}, {"error", m_playback->errorMessage()},
          {"liked", m_library->currentLiked()}, {"likeKnown", m_library->likeKnown()},
          {"likeBusy", m_library->likeBusy()}, {"mix", mixState()}};
}

void LocalApi::publish() {
  bool subscribed = false;
  for (bool authenticated : std::as_const(m_wsClients)) subscribed |= authenticated;
  if (!subscribed) return;
  const auto broadcast = [this](const QString &event, const QJsonValue &data) {
    const QString message = QString::fromUtf8(QJsonDocument(QJsonObject{{"event", event}, {"data", data}}).toJson(QJsonDocument::Compact));
    for (auto it = m_wsClients.cbegin(); it != m_wsClients.cend(); ++it) {
      if (!it.value()) continue;
      if (it.key()->bytesToWrite() > 1024 * 1024) it.key()->close(QWebSocketProtocol::CloseCodeGoingAway, "Slow consumer");
      else it.key()->sendTextMessage(message);
    }
  };
  const QJsonObject snapshot = state();
  const QByteArray encoded = QJsonDocument(snapshot).toJson(QJsonDocument::Compact);
  if (encoded != m_lastState) { m_lastState = encoded; broadcast("state", snapshot); }
  const QJsonArray queue = QJsonArray::fromVariantList(PlaybackController::sanitizeTrackList(m_playback->shownQueue()));
  const QByteArray encodedQueue = QJsonDocument(queue).toJson(QJsonDocument::Compact);
  if (encodedQueue != m_lastQueue) { m_lastQueue = encodedQueue; broadcast("queue", queue); }
}

QJsonObject LocalApi::description() const {
  return {{"version", 1}, {"name", "Orchard local API"},
          {"methods", QJsonArray{"state", "queue", "history", "mix.state", "play", "pause", "toggle", "stop", "next", "previous",
              "seek", "volume", "mute", "shuffle", "repeat", "queue.add", "queue.remove", "queue.move", "queue.clear",
              "queue.play", "queue.replace", "track.play", "request", "search", "home", "mixes", "playlist", "playlist.more",
              "playlist.play", "album", "artist", "library", "playlists", "like", "mix.configure", "mix.best", "connect"}},
          {"discoveryFile", m_discoveryFile}};
}
