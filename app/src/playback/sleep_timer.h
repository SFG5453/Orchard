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

#include <QObject>
#include <QString>
#include <QTimer>

class PlaybackController;

// Pauses playback after a delay or when the current track ends.
class SleepTimer final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool active READ active NOTIFY changed)
  Q_PROPERTY(bool endOfTrack READ endOfTrack NOTIFY changed)
  Q_PROPERTY(int remainingSeconds READ remainingSeconds NOTIFY tick)

public:
  explicit SleepTimer(PlaybackController *playback, QObject *parent = nullptr);

  [[nodiscard]] bool active() const { return m_minutes > 0 || m_endOfTrack; }
  [[nodiscard]] bool endOfTrack() const { return m_endOfTrack; }
  [[nodiscard]] int remainingSeconds() const;

  Q_INVOKABLE void startMinutes(int minutes);
  Q_INVOKABLE void startEndOfTrack();
  Q_INVOKABLE void cancel();

signals:
  void changed();
  void tick();
  // Playback was paused by the timer.
  void fired();

private:
  void poll();
  void fire();

  PlaybackController *m_playback;
  QTimer m_timer;
  qint64 m_deadlineMs{0};
  int m_minutes{0};
  bool m_endOfTrack{false};
  QString m_trackId;
};
