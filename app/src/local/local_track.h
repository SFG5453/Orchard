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

#include <QString>
#include <QVariantMap>

// Everything a file on disk becomes once Orchard adopts it. The shapes mirror
// the YouTube track and playlist maps so QML rows do not need two code paths.
namespace local {

inline const QString kTrackPrefix = QStringLiteral("local:");
inline const QString kPlaylistPrefix = QStringLiteral("local-playlist:");

inline bool isLocalTrackId(const QString &id) { return id.startsWith(kTrackPrefix); }
inline bool isLocalPlaylistId(const QString &id) { return id.startsWith(kPlaylistPrefix); }

// A track map straight from QML: the id prefix is the source of truth, the
// "source" key is a courtesy for people reading the JSON.
inline bool isLocalTrack(const QVariantMap &track) {
  return isLocalTrackId(track.value(QStringLiteral("id")).toString());
}

// Stable across restarts and moves of the library file, because it only
// depends on where the song lives. Rename the file and it is a new song,
// which is also how humans treat it.
QString trackIdForPath(const QString &absolutePath);

// "3:07" or "1:02:03", whatever the length deserves.
QString formatDuration(double seconds);

} // namespace local
