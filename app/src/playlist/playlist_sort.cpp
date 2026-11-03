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

#include "playlist_controller.h"

#include <QCollator>
#include <algorithm>
#include <numeric>

namespace {
bool isSortKey(const QString &key) {
  return key.isEmpty() || key == QStringLiteral("title") ||
         key == QStringLiteral("artist") || key == QStringLiteral("album") ||
         key == QStringLiteral("duration");
}

QString sortText(const QVariantMap &track, const QString &key) {
  if (key != QStringLiteral("artist"))
    return track.value(key).toString().trimmed();
  const QString artist = track.value(QStringLiteral("artist")).toString();
  return (artist.trimmed().isEmpty()
              ? track.value(QStringLiteral("artists")).toStringList().join(
                    QStringLiteral(", "))
              : artist)
      .trimmed();
}
} // namespace

void PlaylistController::setSort(const QString &key, bool descending) {
  if (!isSortKey(key))
    return;
  // Playlist order has no direction worth flipping.
  descending = descending && !key.isEmpty();
  if (key == m_sortKey && descending == m_sortDescending)
    return;
  m_sortKey = key;
  m_sortDescending = descending;
  emit sortChanged();
  if (!m_detail.isEmpty()) {
    applySort();
    emit detailChanged();
  }
}

void PlaylistController::resetSort() {
  if (m_sortKey.isEmpty() && !m_sortDescending)
    return;
  m_sortKey.clear();
  m_sortDescending = false;
  emit sortChanged();
}

void PlaylistController::applySort() {
  m_detail.insert(QStringLiteral("tracks"), sortedTracks(m_sourceTracks));
  if (!m_heldDetail.isEmpty())
    m_heldDetail.insert(QStringLiteral("tracks"), sortedTracks(m_heldTracks));
}

QVariantList PlaylistController::sortedTracks(const QVariantList &source) const {
  if (m_sortKey.isEmpty())
    return source;

  const auto count = source.size();
  const bool byDuration = m_sortKey == QStringLiteral("duration");
  QList<QString> texts(byDuration ? 0 : count);
  QList<double> seconds(byDuration ? count : 0);
  for (qsizetype index = 0; index < count; ++index) {
    const QVariantMap track = source.at(index).toMap();
    if (byDuration)
      seconds[index] =
          track.value(QStringLiteral("durationSeconds")).toDouble();
    else
      texts[index] = sortText(track, m_sortKey);
  }

  QCollator collator;
  collator.setCaseSensitivity(Qt::CaseInsensitive);
  // "Track 2" before "Track 10", as nature intended.
  collator.setNumericMode(true);

  QList<qsizetype> order(count);
  std::iota(order.begin(), order.end(), 0);
  // Stable so ties keep playlist order; blanks sink in both directions.
  std::stable_sort(order.begin(), order.end(), [&](qsizetype a, qsizetype b) {
    const bool missingA = byDuration ? seconds[a] <= 0 : texts[a].isEmpty();
    const bool missingB = byDuration ? seconds[b] <= 0 : texts[b].isEmpty();
    if (missingA || missingB)
      return !missingA && missingB;
    const int compared =
        byDuration ? (seconds[a] > seconds[b]) - (seconds[a] < seconds[b])
                   : collator.compare(texts[a], texts[b]);
    return m_sortDescending ? compared > 0 : compared < 0;
  });

  QVariantList sorted;
  sorted.reserve(count);
  for (const qsizetype index : order)
    sorted.append(source.at(index));
  return sorted;
}
