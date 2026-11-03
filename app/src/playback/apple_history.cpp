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

#include "apple_history.h"

#include "integrations/apple_music.h"
#include "playback_controller.h"

#include <QUuid>
#include <cmath>

namespace {
// A song counts as finished when playback ends this close to its duration.
constexpr double kNaturalEndSeconds = 3.0;
constexpr int kPauseEndMs = 3000;

QString songId(const QVariantMap &track) {
  const QString id = track.value(QStringLiteral("appleId")).toString();
  if (!id.isEmpty())
    return id;
  const QString browseId = track.value(QStringLiteral("id")).toString();
  const QString prefix = QStringLiteral("am.songs.");
  return browseId.startsWith(prefix) ? browseId.mid(prefix.size()) : QString();
}

qint64 millis(double seconds) { return std::llround(seconds * 1000.0); }
} // namespace

AppleHistory::AppleHistory(PlaybackController *playback, AppleMusic *account)
    : QObject(playback), m_playback(playback), m_account(account) {
  m_pauseTimer.setSingleShot(true);
  m_pauseTimer.setInterval(kPauseEndMs);
  connect(&m_pauseTimer, &QTimer::timeout, this, [this] {
    if (m_id.isEmpty())
      return;
    finish(QStringLiteral("paused"));
    m_id.clear();
  });
  connect(playback, &PlaybackController::stateChanged, this, &AppleHistory::update);
}

void AppleHistory::update() {
  const QString id = songId(m_playback->track());
  if (id != m_id) {
    // Gapless and crossfade swap tracks without stopping, so close the old play here.
    if (!m_id.isEmpty())
      finish(m_duration > 0 && m_position >= m_duration - kNaturalEndSeconds ? QStringLiteral("natural")
                                                                                : QStringLiteral("skipped"));
    m_id.clear();
  }
  const double position = m_playback->position();
  if (m_id.isEmpty()) {
    if (!id.isEmpty() && m_playback->playing() && position > 0 && m_account->playHistoryEnabled())
      begin(id, position, m_playback->duration());
    return;
  }
  if (m_playback->playing()) {
    m_pauseTimer.stop();
    m_position = std::max(m_position, position);
  } else if (!m_pauseTimer.isActive()) {
    m_pauseTimer.start();
  }
  m_duration = m_playback->duration();
}

void AppleHistory::begin(const QString &id, double position, double duration) {
  m_id = id;
  m_persistentId = QUuid::createUuid().toString(QUuid::WithoutBraces);
  m_start = position;
  m_position = position;
  m_duration = duration;
  m_account->reportPlay(QStringLiteral("history.start"),
                        {{"id", m_id},
                         {"persistentId", m_persistentId},
                         {"durationMs", double(millis(duration))},
                         {"startPositionMs", double(millis(position))},
                         {"shuffle", m_playback->shuffleEnabled()}});
}

void AppleHistory::finish(const QString &reason) {
  const bool natural = reason == QStringLiteral("natural");
  if (!m_account->playHistoryEnabled())
    return;
  m_account->reportPlay(QStringLiteral("history.end"),
                        {{"id", m_id},
                         {"persistentId", m_persistentId},
                         {"durationMs", double(millis(m_duration))},
                         {"startPositionMs", double(millis(m_start))},
                         {"endPositionMs", double(millis(natural ? m_duration : m_position))},
                         {"reason", reason},
                         {"shuffle", m_playback->shuffleEnabled()}});
}
