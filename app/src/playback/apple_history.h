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

#include <QJsonObject>
#include <QElapsedTimer>
#include <QObject>
#include <QString>
#include <QTimer>

class AppleMusic;
class PlaybackController;

// Reports Apple songs played on this desktop to Apple so they reach the listener's
// recents. One play runs from the first audible position to the song changing.
class AppleHistory final : public QObject {
  Q_OBJECT

public:
  AppleHistory(PlaybackController *playback, AppleMusic *account);

private:
  void update();
  void begin(const QString &id, double position, double duration);
  void finish(const QString &reason);

  PlaybackController *m_playback;
  AppleMusic *m_account;
  QString m_id;
  // Ends the play after a pause so a long pause is not counted as listening.
  QTimer m_pauseTimer;
  QString m_persistentId;
  QJsonObject m_container;
  QElapsedTimer m_elapsed;
  double m_start{0};
  double m_position{0};
  double m_duration{0};
};
