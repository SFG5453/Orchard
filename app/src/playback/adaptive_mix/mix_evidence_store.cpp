/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

#include "mix_evidence_store.h"
#include <QCborValue>
#include <QDateTime>
#include <QDir>
#include <QSqlQuery>
#include <QStandardPaths>

namespace {
// The worker's measured::VERSION; rows of another version are measured again.
constexpr int kEvidenceVersion = 1;
constexpr qint64 kMaxBytes = 32LL * 1024 * 1024;
// Player and catalog durations of one song disagree by a second or so.
constexpr double kDurationSlack = 2.0;

QByteArray cbor(const QJsonObject &value) {
  return QCborValue::fromJsonValue(QJsonValue(value)).toCbor();
}
} // namespace

MixEvidenceStore::MixEvidenceStore(const QString &connection) : m_connection(connection) {}

MixEvidenceStore::~MixEvidenceStore() {
  if (!m_opened)
    return;
  m_db = QSqlDatabase();
  QSqlDatabase::removeDatabase(m_connection);
}

bool MixEvidenceStore::open() {
  if (m_opened)
    return m_db.isOpen();
  m_opened = true;
  const QDir root(QStandardPaths::writableLocation(QStandardPaths::CacheLocation));
  const QString dir = root.filePath("adaptive-mix");
  QDir().mkpath(dir);
  m_db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_connection);
  m_db.setDatabaseName(QDir(dir).filePath("evidence.sqlite"));
  // Playback and Best Mix hold their own connections to this file.
  m_db.setConnectOptions(QStringLiteral("QSQLITE_BUSY_TIMEOUT=2000"));
  if (!m_db.open())
    return false;
  QSqlQuery query(m_db);
  query.exec("PRAGMA cache_size=-1024");
  if (query.exec("PRAGMA user_version") && query.next() &&
      query.value(0).toInt() != kEvidenceVersion) {
    query.finish();
    query.exec("DROP TABLE IF EXISTS evidence");
    query.exec(QStringLiteral("PRAGMA user_version=%1").arg(kEvidenceVersion));
  }
  query.finish();
  if (!query.exec("CREATE TABLE IF NOT EXISTS evidence (id TEXT PRIMARY KEY, duration REAL NOT NULL,"
                  " evidence BLOB NOT NULL, beat_model BLOB NOT NULL, used_at INTEGER NOT NULL)")) {
    m_db.close();
    return false;
  }
  query.exec("CREATE INDEX IF NOT EXISTS evidence_used_at ON evidence(used_at)");
  return true;
}

QByteArray MixEvidenceStore::read(const char *column, const QString &id, double duration) {
  if (id.isEmpty() || !open())
    return {};
  QSqlQuery query(m_db);
  query.prepare(QStringLiteral("SELECT %1 FROM evidence WHERE id=? AND ABS(duration-?)<=?")
                    .arg(QLatin1String(column)));
  query.addBindValue(id);
  query.addBindValue(duration);
  query.addBindValue(kDurationSlack);
  if (!query.exec() || !query.next())
    return {};
  const QByteArray bytes = query.value(0).toByteArray();
  query.finish();
  QSqlQuery touch(m_db);
  touch.prepare("UPDATE evidence SET used_at=? WHERE id=?");
  touch.addBindValue(QDateTime::currentSecsSinceEpoch());
  touch.addBindValue(id);
  touch.exec();
  return bytes;
}

QJsonObject MixEvidenceStore::evidence(const QString &id, double duration) {
  return QCborValue::fromCbor(read("evidence", id, duration)).toJsonValue().toObject();
}

QJsonObject MixEvidenceStore::beatModel(const QString &id, double duration) {
  return QCborValue::fromCbor(read("beat_model", id, duration)).toJsonValue().toObject();
}

void MixEvidenceStore::store(const QString &id, double duration, const QJsonObject &measured) {
  const QJsonObject beatModel = measured.value("beatModel").toObject();
  if (id.isEmpty() || measured.isEmpty() || beatModel.isEmpty() || !open())
    return;
  QSqlQuery query(m_db);
  query.prepare("INSERT OR REPLACE INTO evidence (id,duration,evidence,beat_model,used_at)"
                " VALUES (?,?,?,?,?)");
  query.addBindValue(id);
  query.addBindValue(duration);
  query.addBindValue(cbor(measured));
  query.addBindValue(cbor(beatModel));
  query.addBindValue(QDateTime::currentSecsSinceEpoch());
  if (query.exec())
    trim();
}

void MixEvidenceStore::trim() {
  QSqlQuery size(m_db);
  if (!size.exec("SELECT COALESCE(SUM(length(evidence)+length(beat_model)),0) FROM evidence") ||
      !size.next())
    return;
  qint64 bytes = size.value(0).toLongLong();
  size.finish();
  while (bytes > kMaxBytes) {
    QSqlQuery oldest(m_db);
    if (!oldest.exec("SELECT id,length(evidence)+length(beat_model) FROM evidence"
                     " ORDER BY used_at LIMIT 1") ||
        !oldest.next())
      return;
    const QVariant id = oldest.value(0);
    bytes -= oldest.value(1).toLongLong();
    oldest.finish();
    QSqlQuery remove(m_db);
    remove.prepare("DELETE FROM evidence WHERE id=?");
    remove.addBindValue(id);
    if (!remove.exec())
      return;
  }
}
