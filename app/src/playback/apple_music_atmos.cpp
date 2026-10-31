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

#include "apple_music_atmos.h"

#include <QRegularExpression>
#include <QSet>
#include <QStringList>
#include <QtEndian>

namespace apple_music_atmos {

Rendition pick(const QUrl &masterUrl, const QByteArray &master) {
  const QStringList lines = QString::fromUtf8(master).split(QLatin1Char('\n'));
  static const QRegularExpression group(QStringLiteral(R"re(GROUP-ID="([^"]+)")re"));
  QSet<QString> groups;
  for (const QString &raw : lines) {
    const QString line = raw.trimmed();
    if (line.startsWith(QStringLiteral("#EXT-X-MEDIA:")) && line.contains(QStringLiteral("16/JOC")))
      if (const auto match = group.match(line); match.hasMatch())
        groups.insert(match.captured(1));
  }
  static const QRegularExpression bandwidth(QStringLiteral(R"((?:^|,)(?:AVERAGE-)?BANDWIDTH=(\d+))"));
  for (qsizetype i = 0; i + 1 < lines.size(); ++i) {
    const QString line = lines.at(i).trimmed();
    if (!line.startsWith(QStringLiteral("#EXT-X-STREAM-INF:")))
      continue;
    const QString attributes = line.mid(18);
    bool joc = false;
    for (const QString &name : std::as_const(groups))
      joc = joc || attributes.contains(QStringLiteral("AUDIO=\"%1\"").arg(name));
    const QString next = lines.at(i + 1).trimmed();
    if (!joc || next.isEmpty() || next.startsWith(QLatin1Char('#')))
      continue;
    const auto rate = bandwidth.match(attributes);
    return {masterUrl.resolved(QUrl(next)), rate.hasMatch() ? int(rate.captured(1).toLongLong() / 1000) : 0};
  }
  return {};
}

QByteArray psshKeyId(const QByteArray &pssh) {
  if (pssh.size() < 32 || pssh.mid(4, 4) != "pssh")
    return {};
  if (pssh.at(8) == 1 && pssh.size() >= 52 && qFromBigEndian<quint32>(pssh.constData() + 28) > 0)
    return pssh.mid(32, 16);
  // WidevinePsshData: field 2 (key_id) is a length-delimited 16-byte id.
  const QByteArray data = pssh.mid(32);
  for (qsizetype at = 0; at < data.size();) {
    const quint8 tag = quint8(data.at(at++));
    if ((tag & 7) == 2) {
      if (at >= data.size())
        return {};
      const int length = quint8(data.at(at++));
      if ((tag >> 3) == 2 && length == 16 && at + 16 <= data.size())
        return data.mid(at, 16);
      at += length;
    } else if ((tag & 7) == 0) {
      while (at < data.size() && (quint8(data.at(at)) & 0x80))
        ++at;
      ++at;
    } else {
      return {};
    }
  }
  return {};
}

QString keyInit(QByteArray &init, const QList<QByteArray> &keyIds) {
  QList<qsizetype> offsets;
  for (qsizetype at = init.indexOf("tenc"); at >= 4; at = init.indexOf("tenc", at + 4)) {
    const quint32 size = qFromBigEndian<quint32>(init.constData() + at - 4);
    if (size < 32 || at - 4 + qsizetype(size) > init.size())
      return QStringLiteral("malformed tenc box");
    // Version and flags, two reserved or pattern bytes, isProtected, per-sample IV size.
    offsets.append(at + 4 + 8);
  }
  if (offsets.size() != keyIds.size())
    return QStringLiteral("the init segment has %1 keyed descriptions for %2 keys").arg(offsets.size()).arg(keyIds.size());
  for (qsizetype i = 0; i < offsets.size(); ++i) {
    const QByteArray &keyId = keyIds.at(i);
    const QByteArray current = init.mid(offsets.at(i), 16);
    if (keyId.size() != 16 || (current != QByteArray(16, '\0') && current != keyId))
      return QStringLiteral("unexpected Atmos key layout");
    init.replace(offsets.at(i), 16, keyId);
  }
  return {};
}

} // namespace apple_music_atmos
