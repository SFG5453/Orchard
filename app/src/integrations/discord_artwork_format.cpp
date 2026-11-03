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

#include "discord_artwork.h"
#include "discord_artwork_internal.h"

#include "account/orchard_account.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QProcess>
#include <QSaveFile>
#include <QSize>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTimeZone>
#include <QTimer>
#include <QUrl>
#include <QUrlQuery>
#include <QtEndian>

#ifdef Q_OS_WIN
#include <windows.h>
#else
#include <unistd.h>
#endif

#include <utility>

using namespace discord_artwork;

namespace {
quint32 u24(const uchar *p) { return quint32(p[0]) | (quint32(p[1]) << 8) | (quint32(p[2]) << 16); }

struct Chunk {
  QByteArray id;
  qsizetype payload{0};
  qsizetype size{0};
};

// Walks RIFF chunks in [start, end). Empty result means malformed.
std::optional<QList<Chunk>> readChunks(const QByteArray &bytes, qsizetype start, qsizetype end) {
  QList<Chunk> chunks;
  qsizetype offset = start;
  while (offset < end) {
    if (offset + 8 > end)
      return std::nullopt;
    const auto *p = reinterpret_cast<const uchar *>(bytes.constData() + offset);
    const qsizetype size = qFromLittleEndian<quint32>(p + 4);
    if (offset + 8 + size > end)
      return std::nullopt;
    chunks.append({bytes.mid(offset, 4), offset + 8, size});
    offset += 8 + size + (size & 1);
  }
  return chunks;
}

std::optional<QSize> bitstreamSize(const QByteArray &bytes, const Chunk &chunk) {
  const auto *p = reinterpret_cast<const uchar *>(bytes.constData() + chunk.payload);
  if (chunk.id == "VP8 ") {
    if (chunk.size < 10 || p[3] != 0x9d || p[4] != 0x01 || p[5] != 0x2a)
      return std::nullopt;
    return QSize((p[6] | (p[7] << 8)) & 0x3fff, (p[8] | (p[9] << 8)) & 0x3fff);
  }
  if (chunk.id == "VP8L") {
    if (chunk.size < 5 || p[0] != 0x2f)
      return std::nullopt;
    const quint32 bits = qFromLittleEndian<quint32>(p + 1);
    return QSize(int(bits & 0x3fff) + 1, int((bits >> 14) & 0x3fff) + 1);
  }
  return std::nullopt;
}
} // namespace

std::optional<DiscordArtwork::WebpInfo> DiscordArtwork::parseWebp(const QByteArray &bytes) {
  if (bytes.size() < 20 || !bytes.startsWith("RIFF") || bytes.mid(8, 4) != "WEBP")
    return std::nullopt;
  const auto *data = reinterpret_cast<const uchar *>(bytes.constData());
  const qsizetype riffEnd = qsizetype(qFromLittleEndian<quint32>(data + 4)) + 8;
  if (riffEnd > bytes.size() || riffEnd < bytes.size() - 1)
    return std::nullopt;
  const auto chunks = readChunks(bytes, 12, riffEnd);
  if (!chunks || chunks->isEmpty())
    return std::nullopt;

  const Chunk &first = chunks->first();
  if (first.id == "VP8 " || first.id == "VP8L") {
    const auto size = bitstreamSize(bytes, first);
    if (!size)
      return std::nullopt;
    return WebpInfo{size->width(), size->height(), false, 1, 0};
  }
  if (first.id != "VP8X" || first.size < 10)
    return std::nullopt;

  const uchar *header = data + first.payload;
  WebpInfo info{int(u24(header + 4)) + 1, int(u24(header + 7)) + 1, (header[0] & 0x02) != 0, 0, 0};
  if (!info.animated) {
    for (const Chunk &chunk : *chunks) {
      if (const auto size = bitstreamSize(bytes, chunk)) {
        if (size->width() != info.width || size->height() != info.height)
          return std::nullopt;
        info.frames = 1;
        return info;
      }
    }
    return std::nullopt;
  }

  bool hasAnim = false;
  for (const Chunk &chunk : *chunks) {
    hasAnim = hasAnim || chunk.id == "ANIM";
    if (chunk.id != "ANMF")
      continue;
    if (chunk.size < 16)
      return std::nullopt;
    const uchar *frame = data + chunk.payload;
    const int x = int(u24(frame)) * 2;
    const int y = int(u24(frame + 3)) * 2;
    const int width = int(u24(frame + 6)) + 1;
    const int height = int(u24(frame + 9)) + 1;
    if (x + width > info.width || y + height > info.height)
      return std::nullopt;
    const auto inner = readChunks(bytes, chunk.payload + 16, chunk.payload + chunk.size);
    if (!inner)
      return std::nullopt;
    std::optional<QSize> size;
    for (const Chunk &sub : *inner) {
      if ((size = bitstreamSize(bytes, sub)))
        break;
    }
    if (!size || size->width() != width || size->height() != height)
      return std::nullopt;
    info.frames += 1;
    info.durationMs += u24(frame + 12);
  }
  if (!hasAnim || info.frames == 0)
    return std::nullopt;
  return info;
}

std::optional<DiscordArtwork::SourceInfo> DiscordArtwork::parseProbe(const QByteArray &json) {
  const QJsonArray streams = QJsonDocument::fromJson(json).object().value(QStringLiteral("streams")).toArray();
  if (streams.isEmpty())
    return std::nullopt;
  const QJsonObject stream = streams.first().toObject();
  const auto rate = [](const QString &text) {
    const QStringList parts = text.split(QLatin1Char('/'));
    const double numerator = parts.value(0).toDouble();
    const double denominator = parts.size() > 1 ? parts.value(1).toDouble() : 1.0;
    return denominator > 0.0 ? numerator / denominator : 0.0;
  };
  SourceInfo info;
  info.codec = stream.value(QStringLiteral("codec_name")).toString();
  info.width = stream.value(QStringLiteral("width")).toInt();
  info.height = stream.value(QStringLiteral("height")).toInt();
  info.fps = rate(stream.value(QStringLiteral("avg_frame_rate")).toString());
  if (info.fps <= 0.0)
    info.fps = rate(stream.value(QStringLiteral("r_frame_rate")).toString());
  info.frames = stream.value(QStringLiteral("nb_read_packets")).toString().toLongLong();
  return info;
}

QStringList DiscordArtwork::encodeArguments(const QString &input, const QString &output,
                                            const SourceInfo &source, const Rung &rung, int sampleSeconds) {
  QStringList filters;
  if (source.fps > rung.maxFps + 0.5)
    filters << QStringLiteral("fps=%1").arg(rung.maxFps);
  // Fit inside the box without upscaling. BGRA hands libwebp RGB, so the
  // BT.709 source is converted once instead of being read back as BT.601.
  filters << QStringLiteral("scale='min(%1,iw)':'min(%1,ih)':force_original_aspect_ratio=decrease"
                            ":flags=lanczos+accurate_rnd+full_chroma_int")
                 .arg(rung.dimension)
          << QStringLiteral("format=bgra");
  QStringList arguments{QStringLiteral("-nostdin"), QStringLiteral("-hide_banner"), QStringLiteral("-loglevel"),
                        QStringLiteral("error"), QStringLiteral("-y")};
  if (sampleSeconds > 0)
    arguments << QStringLiteral("-t") << QString::number(sampleSeconds);
  return arguments + QStringList{
      QStringLiteral("-i"), input,
      QStringLiteral("-map"), QStringLiteral("0:v:0"), QStringLiteral("-an"), QStringLiteral("-sn"), QStringLiteral("-dn"),
      QStringLiteral("-vf"), filters.join(QLatin1Char(',')),
      // Keep source timestamps: libwebp_anim turns them into frame durations.
      QStringLiteral("-fps_mode"), QStringLiteral("passthrough"),
      QStringLiteral("-c:v"), QStringLiteral("libwebp_anim"),
      QStringLiteral("-lossless"), QStringLiteral("0"),
      QStringLiteral("-quality"), QString::number(rung.quality),
      QStringLiteral("-compression_level"), QStringLiteral("6"),
      QStringLiteral("-preset"), QStringLiteral("picture"),
      QStringLiteral("-loop"), QStringLiteral("0"),
      QStringLiteral("-f"), QStringLiteral("webp"), output,
  };
}
