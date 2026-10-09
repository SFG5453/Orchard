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

#include "sleep_timer.h"

#include "playback_controller.h"

#include <QDateTime>

namespace {
constexpr int kPollMs = 250;
// Pause just before the boundary so the next track never starts audibly.
constexpr double kTailSeconds = 0.3;
} // namespace

SleepTimer::SleepTimer(PlaybackController *playback, QObject *parent)
    : QObject(parent), m_playback(playback) {
  m_timer.setInterval(kPollMs);
  connect(&m_timer, &QTimer::timeout, this, &SleepTimer::poll);
}

int SleepTimer::remainingSeconds() const {
  if (m_minutes <= 0)
    return 0;
  const qint64 left = m_deadlineMs - QDateTime::currentMSecsSinceEpoch();
  return left > 0 ? static_cast<int>((left + 999) / 1000) : 0;
}

void SleepTimer::startMinutes(int minutes) {
  if (minutes <= 0)
    return cancel();
  m_endOfTrack = false;
  m_minutes = minutes;
  m_deadlineMs = QDateTime::currentMSecsSinceEpoch() + qint64(minutes) * 60000;
  m_timer.start();
  emit changed();
  emit tick();
}

void SleepTimer::startEndOfTrack() {
  m_minutes = 0;
  m_endOfTrack = true;
  m_trackId = m_playback->shownTrack().value(QStringLiteral("id")).toString();
  m_timer.start();
  emit changed();
  emit tick();
}

void SleepTimer::cancel() {
  if (!active())
    return;
  m_timer.stop();
  m_minutes = 0;
  m_endOfTrack = false;
  emit changed();
  emit tick();
}

void SleepTimer::poll() {
  if (m_endOfTrack) {
    const double duration = m_playback->shownDisplayDuration();
    const double left = duration - m_playback->shownDisplayPosition();
    const bool moved = m_playback->shownTrack().value(QStringLiteral("id")).toString() != m_trackId;
    if (moved || (duration > 0 && left <= kTailSeconds))
      fire();
    return;
  }
  emit tick();
  if (remainingSeconds() <= 0)
    fire();
}

void SleepTimer::fire() {
  m_playback->pause();
  cancel();
  emit fired();
}
