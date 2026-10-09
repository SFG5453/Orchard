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
// Fisher–Yates: every remaining song gets a fair ticket to the dance floor.
inline void shuffleTracks(QVariantList &tracks, int first = 0) {
  for (int i = tracks.size() - 1; i > first; --i)
    tracks.swapItemsAt(
        i, first + QRandomGenerator::global()->bounded(i - first + 1));
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
inline bool historyDebug() {
  static const bool enabled = qEnvironmentVariableIsSet("ORCHARD_HISTORY_DEBUG");
  return enabled;
}
} // namespace
