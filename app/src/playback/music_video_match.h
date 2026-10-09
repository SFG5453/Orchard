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

namespace musicvideo {

// Video id the track already is (an OMV or UGC row), or empty.
QString directVideoId(const QVariantMap &track);

// Search query that finds the track's video counterpart.
QString searchQuery(const QVariantMap &track);

// "videos" items from a catalog.search result.
QJsonArray videoCandidates(const QJsonObject &searchResult);

// Best same-recording video, or empty. Mirrors the mobile VideoVersionResolver.
QString bestVideoId(const QVariantMap &track, const QJsonArray &candidates);

// Lowercased title without "(Official Video)" style suffixes; exposed for tests.
QString normalizedTitle(const QString &title);

} // namespace musicvideo
