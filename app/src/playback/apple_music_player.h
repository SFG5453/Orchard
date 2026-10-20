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
#include <QTimer>

class AppleMusic;
class QProcess;

// Apple Music songs play through MusicKit JS in orchard-auth-helper's player mode
// (applemusic/player.html). Apple's DRM keeps decoded audio inside that process, so
// this side only sends commands and mirrors the state the page reports. An idle
// helper ends after a few minutes; a paused song reloads at its position on play().
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
  // Hands the page an adaptive mix into `appleId` (see AppleMusicMix::plan). If the page
  // cannot place it, it falls back to prepare(appleId, fallbackFade).
  void mix(const QString &appleId, QJsonObject plan, double fallbackFade);
  bool mixPrepared() const { return m_preparedMix; }
  // Where the armed mix starts in the current song and how long it runs; -1 and 0 until armed.
  double mixStart() const { return m_mixStart; }
  double mixDuration() const { return m_mixDuration; }
  // The page could not place a mix from the current song into `appleId`.
  bool mixRefused(const QString &appleId) const { return m_refusedMix == m_id + QLatin1Char('\n') + appleId; }
  // Ends the helper process; the next load starts a new one.
  void shutdown();

  QString currentId() const { return m_id; }
  QString preparedId() const { return m_prepared; }
  double preparedFade() const { return m_preparedFade; }
  bool playing() const { return m_playing; }
  bool loading() const { return m_loading; }
  // Projected from the last report with the local clock.
  double position() const;
  double duration() const { return m_duration; }
  // During a crossfade: the incoming song, its clock, length and blend progress (0 to 1).
  QString fadingId() const { return m_fadeId; }
  // Playing song's position when the blend began.
  double fadeFrom() const { return m_fadeFrom; }
  double fadePosition() const;
  double fadeDuration() const { return m_fadeDuration; }
  double fadeProgress() const;

signals:
  void changed();
  void playingChanged();
  // The song finished and nothing was prepared.
  void ended();
  // The prepared song started blending in; changed() repeats until it takes over.
  void fadeStarted();
  // The prepared song took over (gapless start or end of a crossfade).
  void advanced(const QString &appleId);
  // The page armed a mix (empty reason) or gave one up.
  void mixSettled(const QString &reason);
  void failed(const QString &message);

private:
  void start();
  void send(const QJsonObject &command);
  void write(const QByteArray &line);
  void readEvents();
  void receive(const QJsonObject &event);
  void processEnded();
  void clearFade();
  double fadeElapsed() const;
  void dropPrepared();
  bool idle() const;
  void updateIdleTimer();
  void releaseIdle();
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
  bool m_preparedMix{false};
  double m_mixStart{-1.0};
  double m_mixDuration{0.0};
  QString m_refusedMix;
  bool m_playing{false};
  bool m_loading{false};
  double m_position{0.0};
  double m_duration{0.0};
  double m_volume{1.0};
  QElapsedTimer m_reportedAt;
  QString m_fadeId;
  double m_fadeSeconds{0.0};
  double m_fadeFrom{0.0};
  // Incoming song time where the blend begins: 0 for a crossfade, the cue for a mix.
  double m_fadeAt{0.0};
  double m_fadeDuration{0.0};
  // Blend seconds banked before the last pause; the clock runs only while playing.
  double m_fadeBanked{0.0};
  QElapsedTimer m_fadeClock;
  QTimer *m_fadeTicker;
  QTimer *m_idleTimer;
};
