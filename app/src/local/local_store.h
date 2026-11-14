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

#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QString>
#include <QStringList>
#include <QVariantMap>

namespace local {

// Bump when probe() learns a field that existing records should pick up.
inline constexpr int kTagsVersion = 2;

// One song file Orchard knows about. The audio itself is never copied: the
// path points at wherever the user keeps it.
struct TrackRecord {
  QString id;
  QString path;
  QString title;
  QString artist;
  QString album;
  QString albumArtist;
  int trackNumber{0};
  int discNumber{0};
  int year{0};
  int tagsVersion{0}; // Below kTagsVersion means the file is probed again for newer fields.
  qint64 fileModified{0}; // File mtime when last probed; a change means the tags may have too.
  QString codec;
  double durationSeconds{0};
  int bitrate{0}; // bits per second
  int sampleRate{0};
  int bitDepth{0};
  QString coverPath;    // Still image: embedded art or the user's pick.
  QString animatedPath; // Looping video from a user-supplied GIF or MP4.
  bool customCover{false};
  QString lyricsPath;     // User-supplied lyrics file, copied into the store.
  QString embeddedLyrics; // Text found in the file's own tags.
  qint64 addedAt{0};
};

struct PlaylistRecord {
  QString id;
  QString title;
  QString description;
  QStringList trackIds;
  QString coverPath;    // The user's own art; empty means auto collage.
  QString animatedPath; // Looping video when the user's art is a GIF or MP4.
  QString collagePath;  // Generated stitch of the first four covers.
  qint64 createdAt{0};
};

// Records plus the folders that hold their pictures and lyrics. Pure data and
// JSON: no threads, no signals, so tests can poke it with a temp directory.
class LocalStore {
public:
  explicit LocalStore(QString rootDir);

  bool load();
  bool save() const;

  [[nodiscard]] QString root() const { return m_root; }
  [[nodiscard]] QString coversDir() const { return m_root + QStringLiteral("/covers"); }
  [[nodiscard]] QString collagesDir() const { return m_root + QStringLiteral("/collages"); }
  [[nodiscard]] QString lyricsDir() const { return m_root + QStringLiteral("/lyrics"); }
  [[nodiscard]] QString animatedDir() const { return m_root + QStringLiteral("/animated"); }

  QHash<QString, TrackRecord> tracks;
  // Library order: newest first, which is what people expect from "recently added".
  QStringList trackOrder;
  QList<PlaylistRecord> playlists;

  PlaylistRecord *playlist(const QString &id);
  [[nodiscard]] const PlaylistRecord *playlist(const QString &id) const;

  static QJsonObject toJson(const TrackRecord &track);
  static TrackRecord trackFromJson(const QJsonObject &object);
  static QJsonObject toJson(const PlaylistRecord &playlist);
  static PlaylistRecord playlistFromJson(const QJsonObject &object);

private:
  QString m_root;
};

// QML-facing shapes, matching the keys YouTube rows already use.
QVariantMap trackToVariant(const TrackRecord &track);
QVariantMap playlistToVariant(const PlaylistRecord &playlist, const LocalStore &store);
QVariantMap playlistDetail(const PlaylistRecord &playlist, const LocalStore &store);

// The picture a playlist shows: the user's, else the collage, else nothing.
QString playlistCover(const PlaylistRecord &playlist);

} // namespace local
