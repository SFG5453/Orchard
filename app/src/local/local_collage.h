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

#include <QImage>
#include <QString>
#include <QStringList>

namespace local {

// Cover for a playlist nobody drew one for: the first four distinct covers in
// a 2x2 grid, YouTube Music style. One cover fills the square, two split it,
// three repeat the first in the last cell. No covers, no image.
QImage buildCollage(const QList<QImage> &covers, int side = 640);

// Distinct, existing image paths in order, capped at four.
QStringList collageSources(const QStringList &coverPaths);

// Short fingerprint of the sources, so a changed playlist gets a new file name
// and QML's image cache cannot serve yesterday's collage.
QString collageKey(const QStringList &sources);

// Builds the collage from files and saves it as a JPEG. False when there was
// nothing to stitch together.
bool writeCollage(const QStringList &coverPaths, const QString &outFile, int side = 640);

} // namespace local
