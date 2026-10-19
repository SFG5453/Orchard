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

#include <QRandomGenerator>
#include <QVariantList>

// Shared by the PlaybackController translation units.
// Three helpers, one header, zero custody disputes.
namespace {
inline bool isAppleTrack(const QVariantMap &track) {
  return track.value(QStringLiteral("source")).toString() == QStringLiteral("apple_music") ||
         track.value(QStringLiteral("playbackSource")).toString() == QStringLiteral("apple_music") ||
         track.value(QStringLiteral("id")).toString().startsWith(QStringLiteral("am."));
}
// Fisher–Yates: every remaining song gets a fair ticket to the dance floor.
inline void shuffleTracks(QVariantList &tracks, int first = 0) {
  for (int i = tracks.size() - 1; i > first; --i)
    tracks.swapItemsAt(
        i, first + QRandomGenerator::global()->bounded(i - first + 1));
}
// Autoplay may append while Best Mix runs; any edit inside the captured prefix invalidates it.
inline bool queueExtendsSnapshot(const QVariantList &queue, const QVariantList &snapshot) {
  return queue.size() >= snapshot.size() && queue.mid(0, snapshot.size()) == snapshot;
}
inline bool retainedBestMixOrder(const QVariantList &current, const QVariantList &sorted) {
  int cursor = 0;
  bool appended = false;
  for (const auto &track : current) {
    int found = -1;
    for (int i = cursor; i < sorted.size(); ++i) {
      if (sorted.at(i) == track) { found = i; break; }
    }
    if (found >= 0) {
      if (appended) return false;
      cursor = found + 1;
    } else {
      if (sorted.contains(track)) return false;
      appended = true;
    }
  }
  return true;
}
inline bool isAlbumPlaythrough(const QVariantMap &current, const QVariantMap &next) {
  const QVariantMap origin = current.value(QStringLiteral("queueOrigin")).toMap();
  const QVariantMap nextOrigin = next.value(QStringLiteral("queueOrigin")).toMap();
  const QString album = QStringLiteral("album");
  if (origin.value(QStringLiteral("kind")) != album ||
      nextOrigin.value(QStringLiteral("kind")) != album)
    return false;
  const QString title = origin.value(QStringLiteral("title")).toString();
  if (title.isEmpty() || title != nextOrigin.value(QStringLiteral("title")).toString() ||
      origin.value(QStringLiteral("artist")).toString() !=
          nextOrigin.value(QStringLiteral("artist")).toString())
    return false;
  bool currentOk = false;
  bool nextOk = false;
  const int currentIndex =
      current.value(QStringLiteral("index")).toString().trimmed().toInt(&currentOk);
  const int nextIndex =
      next.value(QStringLiteral("index")).toString().trimmed().toInt(&nextOk);
  // Missing track numbers leave the shared album origin as the best evidence.
  if (!currentOk || !nextOk || currentIndex <= 0 || nextIndex <= 0)
    return true;
  return nextIndex == currentIndex + 1;
}
inline bool historyDebug() {
  static const bool enabled = qEnvironmentVariableIsSet("ORCHARD_HISTORY_DEBUG");
  return enabled;
}
} // namespace
