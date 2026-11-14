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

#include "local_store.h"

#include <QVariantList>
#include <QVariantMap>

// Albums derived from tags. Nothing is stored: they are rebuilt from the tracks.
namespace local {

inline const QString kAlbumPrefix = QStringLiteral("local-album:");

inline bool isLocalAlbumId(const QString &id) { return id.startsWith(kAlbumPrefix); }

// Album tag plus album artist, or plus the folder when there is no album
// artist so compilations stay whole. Empty for songs without an album tag.
QString albumIdFor(const TrackRecord &track);

// Cards for the Library grid, sorted by artist then title.
QVariantList albumsToVariant(const LocalStore &store);

// Read-only detail in the shape PlaylistView reads; empty for an unknown id.
QVariantMap albumDetail(const QString &albumId, const LocalStore &store);

} // namespace local
