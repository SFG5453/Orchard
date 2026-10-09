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

#include "local_store.h"
#include "local_track.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSaveFile>
#include <QUrl>

namespace local {
namespace {

constexpr int kVersion = 1;

QString fileUrl(const QString &path) {
  return path.isEmpty() ? QString() : QUrl::fromLocalFile(path).toString();
}

QStringList toStringList(const QJsonValue &value) {
  QStringList list;
  for (const QJsonValue &item : value.toArray())
    list.append(item.toString());
  return list;
}

} // namespace

LocalStore::LocalStore(QString rootDir) : m_root(std::move(rootDir)) {}

PlaylistRecord *LocalStore::playlist(const QString &id) {
  for (PlaylistRecord &candidate : playlists) {
    if (candidate.id == id)
      return &candidate;
  }
  return nullptr;
}

const PlaylistRecord *LocalStore::playlist(const QString &id) const {
  for (const PlaylistRecord &candidate : playlists) {
    if (candidate.id == id)
      return &candidate;
  }
  return nullptr;
}

QJsonObject LocalStore::toJson(const TrackRecord &track) {
  return {{QStringLiteral("id"), track.id},
          {QStringLiteral("path"), track.path},
          {QStringLiteral("title"), track.title},
          {QStringLiteral("artist"), track.artist},
          {QStringLiteral("album"), track.album},
          {QStringLiteral("codec"), track.codec},
          {QStringLiteral("duration"), track.durationSeconds},
          {QStringLiteral("bitrate"), track.bitrate},
          {QStringLiteral("sampleRate"), track.sampleRate},
          {QStringLiteral("bitDepth"), track.bitDepth},
          {QStringLiteral("cover"), track.coverPath},
          {QStringLiteral("animated"), track.animatedPath},
          {QStringLiteral("customCover"), track.customCover},
          {QStringLiteral("lyrics"), track.lyricsPath},
          {QStringLiteral("embeddedLyrics"), track.embeddedLyrics},
          {QStringLiteral("addedAt"), static_cast<double>(track.addedAt)}};
}

TrackRecord LocalStore::trackFromJson(const QJsonObject &object) {
  TrackRecord track;
  track.id = object.value(QStringLiteral("id")).toString();
  track.path = object.value(QStringLiteral("path")).toString();
  track.title = object.value(QStringLiteral("title")).toString();
  track.artist = object.value(QStringLiteral("artist")).toString();
  track.album = object.value(QStringLiteral("album")).toString();
  track.codec = object.value(QStringLiteral("codec")).toString();
  track.durationSeconds = object.value(QStringLiteral("duration")).toDouble();
  track.bitrate = object.value(QStringLiteral("bitrate")).toInt();
  track.sampleRate = object.value(QStringLiteral("sampleRate")).toInt();
  track.bitDepth = object.value(QStringLiteral("bitDepth")).toInt();
  track.coverPath = object.value(QStringLiteral("cover")).toString();
  track.animatedPath = object.value(QStringLiteral("animated")).toString();
  track.customCover = object.value(QStringLiteral("customCover")).toBool();
  track.lyricsPath = object.value(QStringLiteral("lyrics")).toString();
  track.embeddedLyrics = object.value(QStringLiteral("embeddedLyrics")).toString();
  track.addedAt = static_cast<qint64>(object.value(QStringLiteral("addedAt")).toDouble());
  return track;
}

QJsonObject LocalStore::toJson(const PlaylistRecord &playlist) {
  return {{QStringLiteral("id"), playlist.id},
          {QStringLiteral("title"), playlist.title},
          {QStringLiteral("description"), playlist.description},
          {QStringLiteral("tracks"), QJsonArray::fromStringList(playlist.trackIds)},
          {QStringLiteral("cover"), playlist.coverPath},
          {QStringLiteral("animated"), playlist.animatedPath},
          {QStringLiteral("collage"), playlist.collagePath},
          {QStringLiteral("createdAt"), static_cast<double>(playlist.createdAt)}};
}

PlaylistRecord LocalStore::playlistFromJson(const QJsonObject &object) {
  PlaylistRecord playlist;
  playlist.id = object.value(QStringLiteral("id")).toString();
  playlist.title = object.value(QStringLiteral("title")).toString();
  playlist.description = object.value(QStringLiteral("description")).toString();
  playlist.trackIds = toStringList(object.value(QStringLiteral("tracks")));
  playlist.coverPath = object.value(QStringLiteral("cover")).toString();
  playlist.animatedPath = object.value(QStringLiteral("animated")).toString();
  playlist.collagePath = object.value(QStringLiteral("collage")).toString();
  playlist.createdAt = static_cast<qint64>(object.value(QStringLiteral("createdAt")).toDouble());
  return playlist;
}

bool LocalStore::load() {
  tracks.clear();
  trackOrder.clear();
  playlists.clear();
  QFile file(m_root + QStringLiteral("/library.json"));
  if (!file.open(QIODevice::ReadOnly))
    return false;
  const QJsonObject root = QJsonDocument::fromJson(file.readAll()).object();
  for (const QJsonValue &value : root.value(QStringLiteral("tracks")).toArray()) {
    const TrackRecord track = trackFromJson(value.toObject());
    if (track.id.isEmpty() || track.path.isEmpty())
      continue;
    tracks.insert(track.id, track);
    trackOrder.append(track.id);
  }
  for (const QJsonValue &value : root.value(QStringLiteral("playlists")).toArray()) {
    PlaylistRecord playlist = playlistFromJson(value.toObject());
    if (playlist.id.isEmpty())
      continue;
    // A song removed from the library must not linger as a ghost row.
    playlist.trackIds.removeIf([this](const QString &id) { return !tracks.contains(id); });
    playlists.append(playlist);
  }
  return true;
}

bool LocalStore::save() const {
  QDir().mkpath(m_root);
  QJsonArray trackArray;
  for (const QString &id : trackOrder) {
    if (tracks.contains(id))
      trackArray.append(toJson(tracks.value(id)));
  }
  QJsonArray playlistArray;
  for (const PlaylistRecord &playlist : playlists)
    playlistArray.append(toJson(playlist));
  const QJsonObject root{{QStringLiteral("version"), kVersion},
                         {QStringLiteral("tracks"), trackArray},
                         {QStringLiteral("playlists"), playlistArray}};
  // QSaveFile writes to a temp file and renames, so a crash mid-save cannot
  // eat the whole library. Musicians have suffered enough.
  QSaveFile file(m_root + QStringLiteral("/library.json"));
  if (!file.open(QIODevice::WriteOnly))
    return false;
  file.write(QJsonDocument(root).toJson(QJsonDocument::Compact));
  return file.commit();
}

QString playlistCover(const PlaylistRecord &playlist) {
  if (!playlist.coverPath.isEmpty() && QFileInfo::exists(playlist.coverPath))
    return playlist.coverPath;
  if (!playlist.collagePath.isEmpty() && QFileInfo::exists(playlist.collagePath))
    return playlist.collagePath;
  return {};
}

QVariantMap trackToVariant(const TrackRecord &track) {
  QVariantMap map{
      {QStringLiteral("id"), track.id},
      {QStringLiteral("type"), QStringLiteral("song")},
      {QStringLiteral("source"), QStringLiteral("local")},
      {QStringLiteral("localPath"), track.path},
      {QStringLiteral("title"), track.title},
      {QStringLiteral("artist"), track.artist},
      {QStringLiteral("artists"), track.artist.isEmpty() ? QStringList() : QStringList{track.artist}},
      {QStringLiteral("album"), track.album},
      {QStringLiteral("durationSeconds"), track.durationSeconds},
      {QStringLiteral("duration"), formatDuration(track.durationSeconds)},
      {QStringLiteral("thumbnail"), fileUrl(track.coverPath)},
      {QStringLiteral("localAnimatedCover"), fileUrl(track.animatedPath)},
      // Not "bitrate": that key is stripped from queued tracks as ephemeral
      // stream data, and the player re-inserts it once the file opens.
      {QStringLiteral("localBitrate"), track.bitrate},
      {QStringLiteral("codec"), track.codec},
      {QStringLiteral("sampleRate"), track.sampleRate},
      {QStringLiteral("bitDepth"), track.bitDepth},
      {QStringLiteral("hasCustomCover"), track.customCover},
      {QStringLiteral("hasLyrics"), !track.lyricsPath.isEmpty() || !track.embeddedLyrics.isEmpty()},
      {QStringLiteral("hasCustomLyrics"), !track.lyricsPath.isEmpty()},
      {QStringLiteral("missing"), !QFileInfo::exists(track.path)},
  };
  return map;
}

QVariantMap playlistToVariant(const PlaylistRecord &playlist, const LocalStore &) {
  const int count = static_cast<int>(playlist.trackIds.size());
  return {
      {QStringLiteral("id"), playlist.id},
      {QStringLiteral("playlistId"), playlist.id},
      {QStringLiteral("type"), QStringLiteral("playlist")},
      {QStringLiteral("source"), QStringLiteral("local")},
      {QStringLiteral("title"), playlist.title},
      {QStringLiteral("description"), playlist.description},
      {QStringLiteral("thumbnail"), fileUrl(playlistCover(playlist))},
      {QStringLiteral("subtitle"), count == 1 ? QObject::tr("Local · 1 song") : QObject::tr("Local · %1 songs").arg(count)},
      {QStringLiteral("totalTrackCount"), count},
      {QStringLiteral("hasCustomCover"), !playlist.coverPath.isEmpty()},
  };
}

QVariantMap playlistDetail(const PlaylistRecord &playlist, const LocalStore &store) {
  QVariantMap detail = playlistToVariant(playlist, store);
  QVariantList rows;
  double total = 0;
  for (const QString &id : playlist.trackIds) {
    const auto it = store.tracks.constFind(id);
    if (it == store.tracks.cend())
      continue;
    QVariantMap row = trackToVariant(it.value());
    // The picker and menus tell playlist rows apart by their position, so the
    // owning playlist travels with each row.
    row.insert(QStringLiteral("localPlaylistId"), playlist.id);
    rows.append(row);
    total += it->durationSeconds;
  }
  detail.insert(QStringLiteral("kind"), QStringLiteral("playlist"));
  detail.insert(QStringLiteral("author"), QObject::tr("You"));
  detail.insert(QStringLiteral("tracks"), rows);
  detail.insert(QStringLiteral("totalTrackCount"), static_cast<int>(rows.size()));
  detail.insert(QStringLiteral("hasMoreTracks"), false);
  detail.insert(QStringLiteral("durationSeconds"), total);
  detail.insert(QStringLiteral("animatedArtwork"), fileUrl(playlist.animatedPath));
  return detail;
}

} // namespace local
