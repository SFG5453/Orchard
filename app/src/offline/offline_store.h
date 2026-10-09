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

namespace offline {

inline const QString kCollectionPrefix = QStringLiteral("offline-playlist:");

inline bool isCollectionId(const QString &id) { return id.startsWith(kCollectionPrefix); }

// One downloaded song; `meta` is the QML track map, so rows look the same online and offline.
struct TrackRecord {
  QString id;
  QJsonObject meta;
  QString path;
  QString mimeType;
  QString quality;
  int bitrate{0}; // bits per second
  qint64 bytes{0};
  QString thumbnailPath;
  QString animatedPath; // Shared between the songs of one album.
  qint64 downloadedAt{0};
};

// A saved playlist or album; songs that are no longer downloaded drop out of its detail.
struct CollectionRecord {
  QString id;
  QString kind; // "playlist" or "album"
  QString title;
  QString author;
  QString description;
  QString thumbnailPath;
  QStringList trackIds;
  qint64 savedAt{0};
};

// Records plus the folders that hold their files; no threads or signals.
class OfflineStore {
public:
  explicit OfflineStore(QString rootDir);

  bool load();
  bool save() const;

  [[nodiscard]] QString root() const { return m_root; }
  [[nodiscard]] QString audioDir() const { return m_root + QStringLiteral("/audio"); }
  [[nodiscard]] QString artDir() const { return m_root + QStringLiteral("/art"); }
  [[nodiscard]] QString animatedDir() const { return m_root + QStringLiteral("/animated"); }

  QHash<QString, TrackRecord> tracks;
  // Newest first.
  QStringList order;
  QList<CollectionRecord> collections;

  CollectionRecord *collection(const QString &id);
  [[nodiscard]] const CollectionRecord *collection(const QString &id) const;
  // Adds or replaces a song and moves it to the front of the order.
  void put(const TrackRecord &track);
  // Drops the record and returns it, so the caller can delete its files.
  TrackRecord take(const QString &id);
  [[nodiscard]] bool animatedInUse(const QString &path) const;
  [[nodiscard]] qint64 totalBytes() const;
  // Forgets songs whose audio file is gone and collections left empty.
  int prune();
  // Deletes files in the store folders that no record points at, such as half-written downloads.
  int sweep() const;

  // File-system safe stem for a video id.
  static QString stem(const QString &id);

  static QJsonObject toJson(const TrackRecord &track);
  static TrackRecord trackFromJson(const QJsonObject &object);
  static QJsonObject toJson(const CollectionRecord &collection);
  static CollectionRecord collectionFromJson(const QJsonObject &object);

private:
  QString m_root;
};

// QML shapes. Rows keep the keys YouTube rows use, with local file urls for art.
QVariantMap trackToVariant(const TrackRecord &track);
QVariantMap collectionToVariant(const CollectionRecord &collection, const OfflineStore &store);
QVariantMap collectionDetail(const CollectionRecord &collection, const OfflineStore &store);
// Songs of the collection that are still downloaded.
int downloadedCount(const CollectionRecord &collection, const OfflineStore &store);

} // namespace offline
