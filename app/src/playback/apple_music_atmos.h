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

#include <QByteArray>
#include <QList>
#include <QString>
#include <QUrl>

// Apple Music's Dolby Atmos renditions: E-AC-3 with JOC objects in the enhanced HLS master.
namespace apple_music_atmos {

struct Rendition {
  QUrl url;
  int bitrateKbps{0};
};

// The first JOC rendition in Apple's preference order; an empty url when there is none.
Rendition pick(const QUrl &masterUrl, const QByteArray &master);

// The key id a Widevine pssh box names, or empty.
QByteArray psshKeyId(const QByteArray &pssh);

// Apple leaves tenc's key ids zero and keys each sample description with the playlist key
// at the same position. Writes those ids into `init`; returns an error or an empty string.
QString keyInit(QByteArray &init, const QList<QByteArray> &keyIds);

} // namespace apple_music_atmos
