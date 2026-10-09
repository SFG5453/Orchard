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

#pragma once

#include "connect/node.h"
#include "connect_hub_socket.h"
#include "playback/playback_remote.h"

#include <QElapsedTimer>
#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

#include <functional>
#include <memory>

class AnimatedArtworkService;
class ConnectMixHost;
class AuthManager;
class HomeController;
class OrchardAccount;
class PlaybackController;
class QobuzService;
class YouTubeCatalog;

// Orchard Connect on the desktop (QML: OrchardConnect). The protocol, roles and
// transports live in core/native/connect; this class feeds it the account hub,
// the local player and provider work, and mirrors a remote target for the UI.
class ConnectService final : public QObject,
                             public orchard::connect::NodeHost,
                             public PlaybackRemote,
                             public RemoteProvider {
  Q_OBJECT
  // Signed in to an Orchard account, which Connect needs for discovery and keys.
  Q_PROPERTY(bool available READ available NOTIFY availableChanged)
  Q_PROPERTY(bool online READ online NOTIFY availableChanged)
  Q_PROPERTY(QVariantList devices READ devices NOTIFY devicesChanged)
  // The session the UI shows: this desktop's target, else its first controller.
  Q_PROPERTY(QString state READ state NOTIFY sessionChanged)
  Q_PROPERTY(QString role READ role NOTIFY sessionChanged)
  Q_PROPERTY(QVariantMap peer READ peer NOTIFY sessionChanged)
  Q_PROPERTY(QString transport READ transport NOTIFY sessionChanged)
  Q_PROPERTY(bool controlling READ controlling NOTIFY sessionChanged)
  Q_PROPERTY(QStringList controllers READ controllers NOTIFY sessionChanged)
  Q_PROPERTY(double remoteVolume READ remoteVolume NOTIFY remoteChanged)
  Q_PROPERTY(QString message READ message NOTIFY messageChanged)

public:
  ConnectService(OrchardAccount *account, PlaybackController *playback, HomeController *home, AuthManager *auth,
                 QobuzService *qobuz, QObject *parent = nullptr);
  ~ConnectService() override;

  void setCatalog(YouTubeCatalog *catalog);
  void setArtwork(AnimatedArtworkService *artwork);

  [[nodiscard]] bool available() const { return static_cast<bool>(m_node); }
  [[nodiscard]] bool online() const { return m_online; }
  [[nodiscard]] QVariantList devices() const { return m_devices; }
  [[nodiscard]] QString state() const;
  [[nodiscard]] QString role() const;
  [[nodiscard]] QVariantMap peer() const;
  [[nodiscard]] QString transport() const;
  [[nodiscard]] bool controlling() const { return active(); }
  [[nodiscard]] QStringList controllers() const;
  [[nodiscard]] double remoteVolume() const;
  [[nodiscard]] QString message() const { return m_message; }

  Q_INVOKABLE void connectTo(const QString &deviceId);
  Q_INVOKABLE void disconnectAll();
  Q_INVOKABLE void setRemoteVolume(double volume);
  Q_INVOKABLE void clearMessage();

  // Sends an RPC to the device holding a role ("artwork", "mix", "provider:qobuz").
  // The callback runs on the UI thread with {ok, result, error}.
  void request(const QString &host, const QString &method, const QJsonObject &params,
               std::function<void(const QJsonObject &)> done);
  // The role holder for `role`, or an empty string when it is this device.
  [[nodiscard]] QString roleHost(const QString &role) const;

  // NodeHost: the Connect thread calls these.
  void hubSend(const std::string &text) override;
  void event(const std::string &json) override;
  void data(const std::string &sessionId, const std::string &headerJson, std::string payload) override;
  void log(const std::string &message) override;

  // PlaybackRemote: the mirrored target.
  [[nodiscard]] bool active() const override;
  [[nodiscard]] QVariantMap track() const override { return m_remoteTrack; }
  [[nodiscard]] QVariantList queue() const override { return m_remoteQueue; }
  [[nodiscard]] bool playing() const override;
  [[nodiscard]] bool loading() const override;
  [[nodiscard]] double position() const override;
  [[nodiscard]] double duration() const override;
  [[nodiscard]] bool shuffle() const override;
  [[nodiscard]] QString repeat() const override;
  void command(const QString &action, const QVariantMap &args = {}) override;

  // RemoteProvider: this desktop borrowing a peer's provider session.
  [[nodiscard]] bool available(const QString &provider) const override;
  void resolveTrack(const QString &provider, const QVariantMap &track, Reply done) override;
  void readRange(const QString &provider, const QString &playbackId, qint64 start, qint64 end, Bytes done) override;
  void report(const QString &provider, const QString &playbackId, bool started, double position) override;

signals:
  void availableChanged();
  void devicesChanged();
  void sessionChanged();
  void remoteChanged();
  void messageChanged();
  void rolesChanged();

private:
  struct SessionView {
    QString role;
    QString state;
    QString transport;
    QVariantMap peer;
  };

  void handleEvent(const QJsonObject &event);
  void handleSession(const QJsonObject &event);
  void handleSessionEnded(const QJsonObject &event);
  void handleRemoteState(const QJsonObject &event);
  void accountChanged();
  void refreshDevice();
  void refreshInterfaces();
  void schedulePublish();
  void publish();
  void updateRemoteMode();
  void setMessage(const QString &message);
  [[nodiscard]] QString controllerSessionId() const;
  [[nodiscard]] QString primarySessionId() const;
  [[nodiscard]] QString describe(const QString &code, const QString &deviceName) const;

  // connect_target.cpp: this desktop as the target.
  void applyCommand(const QJsonObject &event);
  void applyTransfer(const QJsonObject &snapshot);
  // connect_rpc.cpp: provider and artwork work requested by a peer.
  void handleRpc(const QJsonObject &event);
  void respond(const QString &id, bool ok, const QJsonValue &result, const QString &error = {});
  void serveCatalog(const QString &id, const QString &method, const QJsonObject &params);
  void serveQobuz(const QString &id, const QString &sessionId, const QString &method, const QJsonObject &params);
  void serveArtwork(const QString &id, const QJsonObject &params);
  void wireCatalog();
  void setupMixHost();

  QPointer<OrchardAccount> m_account;
  QPointer<PlaybackController> m_playback;
  QPointer<HomeController> m_home;
  QPointer<AuthManager> m_auth;
  QPointer<QobuzService> m_qobuz;
  QPointer<YouTubeCatalog> m_catalog;
  QPointer<AnimatedArtworkService> m_artwork;
  ConnectHubSocket m_hub;
  // Smart Crossfade work for a phone target; see connect_mix.h.
  std::unique_ptr<ConnectMixHost> m_mix;
  std::unique_ptr<orchard::connect::Node> m_node;
  QString m_deviceId;
  QByteArray m_lastDevice;
  QByteArray m_lastInterfaces;
  bool m_online{false};

  QVariantList m_devices;
  QHash<QString, SessionView> m_sessions;
  QVariantMap m_pendingPeer; // asked the hub for a session, no grant yet
  QJsonObject m_roles;
  QString m_message;

  QJsonObject m_remote;
  QElapsedTimer m_remoteClock;
  QVariantMap m_remoteTrack;
  QVariantList m_remoteQueue;
  bool m_remoteWasActive{false};

  QHash<QString, std::function<void(const QJsonObject &)>> m_requests;
  // Bytes that arrived ahead of their rpc_result, by request id.
  QHash<QString, QByteArray> m_rangeBytes;
  // Catalog request id -> the peer's rpc id waiting for it.
  QHash<quint64, QString> m_catalogRequests;
  quint64 m_artworkRequest{0};
  QTimer m_publishTimer;
  QTimer m_tickTimer;
  QTimer m_interfaceTimer;
};
