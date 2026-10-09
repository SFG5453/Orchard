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

#include "animated_artwork_store.h"

#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QSqlQuery>
#include <QStandardPaths>
#include <QVariant>

namespace {
qint64 nowSecs() { return QDateTime::currentSecsSinceEpoch(); }
} // namespace

AnimatedArtworkStore::AnimatedArtworkStore(const QString &path)
    : m_connection(QStringLiteral("orchard-animated-artwork-%1")
                       .arg(reinterpret_cast<quintptr>(this))) {
  QString file = path;
  if (file.isEmpty()) {
    file = QDir(QStandardPaths::writableLocation(QStandardPaths::CacheLocation))
               .filePath(QStringLiteral("animated-artwork.sqlite"));
  }
  QDir().mkpath(QFileInfo(file).absolutePath());
  m_db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_connection);
  m_db.setDatabaseName(file);
  if (!m_db.open())
    return;
  QSqlQuery query(m_db);
  query.exec(QStringLiteral("PRAGMA cache_size=-256"));
  // url is empty when every mirror answered and none had motion art.
  if (!query.exec(QStringLiteral(
          "CREATE TABLE IF NOT EXISTS artwork ("
          "key TEXT PRIMARY KEY, url TEXT NOT NULL, source TEXT NOT NULL,"
          "checked_at INTEGER NOT NULL)"))) {
    m_db.close();
    return;
  }
  query.exec(QStringLiteral("CREATE INDEX IF NOT EXISTS artwork_url ON artwork(url)"));
  prune();
}

AnimatedArtworkStore::~AnimatedArtworkStore() {
  m_db.close();
  m_db = QSqlDatabase();
  QSqlDatabase::removeDatabase(m_connection);
}

void AnimatedArtworkStore::prune() {
  QSqlQuery query(m_db);
  query.prepare(QStringLiteral(
      "DELETE FROM artwork WHERE checked_at < ? OR (url = '' AND checked_at < ?)"));
  query.addBindValue(nowSecs() - kFoundTtlSecs);
  query.addBindValue(nowSecs() - kMissingTtlSecs);
  query.exec();
}

std::optional<QString> AnimatedArtworkStore::lookup(const QString &key) const {
  if (!m_db.isOpen())
    return std::nullopt;
  QSqlQuery query(m_db);
  query.prepare(QStringLiteral("SELECT url, checked_at FROM artwork WHERE key = ?"));
  query.addBindValue(key);
  if (!query.exec() || !query.next())
    return std::nullopt;
  const QString url = query.value(0).toString();
  const qint64 age = nowSecs() - query.value(1).toLongLong();
  if (age > (url.isEmpty() ? kMissingTtlSecs : kFoundTtlSecs))
    return std::nullopt;
  return url;
}

void AnimatedArtworkStore::store(const QString &key, const QString &url,
                                 const QString &source, qint64 checkedAtSecs) {
  if (!m_db.isOpen() || key.isEmpty())
    return;
  QSqlQuery query(m_db);
  query.prepare(QStringLiteral(
      "INSERT OR REPLACE INTO artwork (key, url, source, checked_at) VALUES (?, ?, ?, ?)"));
  query.addBindValue(key);
  // Null QStrings bind as SQL NULL; "no art" rows need a real empty string.
  query.addBindValue(url.isNull() ? QStringLiteral("") : url);
  query.addBindValue(source.isNull() ? QStringLiteral("") : source);
  query.addBindValue(checkedAtSecs < 0 ? nowSecs() : checkedAtSecs);
  query.exec();
}

int AnimatedArtworkStore::forgetUrl(const QString &url) {
  if (!m_db.isOpen() || url.isEmpty())
    return 0;
  QSqlQuery query(m_db);
  query.prepare(QStringLiteral("DELETE FROM artwork WHERE url = ?"));
  query.addBindValue(url);
  return query.exec() ? query.numRowsAffected() : 0;
}

int AnimatedArtworkStore::forgetMisses() {
  if (!m_db.isOpen())
    return 0;
  QSqlQuery query(m_db);
  return query.exec(QStringLiteral("DELETE FROM artwork WHERE url = ''")) ? query.numRowsAffected() : 0;
}
