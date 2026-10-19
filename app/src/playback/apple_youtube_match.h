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

#include <QJsonArray>
#include <QJsonObject>
#include <QString>
#include <QVariantMap>

// Finds the YouTube Music upload of an Apple Music song, so adaptive mix can analyse
// audio Orchard is allowed to decode.
namespace appleyt {

// Search text for a catalog.search "songs" lookup.
QString searchQuery(const QVariantMap &appleSong);

// "songs" items from a catalog.search result.
QJsonArray songCandidates(const QJsonObject &searchResult);

// Score for one candidate, or a negative value when it is a different recording.
double score(const QVariantMap &appleSong, const QVariantMap &candidate);

// Video id of the best same-recording candidate, or empty.
QString bestVideoId(const QVariantMap &appleSong, const QJsonArray &candidates);

// "1.2M plays" style counts; 0 when unreadable. Exposed for tests.
double playCount(const QString &text);

} // namespace appleyt
