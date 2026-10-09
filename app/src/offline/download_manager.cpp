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

#include "download_manager.h"

#include "auth/auth_manager.h"
#include "connectivity_monitor.h"
#include "file_fetch.h"
#include "local/local_track.h"
#include "playback/stream_download.h"
#include "providers/youtube/youtube_provider.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonValue>
#include <QSettings>
#include <QStandardPaths>
#include <QStorageInfo>

namespace {

constexpr qint64 kMaxAudioBytes = 512LL * 1024 * 1024;
constexpr qint64 kDiskMargin = 64LL * 1024 * 1024;
// Two at a time: YouTube tolerates a pair of polite vacuums better than ten.
constexpr int kMaxActive = 2;

// Keys tied to one playback or queue slot.
const QStringList kTransientKeys = {
    QStringLiteral("bitrate"),        QStringLiteral("streamUrl"),       QStringLiteral("audioStreamUrl"),
    QStringLiteral("itag"),           QStringLiteral("audioItag"),       QStringLiteral("mimeType"),
    QStringLiteral("playbackSource"), QStringLiteral("bitDepth"),        QStringLiteral("sampleRate"),
    QStringLiteral("hires"),          QStringLiteral("setVideoId"),      QStringLiteral("queueOrigin"),
    QStringLiteral("autoplayGenerated"), QStringLiteral("downloaded"),   QStringLiteral("downloadedAnimatedCover"),
};

QVariantMap snapshot(QVariantMap track) {
  for (const QString &key : kTransientKeys)
    track.remove(key);
  return track;
}

QString extensionFor(const QString &mimeType) {
  if (mimeType.contains(QLatin1String("webm")))
    return QStringLiteral("webm");
  if (mimeType.contains(QLatin1String("mp4")))
    return QStringLiteral("m4a");
  return QStringLiteral("audio");
}

} // namespace

DownloadManager::DownloadManager(YouTubeProvider *provider, AuthManager *auth, QString rootDir, QObject *parent)
    : QObject(parent), m_provider(provider), m_auth(auth), m_store(std::move(rootDir)), m_network(this) {
  m_store.load();
  if (m_store.prune() > 0)
    m_store.save();
  m_store.sweep();
  QSettings settings;
  const QString quality = settings.value(QStringLiteral("downloads/quality")).toString();
  if (quality == QLatin1String("saver") || quality == QLatin1String("normal") || quality == QLatin1String("high"))
    m_quality = quality;
  m_animated = settings.value(QStringLiteral("downloads/animatedArtwork"), false).toBool();
  // Saving on every state change would hammer the disk; batch them instead.
  m_saveTimer.setSingleShot(true);
  m_saveTimer.setInterval(400);
  connect(&m_saveTimer, &QTimer::timeout, this, [this] { m_store.save(); });
  if (m_provider) {
    connect(m_provider, &YouTubeProvider::resultReady, this, &DownloadManager::receiveStream);
    connect(m_provider, &YouTubeProvider::requestFailed, this, &DownloadManager::receiveFailure);
  }
}

DownloadManager::~DownloadManager() {
  for (Job &job : m_jobs)
    abortJob(job);
  if (m_saveTimer.isActive()) {
    m_saveTimer.stop();
    m_store.save();
  }
}

QString DownloadManager::defaultRoot() {
  return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/downloads");
}

bool DownloadManager::eligible(const QVariantMap &track) {
  const QString id = track.value(QStringLiteral("id")).toString();
  const QString type = track.value(QStringLiteral("type")).toString();
  return !id.isEmpty() && !local::isLocalTrackId(id) && !track.value(QStringLiteral("unplayable")).toBool() &&
         (type == QLatin1String("song") || type == QLatin1String("track") || type == QLatin1String("video"));
}

void DownloadManager::setConnectivity(ConnectivityMonitor *monitor) {
  m_connectivity = monitor;
  if (!monitor)
    return;
  connect(monitor, &ConnectivityMonitor::lost, this, [this] {
    failQueued(tr("Waiting for a connection."));
  });
  connect(monitor, &ConnectivityMonitor::restored, this, &DownloadManager::retryFailed);
}

bool DownloadManager::offline() const { return m_connectivity && m_connectivity->offline(); }

QString DownloadManager::stateOf(const QString &id) const {
  if (m_store.tracks.contains(id))
    return QStringLiteral("downloaded");
  const auto it = m_jobs.constFind(id);
  if (it == m_jobs.cend())
    return QStringLiteral("none");
  switch (it->phase) {
  case Phase::Queued:
    return QStringLiteral("queued");
  case Phase::Failed:
    return QStringLiteral("failed");
  default:
    return QStringLiteral("downloading");
  }
}

int DownloadManager::pending() const {
  int count = 0;
  for (const Job &job : m_jobs) {
    if (job.phase != Phase::Failed)
      ++count;
  }
  return count;
}

int DownloadManager::failedCount() const {
  int count = 0;
  for (const Job &job : m_jobs) {
    if (job.phase == Phase::Failed)
      ++count;
  }
  return count;
}

double DownloadManager::progress() const {
  double total = 0;
  int count = 0;
  for (const Job &job : m_jobs) {
    if (job.phase == Phase::Failed)
      continue;
    ++count;
    total += job.phase == Phase::Artwork ? 1.0 : job.progress;
  }
  return count ? total / count : 0.0;
}

QString DownloadManager::currentTitle() const {
  for (const Job &job : m_jobs) {
    if (job.phase == Phase::Resolving || job.phase == Phase::Transferring)
      return job.track.value(QStringLiteral("title")).toString();
  }
  return {};
}

bool DownloadManager::enqueue(const QVariantMap &track) {
  if (!eligible(track))
    return false;
  const QString id = track.value(QStringLiteral("id")).toString();
  if (isDownloaded(id))
    return false;
  const auto existing = m_jobs.constFind(id);
  if (existing != m_jobs.cend() && existing->phase != Phase::Failed)
    return false;
  Job job;
  job.track = snapshot(track);
  m_jobs.insert(id, job);
  if (!m_queue.contains(id))
    m_queue.append(id);
  return true;
}

void DownloadManager::download(const QVariantMap &track) {
  if (!eligible(track))
    return;
  if (offline()) {
    emit noticeRequested(tr("You're offline. Downloads need a connection."));
    return;
  }
  if (!m_provider || !m_auth || !m_auth->isSignedIn()) {
    emit noticeRequested(tr("Sign in to download songs."));
    return;
  }
  if (!enqueue(track))
    return;
  touch();
  activity();
  pump();
}

void DownloadManager::downloadAll(const QVariantList &tracks, const QVariantMap &collection) {
  if (offline()) {
    emit noticeRequested(tr("You're offline. Downloads need a connection."));
    return;
  }
  if (!m_provider || !m_auth || !m_auth->isSignedIn()) {
    emit noticeRequested(tr("Sign in to download songs."));
    return;
  }
  QStringList ids;
  int queued = 0;
  for (const QVariant &value : tracks) {
    const QVariantMap track = value.toMap();
    if (!eligible(track))
      continue;
    ids.append(track.value(QStringLiteral("id")).toString());
    if (enqueue(track))
      ++queued;
  }
  if (ids.isEmpty())
    return;
  saveCollection(collection, ids);
  emit noticeRequested(queued > 0 ? tr("Downloading %n song(s)", nullptr, queued) : tr("Already downloaded."));
  touch();
  activity();
  pump();
}

void DownloadManager::pump() {
  if (offline()) {
    failQueued(tr("Waiting for a connection."));
    return;
  }
  int active = 0;
  for (const Job &job : std::as_const(m_jobs)) {
    if (job.phase == Phase::Resolving || job.phase == Phase::Transferring || job.phase == Phase::Artwork)
      ++active;
  }
  while (active < kMaxActive && !m_queue.isEmpty()) {
    const QString id = m_queue.takeFirst();
    const auto it = m_jobs.constFind(id);
    if (it == m_jobs.cend() || it->phase != Phase::Queued)
      continue;
    ++active;
    resolve(id, false);
  }
  activity();
}

void DownloadManager::resolve(const QString &id, bool refresh) {
  auto it = m_jobs.find(id);
  if (it == m_jobs.end())
    return;
  if (!m_provider || !m_auth) {
    fail(id, tr("Downloads are unavailable."));
    return;
  }
  it->phase = Phase::Resolving;
  m_auth->refreshSession();
  const quint64 request = m_provider->invoke(
      QStringLiteral("playback.resolve"),
      QJsonObject{{QStringLiteral("track"), QJsonObject::fromVariantMap(it->track)},
                  {QStringLiteral("session"), m_auth->sessionObject()},
                  {QStringLiteral("streamQuality"), m_quality},
                  {QStringLiteral("refreshStream"), refresh}});
  m_requests.insert(request, id);
}

void DownloadManager::receiveStream(quint64 request, const QJsonValue &result) {
  const QString id = m_requests.take(request);
  if (id.isEmpty() || !m_jobs.contains(id))
    return;
  beginTransfer(id, result.toObject());
}

void DownloadManager::receiveFailure(quint64 request, const QString &message) {
  const QString id = m_requests.take(request);
  if (id.isEmpty())
    return;
  fail(id, message.isEmpty() ? tr("The download failed.") : message);
}

void DownloadManager::beginTransfer(const QString &id, const QJsonObject &stream) {
  auto it = m_jobs.find(id);
  if (it == m_jobs.end())
    return;
  const qint64 length = static_cast<qint64>(stream.value(QStringLiteral("contentLength")).toDouble());
  QDir().mkpath(m_store.audioDir());
  const qint64 free = QStorageInfo(m_store.audioDir()).bytesAvailable();
  if (free >= 0 && free < length + kDiskMargin) {
    fail(id, tr("Not enough disk space."));
    return;
  }
  const QString extension = extensionFor(stream.value(QStringLiteral("mimeType")).toString());
  it->stream = stream;
  it->path = m_store.audioDir() + QLatin1Char('/') + offline::OfflineStore::stem(id) + QLatin1Char('.') + extension;
  it->phase = Phase::Transferring;
  auto *transfer = new StreamDownload(&m_network, stream, it->path, kMaxAudioBytes, this);
  it->transfer = transfer;
  connect(transfer, &StreamDownload::progress, this, [this, id](qint64 received, qint64 total) {
    const auto job = m_jobs.find(id);
    if (job == m_jobs.end() || total <= 0)
      return;
    job->progress = static_cast<double>(received) / static_cast<double>(total);
    activity();
  });
  connect(transfer, &StreamDownload::finished, this, [this, id, transfer](bool ok) {
    transfer->deleteLater();
    finishTransfer(id, ok);
  });
  activity();
  // May finish before returning when the stream is unusable.
  transfer->start();
}

void DownloadManager::finishTransfer(const QString &id, bool ok) {
  auto it = m_jobs.find(id);
  if (it == m_jobs.end())
    return;
  if (!ok) {
    QFile::remove(it->path);
    // A stale PO token fails mid-file; one fresh resolve usually fixes it.
    if (it->attempts == 0 && !offline()) {
      ++it->attempts;
      it->progress = 0;
      resolve(id, true);
      return;
    }
    fail(id, offline() ? tr("Waiting for a connection.") : tr("The download failed."));
    return;
  }
  offline::TrackRecord record;
  record.id = id;
  record.meta = QJsonObject::fromVariantMap(it->track);
  record.path = it->path;
  record.mimeType = it->stream.value(QStringLiteral("mimeType")).toString();
  record.quality = m_quality;
  record.bytes = QFileInfo(it->path).size();
  double seconds = it->stream.value(QStringLiteral("durationSeconds")).toDouble();
  if (seconds <= 0)
    seconds = it->track.value(QStringLiteral("durationSeconds")).toDouble();
  record.bitrate = it->stream.value(QStringLiteral("bitrate")).toInt();
  if (record.bitrate <= 0 && seconds > 0)
    record.bitrate = static_cast<int>(record.bytes * 8 / seconds);
  record.downloadedAt = QDateTime::currentMSecsSinceEpoch();
  m_store.put(record);
  it->phase = Phase::Artwork;
  it->progress = 1;
  touch();
  activity();
  fetchCover(id);
}

void DownloadManager::fail(const QString &id, const QString &message) {
  auto it = m_jobs.find(id);
  if (it == m_jobs.end())
    return;
  const bool first = failedCount() == 0;
  abortJob(*it);
  it->phase = Phase::Failed;
  it->error = message;
  it->progress = 0;
  m_queue.removeAll(id);
  if (first)
    emit noticeRequested(tr("Could not download “%1”. %2").arg(it->track.value(QStringLiteral("title")).toString(), message));
  touch();
  pump();
}

void DownloadManager::failQueued(const QString &message) {
  bool any = false;
  for (const QString &id : std::as_const(m_queue)) {
    const auto it = m_jobs.find(id);
    if (it == m_jobs.end() || it->phase != Phase::Queued)
      continue;
    it->phase = Phase::Failed;
    it->error = message;
    any = true;
  }
  m_queue.clear();
  if (!any)
    return;
  touch();
  activity();
}

void DownloadManager::complete(const QString &id) {
  m_jobs.remove(id);
  touch();
  pump();
}

void DownloadManager::abortJob(Job &job) {
  if (job.transfer) {
    job.transfer->abort();
    job.transfer->deleteLater();
  }
  if (job.fetch) {
    job.fetch->abort();
    job.fetch->deleteLater();
  }
  job.artworkRequest = 0;
}

void DownloadManager::cancel(const QString &id) {
  const auto it = m_jobs.find(id);
  if (it == m_jobs.end())
    return;
  abortJob(*it);
  if (!m_store.tracks.contains(id))
    QFile::remove(it->path);
  for (auto request = m_requests.begin(); request != m_requests.end();) {
    request = request.value() == id ? m_requests.erase(request) : std::next(request);
  }
  m_queue.removeAll(id);
  m_jobs.erase(it);
  touch();
  pump();
}

void DownloadManager::retryFailed() {
  bool any = false;
  for (auto it = m_jobs.begin(); it != m_jobs.end(); ++it) {
    if (it->phase != Phase::Failed)
      continue;
    it->phase = Phase::Queued;
    it->attempts = 0;
    it->error.clear();
    if (!m_queue.contains(it.key()))
      m_queue.append(it.key());
    any = true;
  }
  if (!any)
    return;
  touch();
  pump();
}
