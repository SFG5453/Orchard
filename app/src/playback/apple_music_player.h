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

#include <QByteArray>
#include <QElapsedTimer>
#include <QJsonObject>
#include <QList>
#include <QObject>
#include <QString>

class AppleMusic;
class QProcess;

// Apple Music songs play through MusicKit JS in orchard-auth-helper's player mode
// (applemusic/player.html). Apple's DRM keeps decoded audio inside that process, so
// this side only sends commands and mirrors the state the page reports.
class AppleMusicPlayer final : public QObject {
  Q_OBJECT

public:
  explicit AppleMusicPlayer(AppleMusic *account, QObject *parent = nullptr);
  ~AppleMusicPlayer() override;

  // Builds whose helper hosts the player (Linux with Qt WebEngine).
  static bool available();

  // `appleId` is the catalog song id. With `play` false the song waits for play().
  void load(const QString &appleId, double position, bool play);
  void play();
  void pause();
  void seek(double seconds);
  void stop();
  // Linear output gain, 0 to 1.
  void setVolume(double gain);
  // Buffers the next song on the spare player. It crossfades in over `fadeSeconds`
  // before this one ends, or starts the moment it ends when 0. An empty id drops it.
  void prepare(const QString &appleId, double fadeSeconds);
  // Ends the helper process; the next load starts a new one.
  void shutdown();

  QString currentId() const { return m_id; }
  QString preparedId() const { return m_prepared; }
  bool playing() const { return m_playing; }
  bool loading() const { return m_loading; }
  // Projected from the last report with the local clock.
  double position() const;
  double duration() const { return m_duration; }

signals:
  void changed();
  void playingChanged();
  // The song finished and nothing was prepared.
  void ended();
  // The prepared song took over (gapless start or end of a crossfade).
  void advanced(const QString &appleId);
  void failed(const QString &message);

private:
  void start();
  void send(const QJsonObject &command);
  void write(const QByteArray &line);
  void readEvents();
  void receive(const QJsonObject &event);
  void processEnded();
  QString errorText(const QString &code) const;

  AppleMusic *m_account;
  QProcess *m_process{nullptr};
  QByteArray m_buffer;
  // Commands written before the developer token arrives wait here.
  QList<QByteArray> m_pending;
  bool m_configured{false};
  bool m_widevine{true};
  quint64 m_generation{0};
  QString m_id;
  QString m_prepared;
  double m_preparedFade{0.0};
  bool m_playing{false};
  bool m_loading{false};
  double m_position{0.0};
  double m_duration{0.0};
  double m_volume{1.0};
  QElapsedTimer m_reportedAt;
};
