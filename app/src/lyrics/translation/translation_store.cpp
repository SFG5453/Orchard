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

#include "translation_store.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QSqlQuery>
#include <QStandardPaths>
#include <QStringList>
#include <QVariant>

TranslationStore::TranslationStore(const QString &path)
    : m_path(path), m_connection(QStringLiteral("orchard-lyric-translations-%1").arg(reinterpret_cast<quintptr>(this))) {
  if (m_path.isEmpty())
    m_path = QDir(QStandardPaths::writableLocation(QStandardPaths::CacheLocation))
                 .filePath(QStringLiteral("lyric-translations.sqlite"));
}

TranslationStore::~TranslationStore() {
  if (!m_tried)
    return;
  m_db.close();
  m_db = QSqlDatabase();
  QSqlDatabase::removeDatabase(m_connection);
}

bool TranslationStore::open() {
  if (m_tried)
    return m_db.isOpen();
  m_tried = true;
  QDir().mkpath(QFileInfo(m_path).absolutePath());
  m_db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_connection);
  m_db.setDatabaseName(m_path);
  if (!m_db.open())
    return false;
  QSqlQuery query(m_db);
  query.exec(QStringLiteral("PRAGMA cache_size=-256"));
  if (!query.exec(QStringLiteral("CREATE TABLE IF NOT EXISTS line ("
                                 "key TEXT PRIMARY KEY, translation TEXT NOT NULL, used_at INTEGER NOT NULL)"))) {
    m_db.close();
    return false;
  }
  query.exec(QStringLiteral("CREATE INDEX IF NOT EXISTS line_used ON line(used_at)"));
  return true;
}

QString TranslationStore::key(const QString &revision, const QString &text) {
  return QString::fromLatin1(
      QCryptographicHash::hash((revision + QLatin1Char('\n') + text).toUtf8(), QCryptographicHash::Sha1).toHex());
}

QHash<QString, QString> TranslationStore::lookup(const QString &revision, const QStringList &texts) {
  QHash<QString, QString> found;
  if (texts.isEmpty() || !open())
    return found;
  const qint64 now = QDateTime::currentSecsSinceEpoch();
  m_db.transaction();
  QSqlQuery select(m_db);
  select.prepare(QStringLiteral("SELECT translation FROM line WHERE key = ?"));
  QSqlQuery touch(m_db);
  touch.prepare(QStringLiteral("UPDATE line SET used_at = ? WHERE key = ?"));
  for (const QString &text : texts) {
    if (found.contains(text))
      continue;
    const QString k = key(revision, text);
    select.addBindValue(k);
    if (select.exec() && select.next()) {
      found.insert(text, select.value(0).toString());
      touch.addBindValue(now);
      touch.addBindValue(k);
      touch.exec();
    }
  }
  m_db.commit();
  return found;
}

void TranslationStore::store(const QString &revision, const QString &text, const QString &translation) {
  if (!open())
    return;
  QSqlQuery query(m_db);
  query.prepare(QStringLiteral("INSERT OR REPLACE INTO line (key, translation, used_at) VALUES (?, ?, ?)"));
  query.addBindValue(key(revision, text));
  query.addBindValue(translation);
  query.addBindValue(QDateTime::currentSecsSinceEpoch());
  query.exec();
  // Prune now and then; least recently shown lines go first.
  if (++m_writes % 500 == 0)
    query.exec(QStringLiteral("DELETE FROM line WHERE key IN (SELECT key FROM line ORDER BY used_at DESC LIMIT -1 OFFSET %1)")
                   .arg(kMaxRows));
}
