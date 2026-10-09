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

#include <QSet>

namespace local {

// Blocking work the library runs off the GUI thread. Nothing in here touches
// the store, so it is safe on the pool and easy to test with a temp folder.

struct ImportResult {
  QList<TrackRecord> records; // Newly probed files only.
  QStringList ids;            // Every accepted file in input order, new or known.
  int rejected{0};            // Not audio, unreadable, or ffprobe said no.
};

// Expands folders, drops non-audio, probes new files and extracts their covers.
// `known` holds ids already in the library, which are not probed again.
ImportResult importPaths(const QStringList &paths, const QSet<QString> &known, const QString &coversDir);

struct CoverResult {
  bool ok{false};
  QString still;    // JPEG or the original image.
  QString animated; // Looping video for GIF and video covers, else empty.
  QString error;
};

// Copies a user's picture into the store. `base` names the output files; a
// timestamp is appended so replacing a cover also replaces QML's cached image.
CoverResult ingestCover(const QString &source, const QString &base, const QString &coversDir,
                        const QString &animatedDir);

} // namespace local
