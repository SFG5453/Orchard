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

#include "slop_detector.h"
#include "auth/auth_manager.h"
#include "providers/youtube/youtube_provider.h"
#include "slop_scan_worker.h"
#include "stream_download.h"

#include <QAudioBuffer>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QJsonArray>
#include <QJsonObject>
#include <QSettings>
#include <QSqlQuery>
#include <QStandardPaths>

namespace {
const QString kActionKey = QStringLiteral("playback/slopAction");
// HE-AAC rebuilds its upper band synthetically, which pushed real songs toward the cut.
const QJsonArray kScanMimeTypes{QStringLiteral("audio/webm; codecs=\"opus\""),
                                QStringLiteral("audio/mp4; codecs=\"mp4a.40.2\"")};
} // namespace

SlopDetector::SlopDetector(YouTubeProvider *provider, AuthManager *auth, QObject *parent)
    : QObject(parent), m_provider(provider), m_auth(auth) {
  const QString action = QSettings().value(kActionKey, m_action).toString();
  if (action == "off" || action == "mark" || action == "skip" || action == "remove")
    m_action = action;
  m_dbName = QStringLiteral("orchard-slop-%1").arg(reinterpret_cast<quintptr>(this));
  if (!m_provider || !m_auth)
    return;
  m_scanDir = QDir(QStandardPaths::writableLocation(QStandardPaths::CacheLocation))
                  .filePath(QStringLiteral("slop-scan"));
  // Leftovers from a crash are never resumed; analysis always starts from a full file.
  QDir(m_scanDir).removeRecursively();
  m_worker = new SlopScanWorker;
  m_worker->moveToThread(&m_scanThread);
  connect(&m_scanThread, &QThread::finished, m_worker, &QObject::deleteLater);
  connect(m_worker, &SlopScanWorker::analyzed, this, &SlopDetector::analyzed);
  m_scanThread.setObjectName(QStringLiteral("SlopScan"));
  m_scanThread.start(QThread::LowPriority);
  connect(provider, &YouTubeProvider::resultReady, this, &SlopDetector::resolved);
  connect(provider, &YouTubeProvider::requestFailed, this,
          [this](quint64 request, const QString &) {
            const QString trackId = m_resolving.take(request);
            if (!trackId.isEmpty())
              scanFailed(trackId);
          });
}

SlopDetector::~SlopDetector() {
  cancelScans();
  if (m_worker) {
    m_scanThread.quit();
    m_scanThread.wait();
  }
  for (const Session &session : std::as_const(m_sessions))
    delete session.handle;
  if (m_dbAttempted) {
    m_db = QSqlDatabase();
    QSqlDatabase::removeDatabase(m_dbName);
  }
}

void SlopDetector::scan(const QVariantList &tracks) {
  m_scanQueue = tracks;
  pump();
}

void SlopDetector::pump() {
  if (!m_worker || !enabled() || !m_auth->isSignedIn())
    return;
  while (m_downloads.size() + m_resolving.size() < kMaxDownloads && !m_scanQueue.isEmpty()) {
    const QVariantMap track = m_scanQueue.takeFirst().toMap();
    const QString id = track.value(QStringLiteral("id")).toString();
    const QString type = track.value(QStringLiteral("type")).toString();
    if (id.isEmpty() || (type != "song" && type != "track" && type != "video") ||
        track.value(QStringLiteral("unplayable")).toBool() || lookup(id) >= 0.0f ||
        m_failed.contains(id) || m_downloads.contains(id) || m_analyzing.contains(id) ||
        std::find(m_resolving.cbegin(), m_resolving.cend(), id) != m_resolving.cend())
      continue;
    // Saver Opus keeps background traffic small; the codec list rules out HE-AAC.
    const quint64 request = m_provider->invoke(
        "playback.resolve",
        QJsonObject{{"track", QJsonObject::fromVariantMap(track)},
                    {"session", m_auth->sessionObject()},
                    {"streamQuality", "saver"},
                    {"audioMimeTypes", kScanMimeTypes}});
    m_resolving.insert(request, id);
  }
}

void SlopDetector::resolved(quint64 request, const QJsonValue &result) {
  const QString id = m_resolving.take(request);
  if (id.isEmpty())
    return;
  if (!enabled()) {
    pump();
    return;
  }
  QDir().mkpath(m_scanDir);
  const QString path = QDir(m_scanDir).filePath(QString::fromLatin1(
      QCryptographicHash::hash(id.toUtf8(), QCryptographicHash::Sha256).toHex()));
  auto *download =
      new StreamDownload(&m_network, result.toObject(), path, kMaxDownloadBytes, this);
  m_downloads.insert(id, download);
  connect(download, &StreamDownload::finished, this, [this, id, path, download](bool ok) {
    m_downloads.remove(id);
    download->deleteLater();
    if (!ok) {
      QFile::remove(path);
      scanFailed(id);
      return;
    }
    m_analyzing.insert(id);
    QMetaObject::invokeMethod(m_worker, "analyze", Qt::QueuedConnection, Q_ARG(QString, id),
                              Q_ARG(QString, path));
    pump();
  });
  download->start();
}

void SlopDetector::scanFailed(const QString &trackId) {
  m_failed.insert(trackId);
  pump();
}

void SlopDetector::analyzed(const QString &trackId, bool ok, float probability, float seconds) {
  m_analyzing.remove(trackId);
  if (!ok) {
    scanFailed(trackId);
    return;
  }
  // The whole song was heard, so even a short track's verdict is final.
  if (enabled())
    record(trackId, SlopVerdict{probability, 0.0f, seconds});
  pump();
}

void SlopDetector::cancelScans() {
  m_scanQueue.clear();
  m_resolving.clear();
  for (const auto &download : std::as_const(m_downloads))
    if (download)
      download->deleteLater();
  m_downloads.clear();
}

void SlopDetector::setAction(const QString &action) {
  if (action == m_action ||
      (action != "off" && action != "mark" && action != "skip" && action != "remove"))
    return;
  m_action = action;
  QSettings().setValue(kActionKey, m_action);
  if (!enabled()) {
    for (const Session &session : std::as_const(m_sessions))
      delete session.handle;
    m_sessions.clear();
    cancelScans();
  }
  emit actionChanged();
  ++m_revision;
  emit flagsChanged();
}

bool SlopDetector::isFlagged(const QString &trackId) const {
  return enabled() && !trackId.isEmpty() && lookup(trackId) >= m_threshold;
}

double SlopDetector::probability(const QString &trackId) const {
  return trackId.isEmpty() ? -1.0 : lookup(trackId);
}

bool SlopDetector::ensureDatabase() const {
  if (m_dbAttempted)
    return m_db.isOpen();
  m_dbAttempted = true;
  const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
  QDir().mkpath(dir);
  m_db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_dbName);
  m_db.setDatabaseName(QDir(dir).filePath(QStringLiteral("slop-verdicts.sqlite")));
  if (!m_db.open())
    return false;
  QSqlQuery query(m_db);
  query.exec("PRAGMA cache_size=-512");
  if (!query.exec("CREATE TABLE IF NOT EXISTS verdicts ("
                  "id TEXT PRIMARY KEY, probability REAL NOT NULL,"
                  "seconds REAL NOT NULL, scored_at INTEGER NOT NULL)")) {
    m_db.close();
    return false;
  }
  query.exec("CREATE INDEX IF NOT EXISTS verdicts_scored_at ON verdicts(scored_at)");
  return true;
}

float SlopDetector::lookup(const QString &trackId) const {
  const auto cached = m_cache.constFind(trackId);
  if (cached != m_cache.cend())
    return *cached;
  float probability = -1.0f;
  if (ensureDatabase()) {
    QSqlQuery query(m_db);
    query.prepare("SELECT probability FROM verdicts WHERE id=?");
    query.addBindValue(trackId);
    if (query.exec() && query.next())
      probability = query.value(0).toFloat();
  }
  if (m_cache.size() >= kMaxCached)
    m_cache.clear();
  m_cache.insert(trackId, probability);
  return probability;
}

void SlopDetector::feed(const QString &trackId, const QAudioBuffer &buffer) {
  if (!enabled() || trackId.isEmpty() || lookup(trackId) >= 0.0f ||
      buffer.format().sampleFormat() != QAudioFormat::Float || buffer.frameCount() <= 0)
    return;
  const int rate = buffer.format().sampleRate();
  const int channels = buffer.format().channelCount();
  auto it = m_sessions.find(trackId);
  if (it != m_sessions.end() && (it->sampleRate != rate || it->channels != channels)) {
    delete it->handle;
    m_sessions.erase(it);
    it = m_sessions.end();
  }
  if (it == m_sessions.end()) {
    while (m_sessions.size() >= kMaxSessions) {
      auto stalest = m_sessions.begin();
      for (auto candidate = m_sessions.begin(); candidate != m_sessions.end(); ++candidate)
        if (candidate->lastFeed < stalest->lastFeed)
          stalest = candidate;
      finish(stalest.key());
    }
    Session session;
    session.handle = SlopFingerprintDetector::create(rate, channels).release();
    if (!session.handle)
      return;
    session.sampleRate = rate;
    session.channels = channels;
    session.nextCheck = kFirstCheckSeconds;
    it = m_sessions.insert(trackId, session);
  }
  it->lastFeed = ++m_feedCounter;
  it->handle->push(buffer.constData<float>(), static_cast<size_t>(buffer.frameCount()));
  const float seconds = it->handle->seconds();
  const bool full = it->handle->isFull();
  if (seconds < it->nextCheck && !full)
    return;
  it->nextCheck = seconds + kCheckInterval;
  const auto verdict = it->handle->verdict();
  if (!verdict)
    return;
  // Fakeprint evidence only accumulates, so an early crossing is already conclusive.
  if (verdict->probability >= m_threshold || full)
    record(trackId, *verdict);
}

void SlopDetector::finish(const QString &trackId) {
  const auto it = m_sessions.find(trackId);
  if (it == m_sessions.end())
    return;
  const auto verdict = it->handle->verdict();
  const bool settled = verdict && verdict->seconds >= kSettleSeconds;
  delete it->handle;
  m_sessions.erase(it);
  if (settled)
    record(trackId, *verdict);
}

void SlopDetector::record(const QString &trackId, const SlopVerdict &verdict) {
  // A live session must not later overwrite this with a shorter listen.
  if (const auto session = m_sessions.find(trackId); session != m_sessions.end()) {
    delete session->handle;
    m_sessions.erase(session);
  }
  m_cache.insert(trackId, verdict.probability);
  if (ensureDatabase()) {
    QSqlQuery query(m_db);
    query.prepare("INSERT OR REPLACE INTO verdicts (id,probability,seconds,scored_at) "
                  "VALUES (?,?,?,?)");
    query.addBindValue(trackId);
    query.addBindValue(verdict.probability);
    query.addBindValue(verdict.seconds);
    query.addBindValue(QDateTime::currentSecsSinceEpoch());
    query.exec();
    // A forgotten track is simply analysed again on its next play.
    QSqlQuery trim(m_db);
    trim.prepare("DELETE FROM verdicts WHERE id NOT IN "
                 "(SELECT id FROM verdicts ORDER BY scored_at DESC LIMIT ?)");
    trim.addBindValue(kMaxStored);
    trim.exec();
  }
  ++m_revision;
  emit flagsChanged();
  if (verdict.probability >= m_threshold)
    emit flagged(trackId);
}
