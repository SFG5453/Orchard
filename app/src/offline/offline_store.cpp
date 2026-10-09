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

#include "offline_store.h"

#include "local/local_track.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QUrl>

namespace offline {
namespace {

constexpr int kVersion = 1;

QString fileUrl(const QString &path) {
  return path.isEmpty() || !QFileInfo::exists(path) ? QString() : QUrl::fromLocalFile(path).toString();
}

QStringList toStringList(const QJsonValue &value) {
  QStringList list;
  for (const QJsonValue &item : value.toArray())
    list.append(item.toString());
  return list;
}

} // namespace

OfflineStore::OfflineStore(QString rootDir) : m_root(std::move(rootDir)) {}

QString OfflineStore::stem(const QString &id) {
  static const QRegularExpression unsafe(QStringLiteral("[^A-Za-z0-9_-]"));
  QString safe = id;
  safe.replace(unsafe, QStringLiteral("_"));
  return safe;
}

CollectionRecord *OfflineStore::collection(const QString &id) {
  for (CollectionRecord &candidate : collections) {
    if (candidate.id == id)
      return &candidate;
  }
  return nullptr;
}

const CollectionRecord *OfflineStore::collection(const QString &id) const {
  for (const CollectionRecord &candidate : collections) {
    if (candidate.id == id)
      return &candidate;
  }
  return nullptr;
}

void OfflineStore::put(const TrackRecord &track) {
  order.removeAll(track.id);
  order.prepend(track.id);
  tracks.insert(track.id, track);
}

TrackRecord OfflineStore::take(const QString &id) {
  order.removeAll(id);
  return tracks.take(id);
}

bool OfflineStore::animatedInUse(const QString &path) const {
  if (path.isEmpty())
    return false;
  for (const TrackRecord &track : tracks) {
    if (track.animatedPath == path)
      return true;
  }
  return false;
}

qint64 OfflineStore::totalBytes() const {
  qint64 total = 0;
  QSet<QString> counted;
  for (const TrackRecord &track : tracks) {
    total += track.bytes;
    if (!track.thumbnailPath.isEmpty())
      total += QFileInfo(track.thumbnailPath).size();
    // One loop file serves every song of its album.
    if (!track.animatedPath.isEmpty() && !counted.contains(track.animatedPath)) {
      counted.insert(track.animatedPath);
      total += QFileInfo(track.animatedPath).size();
    }
  }
  return total;
}

int OfflineStore::prune() {
  int dropped = 0;
  const QStringList ids = tracks.keys();
  for (const QString &id : ids) {
    if (QFileInfo::exists(tracks.value(id).path))
      continue;
    take(id);
    ++dropped;
  }
  collections.removeIf([this](const CollectionRecord &collection) { return downloadedCount(collection, *this) == 0; });
  return dropped;
}

int OfflineStore::sweep() const {
  QSet<QString> keep;
  const auto remember = [&keep](const QString &path) {
    if (!path.isEmpty())
      keep.insert(QFileInfo(path).absoluteFilePath());
  };
  for (const TrackRecord &track : tracks) {
    remember(track.path);
    remember(track.thumbnailPath);
    remember(track.animatedPath);
  }
  for (const CollectionRecord &collection : collections)
    remember(collection.thumbnailPath);
  int removed = 0;
  for (const QString &dir : {audioDir(), artDir(), animatedDir()}) {
    const QFileInfoList files = QDir(dir).entryInfoList(QDir::Files | QDir::Hidden);
    for (const QFileInfo &info : files) {
      if (!keep.contains(info.absoluteFilePath()) && QFile::remove(info.absoluteFilePath()))
        ++removed;
    }
  }
  return removed;
}

QJsonObject OfflineStore::toJson(const TrackRecord &track) {
  return {{QStringLiteral("id"), track.id},
          {QStringLiteral("meta"), track.meta},
          {QStringLiteral("path"), track.path},
          {QStringLiteral("mimeType"), track.mimeType},
          {QStringLiteral("quality"), track.quality},
          {QStringLiteral("bitrate"), track.bitrate},
          {QStringLiteral("bytes"), static_cast<double>(track.bytes)},
          {QStringLiteral("thumbnail"), track.thumbnailPath},
          {QStringLiteral("animated"), track.animatedPath},
          {QStringLiteral("downloadedAt"), static_cast<double>(track.downloadedAt)}};
}

TrackRecord OfflineStore::trackFromJson(const QJsonObject &object) {
  TrackRecord track;
  track.id = object.value(QStringLiteral("id")).toString();
  track.meta = object.value(QStringLiteral("meta")).toObject();
  track.path = object.value(QStringLiteral("path")).toString();
  track.mimeType = object.value(QStringLiteral("mimeType")).toString();
  track.quality = object.value(QStringLiteral("quality")).toString();
  track.bitrate = object.value(QStringLiteral("bitrate")).toInt();
  track.bytes = static_cast<qint64>(object.value(QStringLiteral("bytes")).toDouble());
  track.thumbnailPath = object.value(QStringLiteral("thumbnail")).toString();
  track.animatedPath = object.value(QStringLiteral("animated")).toString();
  track.downloadedAt = static_cast<qint64>(object.value(QStringLiteral("downloadedAt")).toDouble());
  return track;
}

QJsonObject OfflineStore::toJson(const CollectionRecord &collection) {
  return {{QStringLiteral("id"), collection.id},
          {QStringLiteral("kind"), collection.kind},
          {QStringLiteral("title"), collection.title},
          {QStringLiteral("author"), collection.author},
          {QStringLiteral("description"), collection.description},
          {QStringLiteral("thumbnail"), collection.thumbnailPath},
          {QStringLiteral("trackIds"), QJsonArray::fromStringList(collection.trackIds)},
          {QStringLiteral("savedAt"), static_cast<double>(collection.savedAt)}};
}

CollectionRecord OfflineStore::collectionFromJson(const QJsonObject &object) {
  CollectionRecord collection;
  collection.id = object.value(QStringLiteral("id")).toString();
  collection.kind = object.value(QStringLiteral("kind")).toString(QStringLiteral("playlist"));
  collection.title = object.value(QStringLiteral("title")).toString();
  collection.author = object.value(QStringLiteral("author")).toString();
  collection.description = object.value(QStringLiteral("description")).toString();
  collection.thumbnailPath = object.value(QStringLiteral("thumbnail")).toString();
  collection.trackIds = toStringList(object.value(QStringLiteral("trackIds")));
  collection.savedAt = static_cast<qint64>(object.value(QStringLiteral("savedAt")).toDouble());
  return collection;
}

bool OfflineStore::load() {
  tracks.clear();
  order.clear();
  collections.clear();
  QFile file(m_root + QStringLiteral("/downloads.json"));
  if (!file.open(QIODevice::ReadOnly))
    return false;
  QJsonParseError error;
  const QJsonDocument document = QJsonDocument::fromJson(file.readAll(), &error);
  if (error.error != QJsonParseError::NoError || !document.isObject())
    return false;
  const QJsonObject root = document.object();
  if (root.value(QStringLiteral("version")).toInt() > kVersion)
    return false;
  for (const QJsonValue &value : root.value(QStringLiteral("tracks")).toArray()) {
    const TrackRecord track = trackFromJson(value.toObject());
    if (track.id.isEmpty() || track.path.isEmpty() || tracks.contains(track.id))
      continue;
    tracks.insert(track.id, track);
    order.append(track.id);
  }
  for (const QJsonValue &value : root.value(QStringLiteral("collections")).toArray()) {
    const CollectionRecord collection = collectionFromJson(value.toObject());
    if (!collection.id.isEmpty())
      collections.append(collection);
  }
  return true;
}

bool OfflineStore::save() const {
  QDir().mkpath(m_root);
  QJsonArray trackArray;
  for (const QString &id : order) {
    if (tracks.contains(id))
      trackArray.append(toJson(tracks.value(id)));
  }
  QJsonArray collectionArray;
  for (const CollectionRecord &collection : collections)
    collectionArray.append(toJson(collection));
  const QJsonObject root{{QStringLiteral("version"), kVersion},
                         {QStringLiteral("tracks"), trackArray},
                         {QStringLiteral("collections"), collectionArray}};
  // QSaveFile renames over the old index, so a crash mid-save keeps the last good one.
  QSaveFile file(m_root + QStringLiteral("/downloads.json"));
  if (!file.open(QIODevice::WriteOnly))
    return false;
  file.write(QJsonDocument(root).toJson(QJsonDocument::Compact));
  return file.commit();
}

QVariantMap trackToVariant(const TrackRecord &track) {
  QVariantMap map = track.meta.toVariantMap();
  const double seconds = map.value(QStringLiteral("durationSeconds")).toDouble();
  map.insert(QStringLiteral("id"), track.id);
  if (map.value(QStringLiteral("type")).toString().isEmpty())
    map.insert(QStringLiteral("type"), QStringLiteral("song"));
  if (map.value(QStringLiteral("duration")).toString().isEmpty() && seconds > 0)
    map.insert(QStringLiteral("duration"), local::formatDuration(seconds));
  // The remote cover is useless offline; the saved copy always loads.
  const QString cover = fileUrl(track.thumbnailPath);
  if (!cover.isEmpty())
    map.insert(QStringLiteral("thumbnail"), cover);
  map.insert(QStringLiteral("downloaded"), true);
  map.insert(QStringLiteral("downloadedAnimatedCover"), fileUrl(track.animatedPath));
  return map;
}

int downloadedCount(const CollectionRecord &collection, const OfflineStore &store) {
  int count = 0;
  for (const QString &id : collection.trackIds) {
    if (store.tracks.contains(id))
      ++count;
  }
  return count;
}

QVariantMap collectionToVariant(const CollectionRecord &collection, const OfflineStore &store) {
  const int count = downloadedCount(collection, store);
  const QString kind = collection.kind == QLatin1String("album") ? QObject::tr("Album") : QObject::tr("Playlist");
  const QString subtitle = QObject::tr("%1 · %n song(s)", nullptr, count).arg(kind);
  return {
      {QStringLiteral("id"), collection.id},
      {QStringLiteral("type"), QStringLiteral("playlist")},
      {QStringLiteral("source"), QStringLiteral("download")},
      {QStringLiteral("collectionKind"), collection.kind},
      {QStringLiteral("title"), collection.title},
      {QStringLiteral("author"), collection.author},
      {QStringLiteral("description"), collection.description},
      {QStringLiteral("thumbnail"), fileUrl(collection.thumbnailPath)},
      {QStringLiteral("subtitle"), subtitle},
      {QStringLiteral("itemCount"), subtitle},
      {QStringLiteral("totalTrackCount"), count},
  };
}

QVariantMap collectionDetail(const CollectionRecord &collection, const OfflineStore &store) {
  QVariantMap detail = collectionToVariant(collection, store);
  QVariantList rows;
  double total = 0;
  for (const QString &id : collection.trackIds) {
    const auto it = store.tracks.constFind(id);
    if (it == store.tracks.cend())
      continue;
    rows.append(trackToVariant(it.value()));
    total += it->meta.value(QStringLiteral("durationSeconds")).toDouble();
  }
  detail.insert(QStringLiteral("kind"), QStringLiteral("playlist"));
  detail.insert(QStringLiteral("tracks"), rows);
  detail.insert(QStringLiteral("totalTrackCount"), static_cast<int>(rows.size()));
  detail.insert(QStringLiteral("hasMoreTracks"), false);
  detail.insert(QStringLiteral("durationSeconds"), total);
  return detail;
}

} // namespace offline
