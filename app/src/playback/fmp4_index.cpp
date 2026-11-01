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
#include "fmp4_index.h"

#include <QRegularExpression>
#include <QtEndian>
#include <cmath>
#include <optional>

namespace fmp4index {
namespace {

struct Box {
  QByteArray type;
  qint64 payload{0};
  qint64 end{0};
};

quint32 u32(const QByteArray &data, qint64 at) {
  return qFromBigEndian<quint32>(data.constData() + at);
}

quint64 u64(const QByteArray &data, qint64 at) {
  return qFromBigEndian<quint64>(data.constData() + at);
}

// Children of the box spanning [from, to); stops at the first malformed header.
QList<Box> children(const QByteArray &data, qint64 from, qint64 to) {
  QList<Box> boxes;
  to = qMin(to, qint64(data.size()));
  while (from + 8 <= to) {
    qint64 size = u32(data, from);
    qint64 header = 8;
    if (size == 1) {
      if (from + 16 > to)
        break;
      size = qint64(u64(data, from + 8));
      header = 16;
    } else if (size == 0) {
      size = to - from;
    }
    if (size < header || from + size > to)
      break;
    boxes.append({data.mid(from + 4, 4), from + header, from + size});
    from += size;
  }
  return boxes;
}

std::optional<Box> child(const QByteArray &data, const Box &parent, const char *type) {
  for (const Box &box : children(data, parent.payload, parent.end))
    if (box.type == type)
      return box;
  return std::nullopt;
}

// Reads a v0/v1 full-box field that is 32 bits at `v0` or 64 bits at `v1` past the version byte.
std::optional<quint64> versioned(const QByteArray &data, const Box &box, int v0, int v1) {
  if (box.end - box.payload < 4)
    return std::nullopt;
  const bool wide = data.at(box.payload) == 1;
  const qint64 at = box.payload + (wide ? v1 : v0);
  if (at + (wide ? 8 : 4) > box.end)
    return std::nullopt;
  return wide ? u64(data, at) : u32(data, at);
}

void append32(QByteArray &out, quint32 value) {
  char bytes[4];
  qToBigEndian(value, bytes);
  out.append(bytes, 4);
}

void append64(QByteArray &out, quint64 value) {
  char bytes[8];
  qToBigEndian(value, bytes);
  out.append(bytes, 8);
}

} // namespace

qint64 Layout::fileLength() const {
  return segments.isEmpty() ? 0 : segments.last().offset + segments.last().size;
}

Layout parsePlaylist(const QString &playlist) {
  static const QRegularExpression mapPattern(QStringLiteral(R"re(^#EXT-X-MAP:.*URI="([^"]+)".*BYTERANGE="(\d+)@0")re"));
  static const QRegularExpression rangePattern(QStringLiteral(R"(^#EXT-X-BYTERANGE:(\d+)(?:@(\d+))?$)"));
  Layout layout;
  QString file;
  qint64 next = 0;
  double duration = -1;
  qint64 size = -1;
  bool ended = false;
  for (const QString &raw : playlist.split(QLatin1Char('\n'))) {
    const QString line = raw.trimmed();
    if (line.isEmpty())
      continue;
    if (line.startsWith(QStringLiteral("#EXT-X-MAP:"))) {
      const auto match = mapPattern.match(line);
      if (!match.hasMatch() || layout.initLength)
        return {};
      file = match.captured(1);
      layout.initLength = next = match.captured(2).toLongLong();
    } else if (line.startsWith(QStringLiteral("#EXTINF:"))) {
      duration = line.mid(8).section(QLatin1Char(','), 0, 0).toDouble();
    } else if (line.startsWith(QStringLiteral("#EXT-X-BYTERANGE:"))) {
      const auto match = rangePattern.match(line);
      if (!match.hasMatch())
        return {};
      size = match.captured(1).toLongLong();
      // A range without an offset continues the previous one.
      if (!match.captured(2).isEmpty() && match.captured(2).toLongLong() != next)
        return {};
    } else if (line == QStringLiteral("#EXT-X-ENDLIST")) {
      ended = true;
    } else if (line.startsWith(QStringLiteral("#EXT-X-KEY:")) && !line.contains(QStringLiteral("METHOD=NONE"))) {
      return {};
    } else if (!line.startsWith(QLatin1Char('#'))) {
      if (line != file || duration <= 0 || size <= 0)
        return {};
      layout.segments.append({next, size, duration});
      next += size;
      duration = -1;
      size = -1;
    }
  }
  return ended && layout.isValid() ? layout : Layout{};
}

QByteArray buildSidx(const QByteArray &head, const Layout &layout) {
  if (!layout.isValid() || head.size() <= layout.initLength)
    return {};
  const Box file{{}, 0, layout.initLength};
  const auto moov = child(head, file, "moov");
  if (!moov || !child(head, *moov, "mvex"))
    return {};
  QList<Box> tracks;
  for (const Box &box : children(head, moov->payload, moov->end))
    if (box.type == "trak")
      tracks.append(box);
  if (tracks.size() != 1)
    return {};
  const auto tkhd = child(head, tracks.first(), "tkhd");
  const auto mdia = child(head, tracks.first(), "mdia");
  const auto mdhd = mdia ? child(head, *mdia, "mdhd") : std::nullopt;
  const auto trackId = tkhd ? versioned(head, *tkhd, 12, 20) : std::nullopt;
  const auto timescale = mdhd ? versioned(head, *mdhd, 12, 20) : std::nullopt;
  if (!trackId || !timescale || *timescale == 0)
    return {};

  const auto fragments = children(head, layout.initLength, head.size());
  if (fragments.isEmpty() || fragments.first().type != "moof")
    return {};
  const auto traf = child(head, fragments.first(), "traf");
  const auto tfhd = traf ? child(head, *traf, "tfhd") : std::nullopt;
  const auto tfdt = traf ? child(head, *traf, "tfdt") : std::nullopt;
  // Absolute data offsets would break once the sidx shifts the fragments.
  if (!tfhd || !tfdt || tfhd->end - tfhd->payload < 4 || (u32(head, tfhd->payload) & 0x1))
    return {};
  const auto earliest = versioned(head, *tfdt, 4, 4);
  if (!earliest)
    return {};

  QByteArray body;
  append32(body, 0x01000000);
  append32(body, quint32(*trackId));
  append32(body, quint32(*timescale));
  append64(body, *earliest);
  append64(body, 0);
  append32(body, quint32(layout.segments.size()));
  // Rounding the running total keeps long loops from drifting.
  double elapsed = 0;
  qint64 previous = 0;
  for (const Segment &segment : layout.segments) {
    if (segment.size > 0x7fffffff)
      return {};
    elapsed += segment.duration;
    const qint64 tick = std::llround(elapsed * double(*timescale));
    append32(body, quint32(segment.size));
    append32(body, quint32(tick - previous));
    append32(body, 0x90000000); // Starts with a type 1 SAP.
    previous = tick;
  }
  QByteArray box;
  append32(box, quint32(8 + body.size()));
  box.append("sidx", 4);
  return box + body;
}

} // namespace fmp4index
