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

#include <QByteArray>
#include <QList>
#include <QString>

// Index for single-file fragmented MP4s that HLS addresses by byte range.
// Without a sidx, FFmpeg visits every moof over HTTP before the first frame.
namespace fmp4index {

struct Segment {
  qint64 offset{0};
  qint64 size{0};
  double duration{0};
};

struct Layout {
  qint64 initLength{0};
  QList<Segment> segments;
  [[nodiscard]] bool isValid() const { return initLength > 0 && !segments.isEmpty(); }
  [[nodiscard]] qint64 fileLength() const;
};

// Layout of a VOD media playlist whose init and segments tile one file from byte 0; invalid otherwise.
Layout parsePlaylist(const QString &playlist);

// sidx for `layout`, given the init segment followed by at least the first moof. Empty when unsupported.
QByteArray buildSidx(const QByteArray &head, const Layout &layout);

} // namespace fmp4index
