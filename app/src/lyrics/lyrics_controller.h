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
#include <QJsonValue>
#include <QObject>
#include <QStringList>
#include <QVariantList>
#include <QVariantMap>

class AuthManager;
class LocalLibrary;
class PlaybackController;
class YouTubeProvider;

// Fetches lyrics while a lyrics view or synced Discord presence needs them.
class LyricsController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool active READ active WRITE setActive NOTIFY activeChanged)
  Q_PROPERTY(bool followIncoming READ followIncoming WRITE setFollowIncoming NOTIFY followIncomingChanged)
  Q_PROPERTY(QString status READ status NOTIFY stateChanged)
  Q_PROPERTY(QString mode READ mode NOTIFY stateChanged)
  Q_PROPERTY(QString source READ source NOTIFY stateChanged)
  Q_PROPERTY(QString trackId READ trackId NOTIFY stateChanged)
  Q_PROPERTY(QVariantList lines READ lines NOTIFY stateChanged)

public:
  explicit LyricsController(YouTubeProvider *provider, AuthManager *auth,
                            PlaybackController *playback,
                            QObject *parent = nullptr);

  [[nodiscard]] bool active() const { return m_active; }
  [[nodiscard]] QString status() const { return m_status; }
  [[nodiscard]] QString mode() const { return m_mode; }
  [[nodiscard]] QString source() const { return m_source; }
  [[nodiscard]] QString trackId() const { return m_trackId; }
  [[nodiscard]] QVariantList lines() const { return m_lines; }
  Q_INVOKABLE int indexAt(double time) const;

  [[nodiscard]] bool followIncoming() const { return m_followIncoming; }
  void setActive(bool active);
  void setFollowIncoming(bool follow);
  void setDiscordLyricsEnabled(bool enabled);
  // Songs from the local library read their lyrics here instead of online.
  void setLocalLibrary(LocalLibrary *library);
  Q_INVOKABLE void reload();

signals:
  void activeChanged();
  void followIncomingChanged();
  void stateChanged();
  void activeLineChanged(const QString &trackId, const QString &text);

private:
  void syncTrack();
  QVariantMap viewTrack() const;
  void prefetchTransition();
  void requestLocal(const QVariantMap &track);
  void refreshLocal();
  void request(const QVariantMap &track);
  QJsonObject payload(const QVariantMap &track) const;
  void remember(const QString &trackId, const QVariantMap &result);
  void receive(quint64 requestId, const QJsonValue &result);
  void fail(quint64 requestId, const QString &message);
  void apply(const QString &trackId, const QVariantMap &result);
  void reset(const QString &trackId, const QString &status);
  void updateActiveLine();

  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  PlaybackController *m_playback;
  LocalLibrary *m_local{nullptr};
  QHash<QString, QVariantMap> m_cache;
  QStringList m_cacheOrder;
  QVariantList m_lines;
  QString m_status{QStringLiteral("idle")};
  QString m_mode;
  QString m_source;
  QString m_trackId;
  QString m_requestTrackId;
  quint64 m_requestId{0};
  QString m_prefetchTrackId;
  quint64 m_prefetchId{0};
  bool m_active{false};
  bool m_followIncoming{false};
  bool m_discordLyricsEnabled{false};
  int m_activeLineIndex{-1};
  QString m_activeLineTrackId;
  QString m_activeLineText;
};
