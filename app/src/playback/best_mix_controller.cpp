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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "best_mix_controller.h"
#include "auth/auth_manager.h"
#include "providers/youtube/youtube_provider.h"
#include <QCborValue>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QProcess>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QSqlQuery>
#include <QStandardPaths>
#include <QThread>
#include <QTimer>
#include <QUrl>
#include <algorithm>
#include <memory>

struct BestMixController::Job final : QObject {
  explicit Job(const QVariantMap &track, QObject *parent) : QObject(parent), track(track) {}
  QVariantMap track;
  quint64 request{0};
  QPointer<QNetworkReply> reply;
  QPointer<QProcess> worker;
  QSaveFile *file{nullptr};
  QJsonObject stream;
  qint64 expected{0};
  qint64 offset{0};
  qint64 chunkEnd{0};
  qint64 chunkReceived{0};
  QByteArray output;
};

namespace {
double seconds(const QVariantMap &track) {
  const double direct = track.value("durationSeconds").toDouble();
  if (direct > 0) return direct;
  const auto parts = track.value("duration").toString().split(':');
  double result = 0;
  for (int i = 0; i < parts.size(); ++i) {
    bool valid = false;
    const int n = parts.at(i).toInt(&valid);
    if (!valid || n < 0 || (i > 0 && n >= 60)) return 0;
    result = result * 60 + n;
  }
  return result;
}
QString workerPath() {
  QString path = QDir(QCoreApplication::applicationDirPath()).filePath("orchard-adaptive-mix");
#ifdef Q_OS_WIN
  path += ".exe";
#endif
  return path;
}
// Bump when the worker adds a feature. Rows re-analyze from cached audio, so no download.
// Version 1: subBassRatio, so the shortlist can tell boom-bap from trap.
// Version 2: shared native-rate decode and worker-built summaries, identical to Android.
constexpr int kFeatureVersion = 2;
QByteArray cbor(const QJsonObject &value) {
  return QCborValue::fromJsonValue(QJsonValue(value)).toCbor();
}
QJsonObject objectFromCbor(const QByteArray &bytes) {
  return QCborValue::fromCbor(bytes).toJsonValue().toObject();
}
} // namespace

BestMixController::BestMixController(YouTubeProvider *provider, AuthManager *auth, QObject *parent)
    : QObject(parent), m_provider(provider), m_auth(auth) {
  const QDir cacheRoot(QStandardPaths::writableLocation(QStandardPaths::CacheLocation));
  m_cacheDir = cacheRoot.filePath("best-mix-v2");
  // v1 features are windowed and vocal-blind. Keep its audio, re-analyze, sort with both ears.
  const QString legacy = cacheRoot.filePath("best-mix-v1");
  if (QDir(legacy).exists() && !QDir(m_cacheDir).exists() && QDir().rename(legacy, m_cacheDir))
    QFile::remove(QDir(m_cacheDir).filePath("features.sqlite"));
  QDir(legacy).removeRecursively();
  QDir().mkpath(m_cacheDir);
  m_dbName = QStringLiteral("orchard-bestmix-%1").arg(reinterpret_cast<quintptr>(this));
  m_db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_dbName);
  m_db.setDatabaseName(QDir(m_cacheDir).filePath("features.sqlite"));
  if (m_db.open()) {
    QSqlQuery query(m_db);
    query.exec("PRAGMA cache_size=-2048");
    query.exec("PRAGMA mmap_size=0");
    if (query.exec("PRAGMA user_version") && query.next() &&
        query.value(0).toInt() < kFeatureVersion) {
      query.finish();
      query.exec("DROP TABLE IF EXISTS features");
      query.exec(QStringLiteral("PRAGMA user_version=%1").arg(kFeatureVersion));
    }
    query.finish();
    if (!query.exec("CREATE TABLE IF NOT EXISTS features ("
                    "id TEXT PRIMARY KEY, duration REAL NOT NULL, head BLOB NOT NULL,"
                    "tail BLOB NOT NULL, head_summary BLOB NOT NULL,"
                    "tail_summary BLOB NOT NULL, used_at INTEGER NOT NULL)"))
      m_db.close();
    else
      query.exec("CREATE INDEX IF NOT EXISTS features_used_at ON features(used_at)");
  }
  connect(provider, &YouTubeProvider::resultReady, this, &BestMixController::resolved);
  connect(provider, &YouTubeProvider::requestFailed, this,
          [this](quint64 id, const QString &) { failed(id); });
}
BestMixController::~BestMixController() {
  cancel();
  m_db = QSqlDatabase();
  QSqlDatabase::removeDatabase(m_dbName);
}

QString BestMixController::cachePath(const QString &id, const QString &suffix) const {
  const auto hash = QCryptographicHash::hash(id.toUtf8(), QCryptographicHash::Sha256).toHex();
  return QDir(m_cacheDir).filePath(QString::fromLatin1(hash) + suffix);
}
bool BestMixController::hasCachedFeatures(const QString &id, double duration) {
  if (!m_db.isOpen()) return false;
  QSqlQuery query(m_db);
  query.prepare("SELECT 1 FROM features WHERE id=? AND ABS(duration-?)<=1 LIMIT 1");
  query.addBindValue(id);
  query.addBindValue(duration);
  if (query.exec() && query.next()) {
    query.finish();
    QSqlQuery touch(m_db);
    touch.prepare("UPDATE features SET used_at=? WHERE id=?");
    touch.addBindValue(QDateTime::currentSecsSinceEpoch());
    touch.addBindValue(id);
    touch.exec();
    return true;
  }
  // Migrate the old cache one song at a time. It never builds a 50-song JSON
  // heap, and a repeat sort still avoids another network trip.
  QFile legacy(cachePath(id, ".json"));
  if (!legacy.open(QIODevice::ReadOnly) || legacy.size() > 512 * 1024) return false;
  const QJsonObject record = QJsonDocument::fromJson(legacy.readAll()).object();
  if (qAbs(record.value("duration").toDouble() - duration) > 1.0) return false;
  return storeFeatures(id, duration, record.value("features").toObject());
}
bool BestMixController::storeFeatures(const QString &id, double duration,
                                      const QJsonObject &features) {
  if (!m_db.isOpen()) return false;
  const QJsonObject head = features.value("head").toObject();
  const QJsonObject tail = features.value("tail").toObject();
  // The worker builds the shortlist summaries, the same code Android runs.
  const QJsonObject headSummary = features.value("headSummary").toObject();
  const QJsonObject tailSummary = features.value("tailSummary").toObject();
  if (head.isEmpty() || tail.isEmpty() || headSummary.isEmpty() || tailSummary.isEmpty())
    return false;
  QSqlQuery query(m_db);
  query.prepare("INSERT OR REPLACE INTO features "
                "(id,duration,head,tail,head_summary,tail_summary,used_at) "
                "VALUES (?,?,?,?,?,?,?)");
  query.addBindValue(id);
  query.addBindValue(duration);
  query.addBindValue(cbor(head));
  query.addBindValue(cbor(tail));
  query.addBindValue(cbor(headSummary));
  query.addBindValue(cbor(tailSummary));
  query.addBindValue(QDateTime::currentSecsSinceEpoch());
  if (!query.exec()) return false;
  QFile::remove(cachePath(id, ".json"));
  return true;
}
QJsonObject BestMixController::summary(const QString &id) const {
  QSqlQuery query(m_db);
  query.prepare("SELECT head_summary,tail_summary FROM features WHERE id=?");
  query.addBindValue(id);
  if (!query.exec() || !query.next()) return {};
  return QJsonObject{{"head", objectFromCbor(query.value(0).toByteArray())},
                     {"tail", objectFromCbor(query.value(1).toByteArray())}};
}
QJsonObject BestMixController::edge(const QString &id, bool tail) const {
  QSqlQuery query(m_db);
  query.prepare(tail ? "SELECT tail FROM features WHERE id=?"
                     : "SELECT head FROM features WHERE id=?");
  query.addBindValue(id);
  if (!query.exec() || !query.next()) return {};
  return objectFromCbor(query.value(0).toByteArray());
}
void BestMixController::trimCache() const {
  QDir dir(m_cacheDir);
  auto prune = [&dir](const QString &pattern, qint64 limit) {
    const auto files = dir.entryInfoList({pattern}, QDir::Files, QDir::Time | QDir::Reversed);
    qint64 bytes = 0;
    for (const auto &file : files) bytes += file.size();
    for (const auto &file : files) {
      if (bytes <= limit) break;
      bytes -= file.size();
      QFile::remove(file.absoluteFilePath());
    }
  };
  // These are our own hashed cache entries; no one else's mixtape gets swept.
  prune("*.audio", 128LL * 1024 * 1024);
  if (!m_db.isOpen()) return;
  QSqlQuery size(m_db);
  if (!size.exec("SELECT COALESCE(SUM(length(head)+length(tail)+"
                 "length(head_summary)+length(tail_summary)),0) FROM features") || !size.next()) return;
  qint64 bytes = size.value(0).toLongLong();
  size.finish();
  while (bytes > 32LL * 1024 * 1024) {
    QSqlQuery oldest(m_db);
    if (!oldest.exec("SELECT id,length(head)+length(tail)+length(head_summary)+"
                     "length(tail_summary) FROM features ORDER BY used_at LIMIT 1") || !oldest.next()) break;
    const QVariant id = oldest.value(0);
    const qint64 rowBytes = oldest.value(1).toLongLong();
    oldest.finish();
    QSqlQuery remove(m_db);
    remove.prepare("DELETE FROM features WHERE id=?");
    remove.addBindValue(id);
    if (!remove.exec()) break;
    bytes -= rowBytes;
  }
}
void BestMixController::cancel() {
  const bool wasBusy = m_busy;
  m_busy = false;
  if (m_sortWorker) {
    m_sortWorker->disconnect(this);
    m_sortWorker->kill();
    m_sortWorker->deleteLater();
    m_sortWorker = nullptr;
  }
  m_resolving.clear();
  m_pending.clear();
  m_ready.clear();
  m_downloading = false;
  for (Job *job : std::as_const(m_active)) {
    if (job->reply) { job->reply->disconnect(this); job->reply->abort(); job->reply->deleteLater(); }
    if (job->worker) { job->worker->disconnect(this); job->worker->kill(); }
    if (job->file) job->file->cancelWriting();
    job->deleteLater();
  }
  m_active.clear();
  if (wasBusy) emit changed();
}
void BestMixController::start(const QVariantList &queue, const QVariantMap &current) {
  cancel();
  if (!m_db.isOpen()) {
    m_error = tr("Best Mix feature database is unavailable.");
    emit changed();
    return;
  }
  m_snapshot = queue.mid(0, 50);
  m_current = current;
  m_available.clear();
  m_error.clear();
  m_completed = 0;
  m_total = 0;
  QSet<QString> ids;
  QVariantList tracks = m_snapshot;
  if (!current.isEmpty()) tracks.prepend(current);
  for (const auto &value : tracks) {
    const auto track = value.toMap();
    const QString id = track.value("id").toString();
    const double duration = seconds(track);
    if (id.isEmpty() || ids.contains(id) || duration < 2 || duration > 7200) continue;
    ids.insert(id);
    if (hasCachedFeatures(id, duration)) m_available.insert(id);
    else m_pending.append(track);
  }
  m_total = ids.size();
  m_completed = m_total - m_pending.size();
  m_downloaded = m_completed;
  m_downloading = true;
  m_busy = true;
  emit changed();
  pump();
}
void BestMixController::pump() {
  if (!m_busy) return;
  // Download every song before analyzing any, so decoding never holds a network slot.
  while (m_downloading && m_active.size() < 6 && !m_pending.isEmpty()) {
    const QVariantMap track = m_pending.takeFirst();
    const QFileInfo cached(cachePath(track.value("id").toString(), ".audio"));
    if (cached.size() > 0 && cached.size() <= 24 * 1024 * 1024) {
      m_ready.append(track);
      ++m_downloaded;
      emit changed();
      continue;
    }
    Job *job = new Job(track, this);
    m_active.append(job);
    // Saver resolution uses the existing authenticated provider session. No
    // cookie or expiring signed URL goes to logs, disk, or the Rust worker.
    job->request = m_provider->invoke("playback.resolve", QJsonObject{
        {"track", QJsonObject::fromVariantMap(job->track)},
        {"session", m_auth->sessionObject()}, {"streamQuality", "saver"}});
    m_resolving.insert(job->request, job);
  }
  if (m_downloading) {
    if (!m_active.isEmpty() || !m_pending.isEmpty()) return;
    m_downloading = false;
    emit changed();
  }
  // Each worker holds one decoded song (~40 MB) and one core.
  const int workers = qBound(1, QThread::idealThreadCount() / 2, 4);
  while (m_active.size() < workers && !m_ready.isEmpty()) {
    Job *job = new Job(m_ready.takeFirst(), this);
    m_active.append(job);
    analyze(job);
  }
  if (m_active.isEmpty() && m_ready.isEmpty()) sort();
}
void BestMixController::downloadFinished(Job *job) {
  if (!m_active.removeOne(job)) return;
  m_ready.append(job->track);
  ++m_downloaded;
  job->deleteLater();
  emit changed();
  pump();
}
void BestMixController::resolved(quint64 request, const QJsonValue &result) {
  Job *job = m_resolving.take(request);
  if (!job || !m_busy) return;
  download(job, result.toObject());
}
void BestMixController::failed(quint64 request) {
  Job *job = m_resolving.take(request);
  if (job && m_busy) complete(job, {});
}
void BestMixController::download(Job *job, const QJsonObject &stream) {
  const QUrl url(stream.value("url").toString());
  const double size = stream.value("contentLength").toDouble();
  if (url.scheme() != "https" || !(url.host().endsWith(".googlevideo.com") || url.host().endsWith(".c.youtube.com")) ||
      size < 1 || size > 24 * 1024 * 1024 || size != qFloor(size)) {
    complete(job, {}); return;
  }
  job->expected = static_cast<qint64>(size);
  job->stream = stream;
  job->file = new QSaveFile(cachePath(job->track.value("id").toString(), ".audio"), job);
  if (!job->file->open(QIODevice::WriteOnly)) { complete(job, {}); return; }
  fetchChunk(job);
}
void BestMixController::fetchChunk(Job *job) {
  const QJsonObject &stream = job->stream;
  const QUrl url(stream.value("url").toString());
  job->chunkEnd = qMin(job->expected - 1, job->offset + 1024 * 1024 - 1);
  job->chunkReceived = 0;
  QNetworkRequest request(url);
  request.setTransferTimeout(30000);
  request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
  request.setRawHeader("Accept-Encoding", "identity");
  request.setRawHeader("Range", "bytes=" + QByteArray::number(job->offset)
                                + '-' + QByteArray::number(job->chunkEnd));
  request.setRawHeader("User-Agent", stream.value("userAgent").toString().toUtf8());
  const QByteArray origin = stream.value("origin").toString().toUtf8();
  if (!origin.isEmpty()) {
    request.setRawHeader("Origin", origin);
    request.setRawHeader("Referer", origin + '/');
  }
  job->reply = m_network.get(request);
  job->reply->setReadBufferSize(128 * 1024);
  QPointer<Job> guard(job);
  auto drain = [this, guard] {
    if (!guard || !guard->reply || !guard->file) return;
    const QByteArray bytes = guard->reply->readAll();
    guard->chunkReceived += bytes.size();
    if (guard->chunkReceived > guard->chunkEnd - guard->offset + 1 ||
        guard->file->write(bytes) != bytes.size()) {
      complete(guard, {});
    }
  };
  connect(job->reply, &QNetworkReply::readyRead, this, drain);
  connect(job->reply, &QNetworkReply::finished, this, [this, guard, drain] {
    if (!guard || !m_busy) return;
    drain();
    if (!guard || !guard->reply) return;
    const int status = guard->reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    static const QRegularExpression rangePattern(
        QStringLiteral("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$"));
    const auto range = rangePattern.match(QString::fromLatin1(
        guard->reply->rawHeader("Content-Range").trimmed()));
    bool startOk = false, endOk = false, totalOk = false;
    const qint64 start = range.captured(1).toLongLong(&startOk);
    const qint64 end = range.captured(2).toLongLong(&endOk);
    const qint64 total = range.captured(3).toLongLong(&totalOk);
    const bool unknownTotal = range.captured(3) == QStringLiteral("*");
    const bool valid = guard->reply->error() == QNetworkReply::NoError &&
        status == 206 && range.hasMatch() && startOk && endOk &&
        start == guard->offset && end >= start && end <= guard->chunkEnd &&
        guard->chunkReceived == end - start + 1 &&
        (unknownTotal || (totalOk && total == guard->expected));
    guard->reply->deleteLater(); guard->reply = nullptr;
    if (!valid) { complete(guard, {}); return; }
    guard->offset = end + 1;
    if (guard->offset < guard->expected) { fetchChunk(guard); return; }
    if (!guard->file->commit()) { complete(guard, {}); return; }
    downloadFinished(guard);
  });
}
void BestMixController::analyze(Job *job) {
  job->worker = new QProcess(job);
  job->worker->setStandardErrorFile(QProcess::nullDevice());
  const QJsonObject request{{"kind", "bestMixAnalyze"},
      {"path", cachePath(job->track.value("id").toString(), ".audio")},
      {"duration", seconds(job->track)}};
  const QByteArray payload = QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n';
  QPointer<Job> guard(job);
  connect(job->worker, &QProcess::started, this, [guard, payload] {
    if (guard && guard->worker) guard->worker->write(payload);
  });
  connect(job->worker, &QProcess::readyReadStandardOutput, this, [this, guard] {
    if (!guard || !guard->worker) return;
    guard->output += guard->worker->readAllStandardOutput();
    if (guard->output.size() > 512 * 1024) { complete(guard, {}); return; }
    const int end = guard->output.indexOf('\n');
    if (end < 0) return;
    const auto value = QJsonDocument::fromJson(guard->output.left(end)).object();
    complete(guard, value.contains("head") && value.contains("tail") ? value : QJsonObject{});
  });
  connect(job->worker, &QProcess::errorOccurred, this, [this, guard] { if (guard) complete(guard, {}); });
  connect(job->worker, &QProcess::finished, this, [this, guard] { if (guard) complete(guard, {}); });
  job->worker->start(workerPath(), {});
  QTimer::singleShot(60000, this, [this, guard] { if (guard) complete(guard, {}); });
}
void BestMixController::complete(Job *job, const QJsonObject &features) {
  if (!m_active.removeOne(job)) return;
  m_resolving.remove(job->request);
  if (job->reply) { job->reply->disconnect(this); job->reply->abort(); job->reply->deleteLater(); job->reply = nullptr; }
  if (job->worker) { job->worker->disconnect(this); job->worker->kill(); job->worker = nullptr; }
  if (job->file && job->file->isOpen()) job->file->cancelWriting();
  const QString id = job->track.value("id").toString();
  if (!features.isEmpty() && storeFeatures(id, seconds(job->track), features)) {
    m_available.insert(id);
  } else if (m_error.isEmpty()) {
    m_error = tr("Some songs could not be analyzed and will keep their queue position.");
  }
  job->deleteLater();
  // A failed download is also a finished one, so the download count still reaches total.
  if (m_downloading) ++m_downloaded;
  ++m_completed;
  emit changed();
  pump();
}
void BestMixController::sort() {
  if (!m_busy) return;
  const bool hasQueueAnalysis = std::any_of(m_snapshot.cbegin(), m_snapshot.cend(),
      [this](const QVariant &value) {
        return m_available.contains(value.toMap().value("id").toString());
      });
  if (!hasQueueAnalysis) {
    m_busy = false;
    m_error = tr("No songs could be analyzed for Best Mix.");
    emit changed();
    return;
  }
  trimCache();
  QJsonArray summaries;
  m_tempos.clear();
  for (const auto &value : m_snapshot) {
    const QString id = value.toMap().value("id").toString();
    const QJsonObject entry = summary(id);
    // Head tempo is what the listener hears as the song mixes in.
    const double bpm = entry.value("head").toObject().value("bpm").toDouble();
    if (bpm > 0) m_tempos.insert(id, bpm);
    summaries.append(entry);
  }
  const QJsonObject initial = summary(m_current.value("id").toString()).value("tail").toObject();
  const QJsonObject request{{"kind", "bestMixSortLazy"}, {"summaries", summaries}, {"initial", initial}};
  QProcess *worker = new QProcess(this);
  m_sortWorker = worker;
  emit changed();
  worker->setStandardErrorFile(QProcess::nullDevice());
  auto output = std::make_shared<QByteArray>();
  connect(worker, &QProcess::started, this, [worker, request] {
    worker->write(QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n');
  });
  connect(worker, &QProcess::readyReadStandardOutput, this, [this, worker, output] {
    if (!m_busy || m_sortWorker != worker) return;
    *output += worker->readAllStandardOutput();
    if (output->size() > 256 * 1024) { worker->kill(); return; }
    int end = 0;
    while ((end = output->indexOf('\n')) >= 0) {
      const QJsonObject response = QJsonDocument::fromJson(output->left(end)).object();
      output->remove(0, end + 1);
      if (response.contains("needPair")) {
        const QJsonArray pair = response.value("needPair").toArray();
        const int leftIndex = pair.size() == 2 ? pair.at(0).toInt(-2) : -2;
        const int rightIndex = pair.size() == 2 ? pair.at(1).toInt(-1) : -1;
        const bool indicesValid = leftIndex >= -1 && leftIndex < m_snapshot.size() &&
                                  rightIndex >= 0 && rightIndex < m_snapshot.size();
        const QString leftId = leftIndex == -1 ? m_current.value("id").toString() :
                               indicesValid ? m_snapshot.at(leftIndex).toMap().value("id").toString() : QString();
        const QString rightId = indicesValid ? m_snapshot.at(rightIndex).toMap().value("id").toString() : QString();
        const QJsonObject left = indicesValid ? edge(leftId, true) : QJsonObject{};
        const QJsonObject right = indicesValid ? edge(rightId, false) : QJsonObject{};
        const QJsonObject answer = left.isEmpty() || right.isEmpty()
            ? QJsonObject{{"error", "Feature cache entry is unavailable"}}
            : QJsonObject{{"left", left}, {"right", right}};
        worker->write(QJsonDocument(answer).toJson(QJsonDocument::Compact) + '\n');
        continue;
      }
      const auto orderArray = response.value("order").toArray();
      QList<int> order;
      QSet<int> seen;
      for (const auto &value : orderArray) {
        const int index = value.toInt(-1);
        if (index < 0 || index >= m_snapshot.size() || seen.contains(index)) break;
        seen.insert(index); order.append(index);
      }
      const bool valid = order.size() == m_snapshot.size();
      if (!valid) m_error = tr("Best Mix could not order this queue.");
      // Clear the worker before notifying, or QML reads sorting() as still true.
      m_busy = false;
      m_sortWorker = nullptr;
      worker->disconnect(this); worker->kill(); worker->deleteLater();
      emit changed();
      if (valid) emit orderReady(m_snapshot, order);
      return;
    }
  });
  connect(worker, &QProcess::finished, this, [this, worker] {
    const bool owned = m_sortWorker == worker;
    if (owned) m_sortWorker = nullptr;
    if (m_busy) { m_busy = false; m_error = tr("Best Mix worker stopped."); }
    if (owned) emit changed();
    worker->deleteLater();
  });
  connect(worker, &QProcess::errorOccurred, this, [this, worker] {
    if (m_sortWorker != worker) return;
    m_sortWorker = nullptr;
    m_busy = false;
    m_error = tr("Best Mix worker could not start.");
    emit changed();
    worker->disconnect(this);
    worker->deleteLater();
  });
  worker->start(workerPath(), {});
  // Guarded: the worker is usually deleteLater'd long before this fires.
  QTimer::singleShot(60000, this, [this, guard = QPointer<QProcess>(worker)] {
    if (guard && m_sortWorker == guard && guard->state() != QProcess::NotRunning) guard->kill();
  });
}
