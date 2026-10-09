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

namespace local {

// Turns user-supplied lyrics into the map LyricsController publishes:
// { status, mode: "synced"|"unsynced", source: "local", lines: [{startTime, endTime?, text}] }.
// Understands LRC ([mm:ss.xx], several stamps per line, [offset:]), SRT, and
// plain text. Empty or unreadable input yields status "unavailable".
QVariantMap parseLyrics(const QString &text);

// Reads a file as UTF-8 (BOM tolerated), falling back to Latin-1 for the
// notepad files of yesteryear, then parses it.
QVariantMap parseLyricsFile(const QString &path);

} // namespace local
