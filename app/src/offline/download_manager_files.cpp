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

// Art, animated loops, removal and bookkeeping for DownloadManager.

#include "download_manager.h"

#include "appearance/animated_artwork_service.h"
#include "file_fetch.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QImageReader>
#include <QLocale>
#include <QRegularExpression>
#include <QSettings>
#include <QUrl>

namespace {

constexpr qint64 kMaxCoverBytes = 16LL * 1024 * 1024;
constexpr qint64 kMaxLoopBytes = 256LL * 1024 * 1024;

// YouTube covers carry their size in the url; ask for a sharper one.
QString coverUrl(QString url) {
  static const QRegularExpression size(QStringLiteral("=w\\d+-h\\d+"));
  url.replace(size, QStringLiteral("=w544-h544"));
  return url;
}

QString collectionSource(const QVariantMap &collection) {
  for (const QString &key : {QStringLiteral("playlistId"), QStringLiteral("browseId"), QStringLiteral("id")}) {
    QString value = collection.value(key).toString().trimmed();
    if (value.startsWith(QLatin1String("VL")))
      value.remove(0, 2);
    if (!value.isEmpty())
      return value;
  }
  return {};
}

} // namespace

void DownloadManager::touch() {
  ++m_revision;
  scheduleSave();
  emit changed();
}

void DownloadManager::activity() { emit activityChanged(); }

void DownloadManager::scheduleSave() { m_saveTimer.start(); }

void DownloadManager::setQuality(const QString &quality) {
  if (quality == m_quality ||
      (quality != QLatin1String("saver") && quality != QLatin1String("normal") && quality != QLatin1String("high")))
    return;
  m_quality = quality;
  QSettings().setValue(QStringLiteral("downloads/quality"), quality);
  emit settingsChanged();
}

void DownloadManager::setAnimatedArtwork(bool enabled) {
  if (enabled == m_animated)
    return;
  m_animated = enabled;
  QSettings().setValue(QStringLiteral("downloads/animatedArtwork"), enabled);
  emit settingsChanged();
}

QString DownloadManager::formatBytes(double bytes) const {
  return QLocale().formattedDataSize(static_cast<qint64>(bytes), 1, QLocale::DataSizeSIFormat);
}

QString DownloadManager::audioPathFor(const QString &id) const {
  const auto it = m_store.tracks.constFind(id);
  return it != m_store.tracks.cend() && QFileInfo::exists(it->path) ? it->path : QString();
}

QString DownloadManager::animatedUrlFor(const QString &id) const {
  const auto it = m_store.tracks.constFind(id);
  if (it == m_store.tracks.cend() || it->animatedPath.isEmpty() || !QFileInfo::exists(it->animatedPath))
    return {};
  return QUrl::fromLocalFile(it->animatedPath).toString();
}

int DownloadManager::bitrateFor(const QString &id) const {
  const auto it = m_store.tracks.constFind(id);
  return it == m_store.tracks.cend() ? 0 : it->bitrate;
}

QVariantMap DownloadManager::collectionState(const QVariantList &tracks) const {
  int total = 0;
  int downloaded = 0;
  int pendingCount = 0;
  for (const QVariant &value : tracks) {
    const QVariantMap track = value.toMap();
    if (!eligible(track))
      continue;
    ++total;
    const QString id = track.value(QStringLiteral("id")).toString();
    const auto job = m_jobs.constFind(id);
    if (isDownloaded(id))
      ++downloaded;
    else if (job != m_jobs.cend() && job->phase != Phase::Failed)
      ++pendingCount;
  }
  return {{QStringLiteral("total"), total},
          {QStringLiteral("downloaded"), downloaded},
          {QStringLiteral("pending"), pendingCount}};
}

void DownloadManager::fetchCover(const QString &id) {
  const auto job = m_jobs.find(id);
  const auto record = m_store.tracks.constFind(id);
  if (job == m_jobs.end())
    return;
  const QString url = record == m_store.tracks.cend()
                          ? QString()
                          : coverUrl(record->meta.value(QStringLiteral("thumbnail")).toString());
  if (url.isEmpty()) {
    fetchLoop(id);
    return;
  }
  QDir().mkpath(m_store.artDir());
  const QString path = m_store.artDir() + QLatin1Char('/') + offline::OfflineStore::stem(id) + QStringLiteral(".jpg");
  auto *fetch = new FileFetch(&m_network, QUrl(url), path, kMaxCoverBytes, 15000, this);
  job->fetch = fetch;
  connect(fetch, &FileFetch::finished, this, [this, id, path, fetch](bool ok) {
    fetch->deleteLater();
    if (!m_jobs.contains(id))
      return;
    const auto stored = m_store.tracks.find(id);
    if (ok && stored != m_store.tracks.end() && QImageReader(path).canRead()) {
      stored->thumbnailPath = path;
      touch();
    } else {
      QFile::remove(path);
    }
    fetchLoop(id);
  });
  fetch->start();
}

void DownloadManager::fetchLoop(const QString &id) {
  const auto job = m_jobs.find(id);
  if (job == m_jobs.end())
    return;
  const QString title = job->track.value(QStringLiteral("title")).toString();
  if (!m_animated || !m_artwork || title.trimmed().isEmpty() || !m_store.tracks.contains(id)) {
    complete(id);
    return;
  }
  const quint64 request = ++m_nextArtworkRequest;
  job->artworkRequest = request;
  const QString artist = job->track.value(QStringLiteral("artist")).toString();
  const QString album = job->track.value(QStringLiteral("album")).toString();
  // The service may answer before returning, and answers with no url when the song has no loop.
  m_artwork->resolveTrackArtwork(title, artist, album, request, [this, id, request](quint64, const QString &url) {
    const auto current = m_jobs.find(id);
    if (current == m_jobs.end() || current->artworkRequest != request)
      return;
    const auto stored = m_store.tracks.find(id);
    if (url.isEmpty() || stored == m_store.tracks.end()) {
      complete(id);
      return;
    }
    const QString name = QString::fromLatin1(QCryptographicHash::hash(url.toUtf8(), QCryptographicHash::Sha1).toHex().left(16));
    const QString path = m_store.animatedDir() + QLatin1Char('/') + name + QStringLiteral(".mp4");
    if (QFileInfo::exists(path)) {
      stored->animatedPath = path;
      complete(id);
      return;
    }
    QDir().mkpath(m_store.animatedDir());
    auto *fetch = new FileFetch(&m_network, QUrl(url), path, kMaxLoopBytes, 60000, this);
    current->fetch = fetch;
    connect(fetch, &FileFetch::finished, this, [this, id, path, fetch](bool ok) {
      fetch->deleteLater();
      if (!m_jobs.contains(id))
        return;
      const auto record = m_store.tracks.find(id);
      if (ok && record != m_store.tracks.end())
        record->animatedPath = path;
      complete(id);
    });
    fetch->start();
  });
}

void DownloadManager::saveCollection(const QVariantMap &collection, const QStringList &ids) {
  const QString source = collectionSource(collection);
  if (source.isEmpty())
    return;
  const QString id = offline::kCollectionPrefix + source;
  offline::CollectionRecord *record = m_store.collection(id);
  if (!record) {
    m_store.collections.append(offline::CollectionRecord{});
    record = &m_store.collections.last();
    record->id = id;
  }
  const bool album = collection.value(QStringLiteral("kind")).toString() == QLatin1String("album") ||
                     collection.value(QStringLiteral("type")).toString() == QLatin1String("album");
  record->kind = album ? QStringLiteral("album") : QStringLiteral("playlist");
  record->title = collection.value(QStringLiteral("title")).toString();
  record->author = collection.value(QStringLiteral("author")).toString();
  if (record->author.isEmpty())
    record->author = collection.value(QStringLiteral("artist")).toString();
  record->description = collection.value(QStringLiteral("description")).toString();
  record->trackIds = ids;
  record->savedAt = QDateTime::currentMSecsSinceEpoch();
  const QString url = coverUrl(collection.value(QStringLiteral("thumbnail")).toString());
  if (url.isEmpty() || (!record->thumbnailPath.isEmpty() && QFileInfo::exists(record->thumbnailPath)))
    return;
  QDir().mkpath(m_store.artDir());
  const QString path = m_store.artDir() + QStringLiteral("/c-") + offline::OfflineStore::stem(source) + QStringLiteral(".jpg");
  auto *fetch = new FileFetch(&m_network, QUrl(url), path, kMaxCoverBytes, 15000, this);
  connect(fetch, &FileFetch::finished, this, [this, id, path, fetch](bool ok) {
    fetch->deleteLater();
    offline::CollectionRecord *saved = m_store.collection(id);
    if (ok && saved && QImageReader(path).canRead()) {
      saved->thumbnailPath = path;
      touch();
    } else {
      QFile::remove(path);
    }
  });
  fetch->start();
}

void DownloadManager::deleteFiles(const offline::TrackRecord &record) {
  QFile::remove(record.path);
  if (!record.thumbnailPath.isEmpty())
    QFile::remove(record.thumbnailPath);
  if (!record.animatedPath.isEmpty() && !m_store.animatedInUse(record.animatedPath))
    QFile::remove(record.animatedPath);
}

// A collection survives while any of its songs is downloaded or on the way.
void DownloadManager::dropEmptyCollections() {
  m_store.collections.removeIf([this](const offline::CollectionRecord &collection) {
    if (offline::downloadedCount(collection, m_store) > 0)
      return false;
    for (const QString &id : collection.trackIds) {
      const auto job = m_jobs.constFind(id);
      if (job != m_jobs.cend() && job->phase != Phase::Failed)
        return false;
    }
    if (!collection.thumbnailPath.isEmpty())
      QFile::remove(collection.thumbnailPath);
    return true;
  });
}

void DownloadManager::remove(const QString &id) {
  cancel(id);
  if (!m_store.tracks.contains(id))
    return;
  deleteFiles(m_store.take(id));
  dropEmptyCollections();
  touch();
}

void DownloadManager::removeAll(const QVariantList &tracks, const QString &collectionId) {
  for (const QVariant &value : tracks) {
    const QString id = value.toMap().value(QStringLiteral("id")).toString();
    cancel(id);
    if (m_store.tracks.contains(id))
      deleteFiles(m_store.take(id));
  }
  if (!collectionId.isEmpty()) {
    m_store.collections.removeIf([&](const offline::CollectionRecord &collection) {
      if (collection.id != collectionId)
        return false;
      if (!collection.thumbnailPath.isEmpty())
        QFile::remove(collection.thumbnailPath);
      return true;
    });
  }
  dropEmptyCollections();
  touch();
}

void DownloadManager::clearAll() {
  for (Job &job : m_jobs)
    abortJob(job);
  m_jobs.clear();
  m_queue.clear();
  m_requests.clear();
  m_store.tracks.clear();
  m_store.order.clear();
  m_store.collections.clear();
  QDir(m_store.audioDir()).removeRecursively();
  QDir(m_store.artDir()).removeRecursively();
  QDir(m_store.animatedDir()).removeRecursively();
  m_store.save();
  touch();
  activity();
  emit noticeRequested(tr("All downloads were removed."));
}
