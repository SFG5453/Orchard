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

#include "connect_service.h"

#include "account/orchard_account.h"
#include "auth/auth_manager.h"
#include "connect_mix.h"
#include "connect_tracks.h"
#include "home/home_controller.h"
#include "playback/playback_controller.h"
#include "providers/qobuz/qobuz_service.h"

#include <QHostAddress>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkInterface>
#include <QtDebug>

#include <algorithm>

namespace {

std::string compact(const QJsonObject &object) {
  return QJsonDocument(object).toJson(QJsonDocument::Compact).toStdString();
}

} // namespace

ConnectService::ConnectService(OrchardAccount *account, PlaybackController *playback, HomeController *home,
                               AuthManager *auth, QobuzService *qobuz, QObject *parent)
    : QObject(parent), m_account(account), m_playback(playback), m_home(home), m_auth(auth), m_qobuz(qobuz),
      m_hub(account) {
  m_publishTimer.setSingleShot(true);
  m_publishTimer.setInterval(120);
  connect(&m_publishTimer, &QTimer::timeout, this, &ConnectService::publish);
  // A remote clock is projected locally; this only repaints the seek bar.
  m_tickTimer.setInterval(250);
  connect(&m_tickTimer, &QTimer::timeout, this, [this] {
    if (m_playback)
      m_playback->remoteChanged();
  });
  m_interfaceTimer.setInterval(30000);
  connect(&m_interfaceTimer, &QTimer::timeout, this, &ConnectService::refreshInterfaces);

  connect(&m_hub, &ConnectHubSocket::opened, this, [this] {
    if (m_node)
      m_node->hubOpened();
  });
  connect(&m_hub, &ConnectHubSocket::message, this, [this](const QString &text) {
    if (m_node)
      m_node->hubMessage(text.toStdString());
  });
  connect(&m_hub, &ConnectHubSocket::closed, this, [this] {
    if (m_node)
      m_node->hubClosed();
  });

  connect(m_account, &OrchardAccount::statusChanged, this, &ConnectService::accountChanged);
  connect(m_account, &OrchardAccount::userChanged, this, &ConnectService::accountChanged);
  connect(m_auth, &AuthManager::statusChanged, this, &ConnectService::refreshDevice);
  connect(m_qobuz, &QobuzService::changed, this, &ConnectService::refreshDevice);
  connect(m_playback, &PlaybackController::stateChanged, this, &ConnectService::schedulePublish);
  connect(m_playback, &PlaybackController::queueChanged, this, &ConnectService::schedulePublish);
  connect(m_home, &HomeController::volumeChanged, this, &ConnectService::schedulePublish);
  m_playback->setRemote(this);
  m_playback->setRemoteProvider(this);
  setupMixHost();
  accountChanged();
}

ConnectService::~ConnectService() {
  if (m_playback) {
    m_playback->setRemote(nullptr);
    m_playback->setRemoteProvider(nullptr);
  }
  m_hub.stop();
  // Joins the Connect thread before anything it calls back into goes away.
  m_node.reset();
}

void ConnectService::accountChanged() {
  const QString deviceId = m_account && m_account->isSignedIn() ? m_account->deviceId() : QString();
  if (deviceId == m_deviceId)
    return;
  m_deviceId = deviceId;
  // A new account session gets a fresh node: no listener, keys or peers carry over.
  m_hub.stop();
  m_node.reset();
  m_online = false;
  m_sessions.clear();
  m_pendingPeer.clear();
  m_devices.clear();
  m_remote = {};
  m_lastDevice.clear();
  m_lastInterfaces.clear();
  if (!deviceId.isEmpty()) {
    m_node = std::make_unique<orchard::connect::Node>(*this);
    refreshDevice();
    refreshInterfaces();
    publish();
    m_interfaceTimer.start();
    m_hub.start();
  } else {
    m_interfaceTimer.stop();
  }
  updateRemoteMode();
  emit availableChanged();
  emit devicesChanged();
  emit sessionChanged();
}

void ConnectService::refreshDevice() {
  if (!m_node)
    return;
  const QJsonObject device{
      {QStringLiteral("id"), m_deviceId},
      {QStringLiteral("name"), OrchardAccount::defaultDeviceName()},
      {QStringLiteral("platform"), OrchardAccount::platformName()},
      {QStringLiteral("kind"), QStringLiteral("desktop")},
      {QStringLiteral("can_render_audio"), true},
      // Mixing for a peer needs only the worker; this desktop's own crossfade mode is beside the point.
      {QStringLiteral("can_mix_audio"), AdaptiveMixWorker::installed()},
      {QStringLiteral("can_fetch_artwork"), true},
      {QStringLiteral("providers"), QJsonArray{QStringLiteral("youtube"), QStringLiteral("qobuz")}},
      {QStringLiteral("provider_sessions"),
       QJsonObject{{QStringLiteral("youtube"), m_auth->isSignedIn()}, {QStringLiteral("qobuz"), m_qobuz->connected()}}},
      {QStringLiteral("audio_transports"), QJsonArray{QStringLiteral("pcm_s16"), QStringLiteral("pcm_f32"), QStringLiteral("source")}}};
  const QByteArray encoded = QJsonDocument(device).toJson(QJsonDocument::Compact);
  if (encoded == m_lastDevice)
    return;
  m_lastDevice = encoded;
  m_node->setDevice(encoded.toStdString());
}

void ConnectService::refreshInterfaces() {
  if (!m_node)
    return;
  QJsonArray list;
  for (const QNetworkInterface &item : QNetworkInterface::allInterfaces()) {
    const auto flags = item.flags();
    if (!(flags & QNetworkInterface::IsUp) || !(flags & QNetworkInterface::IsRunning) ||
        (flags & QNetworkInterface::IsLoopBack))
      continue;
    for (const QNetworkAddressEntry &entry : item.addressEntries()) {
      if (entry.ip().protocol() != QAbstractSocket::IPv4Protocol)
        continue;
      list.append(QJsonObject{{QStringLiteral("name"), item.humanReadableName() + QLatin1Char(' ') + item.name()},
                              {QStringLiteral("address"), entry.ip().toString()}});
    }
  }
  const QByteArray encoded = QJsonDocument(list).toJson(QJsonDocument::Compact);
  if (encoded == m_lastInterfaces)
    return;
  m_lastInterfaces = encoded;
  m_node->setInterfaces(encoded.toStdString());
}

void ConnectService::schedulePublish() {
  if (m_node && !m_publishTimer.isActive())
    m_publishTimer.start();
}

void ConnectService::publish() {
  if (m_node && m_playback && m_home)
    m_node->publishPlayback(compact(connect_tracks::localSnapshot(*m_playback, *m_home)));
}

void ConnectService::connectTo(const QString &deviceId) {
  if (!m_node)
    return;
  if (deviceId.isEmpty() || deviceId == m_deviceId) {
    // "This computer": stop controlling anything else.
    if (const QString sid = controllerSessionId(); !sid.isEmpty())
      m_node->disconnect(sid.toStdString());
    return;
  }
  m_node->connectTo(deviceId.toStdString());
}

void ConnectService::disconnectAll() {
  if (m_node)
    m_node->disconnect();
}

void ConnectService::setRemoteVolume(double volume) {
  command(QStringLiteral("set_volume"), {{QStringLiteral("volume"), std::clamp(volume, 0.0, 1.0)}});
}

void ConnectService::clearMessage() { setMessage({}); }

void ConnectService::setMessage(const QString &message) {
  if (message == m_message)
    return;
  m_message = message;
  emit messageChanged();
}

void ConnectService::request(const QString &host, const QString &method, const QJsonObject &params,
                             std::function<void(const QJsonObject &)> done) {
  if (!m_node) {
    done({{QStringLiteral("ok"), false}, {QStringLiteral("error"), QStringLiteral("not_connected")}});
    return;
  }
  const std::string id = m_node->request(compact(
      {{QStringLiteral("host"), host}, {QStringLiteral("method"), method}, {QStringLiteral("params"), params}}));
  m_requests.insert(QString::fromStdString(id), std::move(done));
}

QString ConnectService::roleHost(const QString &role) const {
  const QString host = role.startsWith(QStringLiteral("provider:"))
                           ? m_roles.value(QStringLiteral("provider_hosts")).toObject().value(role.mid(9)).toString()
                           : m_roles.value(role + QStringLiteral("_host")).toString();
  return host == m_deviceId ? QString() : host;
}

// NodeHost. Everything hops to the UI thread; `this` as context drops calls
// queued after destruction.

void ConnectService::hubSend(const std::string &text) {
  QMetaObject::invokeMethod(this, [this, text = QString::fromStdString(text)] { m_hub.send(text); },
                            Qt::QueuedConnection);
}

void ConnectService::event(const std::string &json) {
  const QJsonObject object = QJsonDocument::fromJson(QByteArray::fromStdString(json)).object();
  QMetaObject::invokeMethod(this, [this, object] { handleEvent(object); }, Qt::QueuedConnection);
}

void ConnectService::data(const std::string &sessionId, const std::string &headerJson, std::string payload) {
  const QJsonObject header = QJsonDocument::fromJson(QByteArray::fromStdString(headerJson)).object();
  const QString kind = header.value(QStringLiteral("kind")).toString();
  if (kind == QStringLiteral("audio")) {
    // Source audio a target uploaded for this desktop to mix.
    QMetaObject::invokeMethod(
        this,
        [this, sid = QString::fromStdString(sessionId), header, bytes = QByteArray::fromStdString(payload)] {
          m_mix->acceptSource(sid, header, bytes);
        },
        Qt::QueuedConnection);
    return;
  }
  if (kind != QStringLiteral("range"))
    return;
  QMetaObject::invokeMethod(
      this,
      [this, id = header.value(QStringLiteral("rpc")).toString(), bytes = QByteArray::fromStdString(payload)] {
        // Only requests still waiting may stash bytes; late ones would never be collected.
        if (m_requests.contains(id))
          m_rangeBytes.insert(id, bytes);
      },
      Qt::QueuedConnection);
}

void ConnectService::log(const std::string &message) { qInfo().noquote() << "Connect:" << QString::fromStdString(message); }

void ConnectService::handleEvent(const QJsonObject &event) {
  const QString name = event.value(QStringLiteral("event")).toString();
  if (name == QStringLiteral("hub")) {
    m_online = event.value(QStringLiteral("connected")).toBool();
    emit availableChanged();
  } else if (name == QStringLiteral("devices")) {
    m_devices = event.value(QStringLiteral("devices")).toArray().toVariantList();
    emit devicesChanged();
  } else if (name == QStringLiteral("session")) {
    handleSession(event);
  } else if (name == QStringLiteral("session_ended")) {
    handleSessionEnded(event);
  } else if (name == QStringLiteral("remote_state")) {
    handleRemoteState(event);
  } else if (name == QStringLiteral("initial_playback")) {
    const QString outcome = event.value(QStringLiteral("outcome")).toString();
    if (event.value(QStringLiteral("role")).toString() == QStringLiteral("target")) {
      if (outcome == QStringLiteral("transferred"))
        applyTransfer(event.value(QStringLiteral("snapshot")).toObject());
    } else if (outcome != QStringLiteral("none") && m_playback) {
      // This desktop becomes a remote control; its own speakers go quiet.
      m_playback->pause();
    }
  } else if (name == QStringLiteral("roles")) {
    m_roles = event.value(QStringLiteral("roles")).toObject();
    m_playback->remoteProviderChanged();
    emit rolesChanged();
  } else if (name == QStringLiteral("command")) {
    applyCommand(event);
  } else if (name == QStringLiteral("command_result")) {
    if (!event.value(QStringLiteral("ok")).toBool())
      setMessage(describe(event.value(QStringLiteral("error")).toString(), peer().value(QStringLiteral("name")).toString()));
  } else if (name == QStringLiteral("rpc")) {
    handleRpc(event);
  } else if (name == QStringLiteral("rpc_result")) {
    if (auto done = m_requests.take(event.value(QStringLiteral("id")).toString()))
      done(event);
  } else if (name == QStringLiteral("error")) {
    QString deviceName;
    const QString deviceId = event.value(QStringLiteral("device_id")).toString();
    for (const QVariant &device : std::as_const(m_devices)) {
      if (device.toMap().value(QStringLiteral("id")).toString() == deviceId)
        deviceName = device.toMap().value(QStringLiteral("name")).toString();
    }
    if (!deviceId.isEmpty() && m_pendingPeer.value(QStringLiteral("id")).toString() == deviceId) {
      m_pendingPeer.clear();
      emit sessionChanged();
    }
    setMessage(describe(event.value(QStringLiteral("code")).toString(), deviceName));
  }
}

void ConnectService::handleSession(const QJsonObject &event) {
  const QString sid = event.value(QStringLiteral("session_id")).toString();
  const QVariantMap peerInfo = event.value(QStringLiteral("peer")).toObject().toVariantMap();
  if (sid.isEmpty()) {
    m_pendingPeer = peerInfo;
  } else {
    if (m_pendingPeer.value(QStringLiteral("id")) == peerInfo.value(QStringLiteral("id")))
      m_pendingPeer.clear();
    m_sessions.insert(sid, {event.value(QStringLiteral("role")).toString(), event.value(QStringLiteral("state")).toString(),
                            event.value(QStringLiteral("transport")).toString(), peerInfo});
  }
  setMessage({});
  updateRemoteMode();
  emit sessionChanged();
}

void ConnectService::handleSessionEnded(const QJsonObject &event) {
  const QString sid = event.value(QStringLiteral("session_id")).toString();
  m_mix->dropSession(sid);
  const SessionView ended = m_sessions.take(sid);
  const QString reason = event.value(QStringLiteral("reason")).toString();
  if (ended.role == QStringLiteral("controller")) {
    m_remote = {};
    m_remoteTrack.clear();
    m_remoteQueue.clear();
  }
  // Leaving on purpose needs no explanation.
  if (reason != QStringLiteral("user_disconnected") && reason != QStringLiteral("switched_target") &&
      reason != QStringLiteral("role_change"))
    setMessage(describe(reason, ended.peer.value(QStringLiteral("name")).toString()));
  updateRemoteMode();
  emit sessionChanged();
}

void ConnectService::handleRemoteState(const QJsonObject &event) {
  if (event.value(QStringLiteral("session_id")).toString() != controllerSessionId())
    return;
  m_remote = event.value(QStringLiteral("snapshot")).toObject();
  m_remoteClock.restart();
  m_remoteTrack = connect_tracks::fromWire(m_remote.value(QStringLiteral("track")).toObject());
  m_remoteQueue = connect_tracks::fromWire(m_remote.value(QStringLiteral("queue")).toArray());
  updateRemoteMode();
  emit remoteChanged();
  if (m_playback)
    m_playback->remoteChanged();
}

void ConnectService::updateRemoteMode() {
  const bool isActive = active();
  if (isActive && playing())
    m_tickTimer.start();
  else
    m_tickTimer.stop();
  if (isActive != m_remoteWasActive) {
    m_remoteWasActive = isActive;
    if (m_playback)
      m_playback->remoteChanged();
  }
}

QString ConnectService::controllerSessionId() const {
  for (auto it = m_sessions.cbegin(); it != m_sessions.cend(); ++it) {
    if (it->role == QStringLiteral("controller"))
      return it.key();
  }
  return {};
}

QString ConnectService::primarySessionId() const {
  if (const QString sid = controllerSessionId(); !sid.isEmpty())
    return sid;
  return m_sessions.isEmpty() ? QString() : m_sessions.cbegin().key();
}

QString ConnectService::state() const {
  const QString sid = primarySessionId();
  if (!sid.isEmpty())
    return m_sessions.value(sid).state;
  return m_pendingPeer.isEmpty() ? QString() : QStringLiteral("DISCOVERING");
}

QString ConnectService::role() const {
  const QString sid = primarySessionId();
  if (!sid.isEmpty())
    return m_sessions.value(sid).role;
  return m_pendingPeer.isEmpty() ? QString() : QStringLiteral("controller");
}

QVariantMap ConnectService::peer() const {
  const QString sid = primarySessionId();
  return sid.isEmpty() ? m_pendingPeer : m_sessions.value(sid).peer;
}

QString ConnectService::transport() const { return m_sessions.value(primarySessionId()).transport; }

QStringList ConnectService::controllers() const {
  QStringList names;
  for (const SessionView &view : m_sessions) {
    if (view.role == QStringLiteral("target") && view.state == QStringLiteral("CONNECTED"))
      names.append(view.peer.value(QStringLiteral("name")).toString());
  }
  return names;
}

bool ConnectService::active() const {
  const QString sid = controllerSessionId();
  if (sid.isEmpty() || m_remote.isEmpty())
    return false;
  const QString sessionState = m_sessions.value(sid).state;
  return sessionState == QStringLiteral("CONNECTED") || sessionState == QStringLiteral("RECONNECTING");
}

bool ConnectService::playing() const { return m_remote.value(QStringLiteral("playing")).toBool(); }

bool ConnectService::loading() const { return m_remote.value(QStringLiteral("buffering")).toBool(); }

double ConnectService::position() const {
  double at = m_remote.value(QStringLiteral("position")).toDouble();
  // A target out of reach has a frozen clock as far as this side knows.
  if (playing() && !loading() && m_remoteClock.isValid() &&
      m_sessions.value(controllerSessionId()).state == QStringLiteral("CONNECTED"))
    at += m_remoteClock.elapsed() / 1000.0;
  const double total = duration();
  return total > 0.0 ? std::min(at, total) : at;
}

double ConnectService::duration() const { return m_remote.value(QStringLiteral("duration")).toDouble(); }

bool ConnectService::shuffle() const { return m_remote.value(QStringLiteral("shuffle")).toBool(); }

QString ConnectService::repeat() const {
  const QString mode = m_remote.value(QStringLiteral("repeat")).toString();
  return mode.isEmpty() ? QStringLiteral("off") : mode;
}

double ConnectService::remoteVolume() const { return m_remote.value(QStringLiteral("volume")).toDouble(1.0); }

void ConnectService::command(const QString &action, const QVariantMap &args) {
  if (!m_node)
    return;
  QJsonObject wireArgs;
  for (auto it = args.cbegin(); it != args.cend(); ++it) {
    if (it.key() == QStringLiteral("track"))
      wireArgs.insert(it.key(), connect_tracks::toWire(it.value().toMap()));
    else if (it.key() == QStringLiteral("tracks"))
      wireArgs.insert(it.key(), connect_tracks::toWire(it.value().toList()));
    else
      wireArgs.insert(it.key(), QJsonValue::fromVariant(it.value()));
  }
  m_node->command(compact({{QStringLiteral("action"), action}, {QStringLiteral("args"), wireArgs}}));
}

QString ConnectService::describe(const QString &code, const QString &deviceName) const {
  const QString who = deviceName.isEmpty() ? tr("The other device") : deviceName;
  if (code == QStringLiteral("incompatible_protocol") || code == QStringLiteral("incompatible_client"))
    return tr("%1 runs a different Orchard version. Update both devices to use Connect.").arg(who);
  if (code == QStringLiteral("peer_offline"))
    return tr("%1 is offline.").arg(who);
  if (code == QStringLiteral("busy"))
    return tr("%1 is already controlling another device.").arg(who);
  if (code == QStringLiteral("hub_offline"))
    return tr("Orchard Connect is offline. Check your connection.");
  if (code == QStringLiteral("target_lost") || code == QStringLiteral("transport_failed") ||
      code == QStringLiteral("timeout"))
    return tr("Lost the connection to %1.").arg(who);
  if (code == QStringLiteral("controller_lost"))
    return tr("%1 stopped controlling this computer.").arg(who);
  if (code == QStringLiteral("unauthorized"))
    return tr("%1 could not be verified. Sign in to Orchard on both devices.").arg(who);
  if (code == QStringLiteral("shutdown") || code == QStringLiteral("peer_left"))
    return tr("%1 left Orchard Connect.").arg(who);
  if (code == QStringLiteral("provider_unavailable"))
    return tr("No connected device is signed in to that service.");
  return tr("Orchard Connect: %1").arg(code);
}
