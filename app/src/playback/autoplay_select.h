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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include <QList>
#include <QVariantMap>

namespace autoplay {

// True when both rows are the same recording (same id, or matching title, artist and length).
bool isSameSong(const QVariantMap &a, const QVariantMap &b);

// Candidates to append: drops songs already in `existing` and collapses
// alternate cuts, keeping the album version. At most `limit` entries.
QList<QVariantMap> select(const QList<QVariantMap> &existing, const QList<QVariantMap> &candidates, int limit);

// True for YouTube Music generated mixes (Supermix, My Mix, radio), which repeat songs as videos.
bool isGeneratedMix(const QString &playlistId);

// Rows of a generated mix with alternate cuts collapsed. `pinned` leading rows stay as given.
QVariantList collapseMix(const QVariantList &tracks, const QList<QVariantMap> &existing, int pinned);

// Indices of autoplayGenerated rows that repeat an earlier row.
QList<int> duplicateGeneratedIndices(const QList<QVariantMap> &queue);

} // namespace autoplay
