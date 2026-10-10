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

// Played songs and the Up next / Continuous queue layout.

#include "auth/auth_manager.h"
#include "playback_controller.h"
#include <QSettings>

namespace {
constexpr auto kLayoutKey = "playback/queueLayout";
constexpr qsizetype kHistoryLimit = 50;
}

QVariantList PlaybackController::shownHistory() const {
  return remoteActive() ? QVariantList{} : m_history;
}

void PlaybackController::pushHistory(const QVariantMap &track) {
  m_history.append(track);
  if (m_history.size() > kHistoryLimit)
    m_history.removeFirst();
  emit historyChanged();
}

void PlaybackController::setQueueLayout(const QString &layout) {
  const QString next = layout == QLatin1String("continuous") ? layout : QStringLiteral("upNext");
  if (m_queueLayout == next)
    return;
  m_queueLayout = next;
  QSettings().setValue(QLatin1String(kLayoutKey), next);
  emit queueLayoutChanged();
}

void PlaybackController::playHistoryIndex(int index) {
  if (remoteActive() || m_loading || !m_auth->isSignedIn() || index < 0 || index >= m_history.size())
    return;
  const QVariantMap selected = m_history.at(index).toMap();
  // Songs after the target, then the current one, return to the front in play order.
  QVariantList requeued = m_history.mid(index + 1);
  if (!m_track.isEmpty())
    requeued.append(m_track);
  const qsizetype rewound = m_history.size() - index;
  m_history = m_history.mid(0, index);
  m_queue = requeued + m_queue;
  m_orderedQueue = requeued + m_orderedQueue;
  for (qsizetype i = 0; i < rewound && !m_cyclePlayed.isEmpty(); ++i)
    m_cyclePlayed.removeLast();
  emit historyChanged();
  emit queueChanged();
  m_suppressHistory = true;
  startTrack(selected);
  m_suppressHistory = false;
}
