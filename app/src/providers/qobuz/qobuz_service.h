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
#include <QVariantMap>
#include <functional>

class ProviderRuntime;
class QobuzOAuthCallback;

// The user's own Qobuz subscription as an optional lossless source. YouTube
// Music stays the catalog; tracks Qobuz can't identify still play from YouTube.
class QobuzService final : public QObject {
  Q_OBJECT
  // restoring, disconnected, connecting, connected
  Q_PROPERTY(QString status READ status NOTIFY changed)
  Q_PROPERTY(bool connected READ connected NOTIFY changed)
  // Connected and wanted (streaming quality MAX): playback asks Qobuz first.
  Q_PROPERTY(bool active READ active NOTIFY changed)
  Q_PROPERTY(QString message READ message NOTIFY changed)
  Q_PROPERTY(bool messageIsError READ messageIsError NOTIFY changed)

public:
  // `error` is empty on success.
  using Reply = std::function<void(const QJsonValue &result, const QString &error)>;
  using Bytes = std::function<void(const QByteArray &bytes, const QString &error)>;

  // `restore` reads the saved session from the keychain; tests pass false.
  explicit QobuzService(QObject *parent = nullptr, bool restore = true);
  ~QobuzService() override;

  QString status() const { return m_status; }
  bool connected() const { return m_status == QStringLiteral("connected"); }
  // Playback sets this from the MAX streaming quality.
  void setEnabled(bool enabled);
  bool active() const { return connected() && m_enabled; }
  QString message() const { return m_message; }
  bool messageIsError() const { return m_messageIsError; }

  Q_INVOKABLE void connectAccount();
  Q_INVOKABLE void cancelConnection();
  Q_INVOKABLE void disconnectAccount();

  // Matches a catalog track and opens a stream. Resolves to null when Qobuz
  // has no confident match; the caller then plays YouTube. A Connect peer asks
  // whenever the account is connected, whatever this desktop's own quality is.
  void resolveTrack(const QVariantMap &track, Reply done, bool forPeer = false);
  void readRange(const QString &playbackId, qint64 start, qint64 end, Bytes done);
  void playbackStarted(const QString &playbackId, double position);
  void playbackEnded(const QString &playbackId, double position);
  // Best quality this account streams for an album, or null.
  void albumQuality(const QVariantMap &album, Reply done);

signals:
  void changed();
  // The user finished signing in, or chose Disconnect.
  void accountConnected();
  void accountDisconnected();

private:
  quint64 call(const QString &method, const QJsonValue &payload, Reply done = {});
  void restoreSession();
  void storeSession(const QString &token, qint64 userId);
  void forgetSession();
  void applySession(const QString &token, qint64 userId);
  void finishConnection(const QString &code);
  void setStatus(const QString &status);
  void setMessage(const QString &message, bool error = false);

  ProviderRuntime *m_runtime;
  QobuzOAuthCallback *m_callback;
  QHash<quint64, Reply> m_replies;
  QHash<quint64, Bytes> m_reads;
  // Album badges by "title\nartist"; searches cost a round trip each.
  QHash<QString, QJsonValue> m_albumQuality;
  QString m_status{QStringLiteral("disconnected")};
  QString m_message;
  quint64 m_connectGeneration{0};
  bool m_enabled{false};
  bool m_messageIsError{false};
};
