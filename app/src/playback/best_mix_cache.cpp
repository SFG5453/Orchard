/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

#include "best_mix_controller.h"
#include <QCborValue>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QSqlQuery>

namespace {
QByteArray cbor(const QJsonObject &value) {
  return QCborValue::fromJsonValue(QJsonValue(value)).toCbor();
}
QJsonObject objectFromCbor(const QByteArray &bytes) {
  return QCborValue::fromCbor(bytes).toJsonValue().toObject();
}
} // namespace

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
