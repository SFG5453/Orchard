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

#include <QJsonObject>
#include <QString>
#include <QStringList>

namespace local {

// What a song file says about itself. Every field may be empty or zero: tags
// are a suggestion, and half the internet's mp3s were tagged by a toaster.
struct Metadata {
  bool probed{false}; // False when ffprobe was unavailable or rejected the file.
  QString title;
  QString artist;
  QString album;
  QString lyrics; // Embedded (USLT / LYRICS) text, if any.
  QString codec;
  double durationSeconds{0};
  int bitrate{0}; // Bits per second, container-wide.
  int sampleRate{0};
  int channels{0};
  int bitDepth{0};
};

// Extensions Orchard offers in the file picker and accepts from folders.
QStringList audioExtensions();
bool isAudioFile(const QString &path);
bool isImageFile(const QString &path);
bool isVideoFile(const QString &path);

// Pulls the useful bits out of ffprobe's JSON. Split from probe() so tests can
// feed it canned output without needing ffmpeg installed.
Metadata parseProbe(const QJsonObject &probe, qint64 fileSize);

// "Artist - Title.ext" becomes artist and title; anything else is just a title.
Metadata metadataFromFileName(const QString &path);

// Blocks on ffprobe, so call it off the GUI thread. Always returns something
// usable: when probing fails the file name stands in for the tags.
Metadata probe(const QString &path);

// Writes the embedded cover (or a cover.jpg-style neighbour) to `outFile`.
// Returns whether a picture was written.
bool extractCover(const QString &audioPath, const QString &outFile);

// Writes the first frame of an image or video to `outFile` as a JPEG still.
bool extractStill(const QString &mediaPath, const QString &outFile);

// GIFs do not play in QtMultimedia, so they become silent looping MP4s.
bool convertToLoopVideo(const QString &sourcePath, const QString &outFile);

QString ffmpegProgram();
QString ffprobeProgram();

} // namespace local
