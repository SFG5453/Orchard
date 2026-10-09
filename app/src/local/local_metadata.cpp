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

#include "local_metadata.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QProcess>
#include <QRegularExpression>

namespace local {
namespace {

constexpr int kProbeTimeoutMs = 15000;
constexpr int kExtractTimeoutMs = 30000;
constexpr int kConvertTimeoutMs = 120000;

// Runs a tool to completion. Output is captured because ffmpeg is chatty and a
// full pipe would otherwise make it wait politely forever.
bool runTool(const QString &program, const QStringList &arguments, int timeoutMs,
             QByteArray *standardOutput = nullptr) {
  QProcess process;
  process.start(program, arguments);
  if (!process.waitForStarted(5000))
    return false;
  if (!process.waitForFinished(timeoutMs)) {
    process.kill();
    process.waitForFinished(1000);
    return false;
  }
  if (standardOutput)
    *standardOutput = process.readAllStandardOutput();
  return process.exitStatus() == QProcess::NormalExit && process.exitCode() == 0;
}

// Tag keys arrive as TITLE, Title and title depending on the container's mood.
QString tagValue(const QJsonObject &tags, std::initializer_list<const char *> names) {
  for (const char *name : names) {
    const QString wanted = QString::fromLatin1(name);
    for (auto it = tags.constBegin(); it != tags.constEnd(); ++it) {
      if (it.key().compare(wanted, Qt::CaseInsensitive) == 0) {
        const QString value = it.value().toString().trimmed();
        if (!value.isEmpty())
          return value;
      }
    }
  }
  return {};
}

int intValue(const QJsonValue &value) {
  if (value.isString())
    return value.toString().toInt();
  return value.toInt();
}

double doubleValue(const QJsonValue &value) {
  if (value.isString())
    return value.toString().toDouble();
  return value.toDouble();
}

} // namespace

QString ffmpegProgram() {
  const QString configured = qEnvironmentVariable("ORCHARD_FFMPEG");
  return configured.isEmpty() ? QStringLiteral("ffmpeg") : configured;
}

QString ffprobeProgram() {
  // A bundled ffmpeg.exe ships its ffprobe next to it.
  const QString configured = qEnvironmentVariable("ORCHARD_FFMPEG");
  if (!configured.isEmpty()) {
    const QFileInfo ffmpeg(configured);
    const QString sibling = ffmpeg.dir().filePath(
        ffmpeg.suffix().isEmpty() ? QStringLiteral("ffprobe")
                                  : QStringLiteral("ffprobe.") + ffmpeg.suffix());
    if (QFileInfo::exists(sibling))
      return sibling;
  }
  return QStringLiteral("ffprobe");
}

QStringList audioExtensions() {
  return {QStringLiteral("mp3"),  QStringLiteral("flac"), QStringLiteral("m4a"),
          QStringLiteral("aac"),  QStringLiteral("ogg"),  QStringLiteral("oga"),
          QStringLiteral("opus"), QStringLiteral("wav"),  QStringLiteral("wma"),
          QStringLiteral("aif"),  QStringLiteral("aiff"), QStringLiteral("alac"),
          QStringLiteral("ape"),  QStringLiteral("wv"),   QStringLiteral("mka")};
}

bool isAudioFile(const QString &path) {
  return audioExtensions().contains(QFileInfo(path).suffix().toLower());
}

bool isImageFile(const QString &path) {
  static const QStringList extensions{QStringLiteral("png"),  QStringLiteral("jpg"),
                                      QStringLiteral("jpeg"), QStringLiteral("webp"),
                                      QStringLiteral("bmp"),  QStringLiteral("gif")};
  return extensions.contains(QFileInfo(path).suffix().toLower());
}

bool isVideoFile(const QString &path) {
  static const QStringList extensions{QStringLiteral("mp4"), QStringLiteral("m4v"),
                                      QStringLiteral("mov"), QStringLiteral("webm"),
                                      QStringLiteral("mkv")};
  return extensions.contains(QFileInfo(path).suffix().toLower());
}

Metadata metadataFromFileName(const QString &path) {
  Metadata metadata;
  QString base = QFileInfo(path).completeBaseName();
  base.replace(QLatin1Char('_'), QLatin1Char(' '));
  // "01 - " or "01. " track numbers are noise, not part of the title.
  static const QRegularExpression leadingNumber(QStringLiteral("^\\s*\\d{1,3}\\s*[-.)]\\s+"));
  base.remove(leadingNumber);
  const qsizetype dash = base.indexOf(QStringLiteral(" - "));
  if (dash > 0) {
    metadata.artist = base.left(dash).trimmed();
    metadata.title = base.mid(dash + 3).trimmed();
  } else {
    metadata.title = base.trimmed();
  }
  if (metadata.title.isEmpty())
    metadata.title = QFileInfo(path).fileName();
  return metadata;
}

Metadata parseProbe(const QJsonObject &probeJson, qint64 fileSize) {
  Metadata metadata;
  const QJsonObject format = probeJson.value(QStringLiteral("format")).toObject();
  const QJsonArray streams = probeJson.value(QStringLiteral("streams")).toArray();
  QJsonObject audio;
  for (const QJsonValue &value : streams) {
    const QJsonObject stream = value.toObject();
    if (stream.value(QStringLiteral("codec_type")).toString() == QStringLiteral("audio")) {
      audio = stream;
      break;
    }
  }
  if (format.isEmpty() && audio.isEmpty())
    return metadata;
  metadata.probed = true;

  // Containers keep tags on the format, Ogg and FLAC sometimes on the stream.
  QJsonObject tags = format.value(QStringLiteral("tags")).toObject();
  const QJsonObject streamTags = audio.value(QStringLiteral("tags")).toObject();
  for (auto it = streamTags.constBegin(); it != streamTags.constEnd(); ++it) {
    if (!tags.contains(it.key()))
      tags.insert(it.key(), it.value());
  }
  metadata.title = tagValue(tags, {"title"});
  metadata.artist = tagValue(tags, {"artist", "album_artist", "albumartist"});
  metadata.album = tagValue(tags, {"album"});
  metadata.lyrics = tagValue(tags, {"lyrics", "unsyncedlyrics", "uslt", "lyrics-eng"});
  metadata.codec = audio.value(QStringLiteral("codec_name")).toString();
  metadata.sampleRate = intValue(audio.value(QStringLiteral("sample_rate")));
  metadata.channels = intValue(audio.value(QStringLiteral("channels")));
  metadata.bitDepth = intValue(audio.value(QStringLiteral("bits_per_raw_sample")));
  if (metadata.bitDepth <= 0)
    metadata.bitDepth = intValue(audio.value(QStringLiteral("bits_per_sample")));

  metadata.durationSeconds = doubleValue(format.value(QStringLiteral("duration")));
  if (metadata.durationSeconds <= 0)
    metadata.durationSeconds = doubleValue(audio.value(QStringLiteral("duration")));

  // Prefer the container's overall rate: it is what the file really costs per
  // second, covers and all. Fall back to the stream, then to plain arithmetic.
  metadata.bitrate = intValue(format.value(QStringLiteral("bit_rate")));
  if (metadata.bitrate <= 0)
    metadata.bitrate = intValue(audio.value(QStringLiteral("bit_rate")));
  if (metadata.bitrate <= 0 && fileSize > 0 && metadata.durationSeconds > 0)
    metadata.bitrate = static_cast<int>((fileSize * 8) / metadata.durationSeconds);
  return metadata;
}

Metadata probe(const QString &path) {
  Metadata metadata;
  QByteArray output;
  const bool ok = runTool(ffprobeProgram(),
                          {QStringLiteral("-v"), QStringLiteral("error"), QStringLiteral("-print_format"),
                           QStringLiteral("json"), QStringLiteral("-show_format"),
                           QStringLiteral("-show_streams"), path},
                          kProbeTimeoutMs, &output);
  if (ok) {
    const QJsonDocument document = QJsonDocument::fromJson(output);
    metadata = parseProbe(document.object(), QFileInfo(path).size());
  }
  // Tags beat file names, but a blank tag must not beat a perfectly good name.
  const Metadata fallback = metadataFromFileName(path);
  if (metadata.title.isEmpty())
    metadata.title = fallback.title;
  if (metadata.artist.isEmpty())
    metadata.artist = fallback.artist;
  return metadata;
}

bool extractCover(const QString &audioPath, const QString &outFile) {
  QDir().mkpath(QFileInfo(outFile).absolutePath());
  QFile::remove(outFile);
  // -map 0:v:0 picks the attached picture; audio-only files simply fail here.
  if (runTool(ffmpegProgram(),
              {QStringLiteral("-v"), QStringLiteral("error"), QStringLiteral("-y"), QStringLiteral("-i"),
               audioPath, QStringLiteral("-an"), QStringLiteral("-map"), QStringLiteral("0:v:0"),
               QStringLiteral("-frames:v"), QStringLiteral("1"), outFile},
              kExtractTimeoutMs) &&
      QFileInfo(outFile).size() > 0)
    return true;
  QFile::remove(outFile);

  // No embedded art: an album folder usually has a picture lying around.
  const QDir folder = QFileInfo(audioPath).dir();
  for (const QString &name : {QStringLiteral("cover"), QStringLiteral("folder"), QStringLiteral("front"),
                              QStringLiteral("album"), QStringLiteral("artwork")}) {
    for (const QString &suffix : {QStringLiteral("jpg"), QStringLiteral("jpeg"), QStringLiteral("png"),
                                  QStringLiteral("webp")}) {
      const QString candidate = folder.filePath(name + QLatin1Char('.') + suffix);
      if (QFileInfo::exists(candidate) && QFile::copy(candidate, outFile))
        return true;
    }
  }
  return false;
}

bool extractStill(const QString &mediaPath, const QString &outFile) {
  QDir().mkpath(QFileInfo(outFile).absolutePath());
  QFile::remove(outFile);
  return runTool(ffmpegProgram(),
                 {QStringLiteral("-v"), QStringLiteral("error"), QStringLiteral("-y"), QStringLiteral("-i"),
                  mediaPath, QStringLiteral("-frames:v"), QStringLiteral("1"), outFile},
                 kExtractTimeoutMs) &&
         QFileInfo(outFile).size() > 0;
}

bool convertToLoopVideo(const QString &sourcePath, const QString &outFile) {
  QDir().mkpath(QFileInfo(outFile).absolutePath());
  QFile::remove(outFile);
  // yuv420p and even dimensions keep every hardware decoder happy.
  const bool ok = runTool(
      ffmpegProgram(),
      {QStringLiteral("-v"), QStringLiteral("error"), QStringLiteral("-y"), QStringLiteral("-i"), sourcePath,
       QStringLiteral("-an"), QStringLiteral("-movflags"), QStringLiteral("+faststart"), QStringLiteral("-pix_fmt"),
       QStringLiteral("yuv420p"), QStringLiteral("-vf"),
       QStringLiteral("scale=trunc(iw/2)*2:trunc(ih/2)*2"), outFile},
      kConvertTimeoutMs);
  if (!ok)
    QFile::remove(outFile);
  return ok;
}

} // namespace local
