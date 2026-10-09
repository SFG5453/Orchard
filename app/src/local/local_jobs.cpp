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

#include "local_jobs.h"
#include "local_metadata.h"
#include "local_track.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QObject>

namespace local {
namespace {

// Folders become their audio files, sorted so an album imports in track order.
QStringList expand(const QStringList &paths) {
  QStringList files;
  for (const QString &path : paths) {
    const QFileInfo info(path);
    if (info.isDir()) {
      QStringList found;
      for (QDirIterator it(path, QDir::Files | QDir::Readable, QDirIterator::Subdirectories); it.hasNext();) {
        const QString file = it.next();
        if (isAudioFile(file))
          found.append(file);
      }
      found.sort(Qt::CaseInsensitive);
      files += found;
    } else if (info.isFile()) {
      files.append(info.absoluteFilePath());
    }
  }
  return files;
}

// Names a cover after its pixels, so an album's worth of identical embedded
// art collapses into one file and one collage tile.
QString adoptByContent(const QString &temporary, const QString &coversDir) {
  QFile file(temporary);
  if (!file.open(QIODevice::ReadOnly))
    return {};
  const QByteArray digest = QCryptographicHash::hash(file.readAll(), QCryptographicHash::Sha1).toHex();
  file.close();
  const QString target = coversDir + QLatin1Char('/') + QString::fromLatin1(digest.left(16)) + QStringLiteral(".jpg");
  if (QFileInfo::exists(target)) {
    QFile::remove(temporary);
    return target;
  }
  return QFile::rename(temporary, target) ? target : QString();
}

} // namespace

ImportResult importPaths(const QStringList &paths, const QSet<QString> &known, const QString &coversDir) {
  ImportResult result;
  QSet<QString> seen;
  for (const QString &file : expand(paths)) {
    if (!isAudioFile(file)) {
      ++result.rejected;
      continue;
    }
    const QString id = trackIdForPath(file);
    if (seen.contains(id))
      continue;
    seen.insert(id);
    result.ids.append(id);
    if (known.contains(id))
      continue;

    const Metadata metadata = probe(file);
    TrackRecord record;
    record.id = id;
    record.path = QFileInfo(file).absoluteFilePath();
    record.title = metadata.title;
    record.artist = metadata.artist;
    record.album = metadata.album;
    record.codec = metadata.codec;
    record.durationSeconds = metadata.durationSeconds;
    record.bitrate = metadata.bitrate;
    record.sampleRate = metadata.sampleRate;
    record.bitDepth = metadata.bitDepth;
    record.embeddedLyrics = metadata.lyrics;
    record.addedAt = QDateTime::currentMSecsSinceEpoch();

    const QString temporary = coversDir + QStringLiteral("/tmp-") + id.mid(kTrackPrefix.size()) + QStringLiteral(".jpg");
    if (extractCover(record.path, temporary))
      record.coverPath = adoptByContent(temporary, coversDir);
    result.records.append(record);
  }
  return result;
}

CoverResult ingestCover(const QString &source, const QString &base, const QString &coversDir,
                        const QString &animatedDir) {
  CoverResult result;
  const QFileInfo info(source);
  if (!info.isFile()) {
    result.error = QObject::tr("That file could not be opened.");
    return result;
  }
  const bool isGif = info.suffix().compare(QStringLiteral("gif"), Qt::CaseInsensitive) == 0;
  const bool isVideo = isVideoFile(source);
  if (!isImageFile(source) && !isVideo) {
    result.error = QObject::tr("Pick a PNG, JPEG, WebP, GIF or MP4 file.");
    return result;
  }
  QDir().mkpath(coversDir);
  const QString stamp = QString::number(QDateTime::currentMSecsSinceEpoch());
  const QString stillPath = coversDir + QLatin1Char('/') + base + QLatin1Char('-') + stamp + QStringLiteral(".jpg");

  if (isGif || isVideo) {
    QDir().mkpath(animatedDir);
    const QString videoPath = animatedDir + QLatin1Char('/') + base + QLatin1Char('-') + stamp +
                              (isGif ? QStringLiteral(".mp4") : QLatin1Char('.') + info.suffix().toLower());
    // GIFs must become video to play at all; real videos are used as they are.
    const bool animatedOk = isGif ? convertToLoopVideo(source, videoPath) : QFile::copy(source, videoPath);
    if (animatedOk)
      result.animated = videoPath;
    if (extractStill(source, stillPath)) {
      result.still = stillPath;
    } else if (isGif) {
      // No ffmpeg: Qt can still show a GIF's first frame, so keep the original.
      const QString copy = coversDir + QLatin1Char('/') + base + QLatin1Char('-') + stamp + QStringLiteral(".gif");
      if (QFile::copy(source, copy))
        result.still = copy;
    }
    result.ok = !result.still.isEmpty();
    if (!result.ok)
      result.error = QObject::tr("Orchard needs ffmpeg to read a still frame from that video.");
    return result;
  }

  const QString copy = coversDir + QLatin1Char('/') + base + QLatin1Char('-') + stamp + QLatin1Char('.') + info.suffix().toLower();
  result.ok = QFile::copy(source, copy);
  if (result.ok)
    result.still = copy;
  else
    result.error = QObject::tr("The picture could not be copied into the library.");
  return result;
}

} // namespace local
