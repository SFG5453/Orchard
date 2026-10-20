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

#include "apple_music_playlist.h"

#include <QHash>
#include <QRegularExpression>
#include <QtEndian>

#include <cstring>

namespace apple_music_playlist {
namespace {

const QByteArray kWidevineSystem = QByteArray::fromHex("edef8ba979d64acea3c827dcd51d21ed");

QHash<QString, QString> attributes(const QString &list) {
  static const QRegularExpression pattern(QStringLiteral(R"(([A-Z0-9-]+)=("[^"]*"|[^,]*))"));
  QHash<QString, QString> found;
  for (auto match = pattern.globalMatch(list); match.hasNext();) {
    const auto next = match.next();
    QString value = next.captured(2);
    if (value.startsWith(QLatin1Char('"')))
      value = value.mid(1, value.size() - 2);
    found.insert(next.captured(1), value);
  }
  return found;
}

// "length@offset"; without an offset the range follows the previous one.
bool byteRange(const QString &text, qint64 follows, QPair<qint64, qint64> &range) {
  const QStringList parts = text.split(QLatin1Char('@'));
  bool sizeValid = false, offsetValid = true;
  const qint64 size = parts.at(0).toLongLong(&sizeValid);
  const qint64 offset = parts.size() > 1 ? parts.at(1).toLongLong(&offsetValid) : follows;
  if (!sizeValid || !offsetValid || size <= 0 || offset < 0 || parts.size() > 2)
    return false;
  range = {offset, size};
  return true;
}

QByteArray psshFromKeyUri(const QString &uri) {
  const qsizetype comma = uri.indexOf(QLatin1Char(','));
  if (!uri.startsWith(QStringLiteral("data:")) || comma < 0)
    return {};
  const QByteArray data = QByteArray::fromBase64(uri.mid(comma + 1).toLatin1());
  if (data.size() == 16)
    return widevinePssh(data);
  if (data.size() >= 32 && data.mid(4, 4) == "pssh" && data.mid(12, 16) == kWidevineSystem)
    return data;
  return {};
}

} // namespace

qint64 Playlist::servedSize() const {
  return ranges.isEmpty() ? 0 : ranges.last().first + ranges.last().second + indexBytes();
}

QPair<qint64, qint64> Playlist::served(int index) const {
  const auto [start, size] = ranges.at(index);
  return index == 0 ? QPair<qint64, qint64>{0, size + indexBytes()} : QPair<qint64, qint64>{start + indexBytes(), size};
}

int Playlist::indexAt(qint64 offset) const {
  for (int i = 0; i < ranges.size(); ++i) {
    const auto [start, size] = served(i);
    if (offset >= start && offset < start + size)
      return i;
  }
  return -1;
}

QByteArray Playlist::segmentIndex(const QByteArray &init) const {
  // Track id from tkhd and timescale from mdhd; both sit after creation and
  // modification times whose width depends on the box version.
  const auto field = [&init](const char *type, quint32 &value) {
    const qsizetype at = init.indexOf(type);
    if (at < 4)
      return false;
    const qsizetype offset = at + (init.at(at + 4) == 1 ? 24 : 16);
    if (offset + 4 > init.size())
      return false;
    value = qFromBigEndian<quint32>(init.constData() + offset);
    return value != 0;
  };
  quint32 trackId = 0, timescale = 0;
  if (!field("tkhd", trackId) || !field("mdhd", timescale))
    return {};
  QByteArray box(int(indexBytes()), '\0');
  char *p = box.data();
  qToBigEndian<quint32>(quint32(box.size()), p);
  std::memcpy(p + 4, "sidx", 4);
  qToBigEndian<quint32>(trackId, p + 12);
  qToBigEndian<quint32>(timescale, p + 16);
  // Version 0: earliest presentation time and first offset stay 0, so the first
  // fragment follows the index directly.
  qToBigEndian<quint16>(quint16(durations.size()), p + 30);
  double elapsed = 0.0;
  qint64 boundary = 0;
  for (int i = 0; i < durations.size(); ++i) {
    elapsed += durations.at(i);
    const qint64 next = qRound64(elapsed * timescale);
    char *reference = p + 32 + 12 * i;
    qToBigEndian<quint32>(quint32(ranges.at(i + 1).second) & 0x7fffffff, reference);
    qToBigEndian<quint32>(quint32(next - boundary), reference + 4);
    // Starts with a SAP of type 1: every AAC frame decodes on its own.
    qToBigEndian<quint32>(0x90000000, reference + 8);
    boundary = next;
  }
  return box;
}

QByteArray widevinePssh(const QByteArray &keyId) {
  // WidevinePsshData { key_ids: keyId }
  const QByteArray data = QByteArray("\x12\x10", 2) + keyId;
  QByteArray box(32, '\0');
  qToBigEndian<quint32>(quint32(32 + data.size()), box.data());
  box.replace(4, 4, "pssh");
  box.replace(12, 16, kWidevineSystem);
  qToBigEndian<quint32>(quint32(data.size()), box.data() + 28);
  return box + data;
}

QString parse(const QUrl &playlistUrl, const QByteArray &text, Playlist &playlist) {
  playlist = {};
  const QStringList lines = QString::fromUtf8(text).split(QLatin1Char('\n'));
  if (lines.isEmpty() || !lines.first().trimmed().startsWith(QStringLiteral("#EXTM3U")))
    return QStringLiteral("not an HLS playlist");
  bool pendingRange = false;
  double segmentDuration = 0.0;
  QPair<qint64, qint64> range;
  for (const QString &raw : lines) {
    const QString line = raw.trimmed();
    if (line.startsWith(QStringLiteral("#EXT-X-KEY:"))) {
      const auto key = attributes(line.mid(11));
      const QString format = key.value(QStringLiteral("KEYFORMAT"));
      const bool widevine = format.isEmpty() || format.contains(QStringLiteral("edef8ba9"), Qt::CaseInsensitive);
      if (widevine && playlist.pssh.isEmpty() && key.value(QStringLiteral("METHOD")) != QStringLiteral("NONE")) {
        playlist.pssh = psshFromKeyUri(key.value(QStringLiteral("URI")));
        if (!playlist.pssh.isEmpty())
          playlist.keyUri = key.value(QStringLiteral("URI"));
      }
    } else if (line.startsWith(QStringLiteral("#EXT-X-MAP:"))) {
      const auto map = attributes(line.mid(11));
      if (!playlist.ranges.isEmpty() || !byteRange(map.value(QStringLiteral("BYTERANGE")), 0, range))
        return QStringLiteral("init segment is not a byte range");
      playlist.media = playlistUrl.resolved(QUrl(map.value(QStringLiteral("URI"))));
      playlist.ranges.append(range);
    } else if (line.startsWith(QStringLiteral("#EXTINF:"))) {
      segmentDuration = line.mid(8).section(QLatin1Char(','), 0, 0).toDouble();
    } else if (line.startsWith(QStringLiteral("#EXT-X-BYTERANGE:"))) {
      const qint64 follows = playlist.ranges.isEmpty() ? 0 : playlist.ranges.last().first + playlist.ranges.last().second;
      if (!byteRange(line.mid(17), follows, range))
        return QStringLiteral("malformed byte range");
      pendingRange = true;
    } else if (!line.isEmpty() && !line.startsWith(QLatin1Char('#'))) {
      if (!pendingRange || playlist.ranges.isEmpty())
        return QStringLiteral("segment is not a byte range of the init file");
      if (playlistUrl.resolved(QUrl(line)) != playlist.media)
        return QStringLiteral("segments span more than one file");
      const auto &previous = playlist.ranges.last();
      if (range.first != previous.first + previous.second)
        return QStringLiteral("byte ranges leave a gap");
      playlist.ranges.append(range);
      playlist.durations.append(segmentDuration);
      playlist.duration += segmentDuration;
      segmentDuration = 0.0;
      pendingRange = false;
    }
  }
  if (playlist.pssh.isEmpty())
    return QStringLiteral("no Widevine key");
  if (playlist.ranges.size() < 2 || playlist.ranges.first().first != 0)
    return QStringLiteral("no media segments");
  return {};
}

} // namespace apple_music_playlist
