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
#include <QSqlDatabase>
#include <QString>
#include <QStringList>

// Translated lyric lines keyed by pack revision and source text. Lines repeat across a song
// (and across remixes), so caching per line beats caching per track. Opened on first use.
class TranslationStore final {
public:
  static constexpr int kMaxRows = 50000;

  // Empty path means <CacheLocation>/lyric-translations.sqlite.
  explicit TranslationStore(const QString &path = QString());
  ~TranslationStore();
  TranslationStore(const TranslationStore &) = delete;
  TranslationStore &operator=(const TranslationStore &) = delete;

  // Cached translations for whichever of texts are known.
  QHash<QString, QString> lookup(const QString &revision, const QStringList &texts);
  void store(const QString &revision, const QString &text, const QString &translation);

private:
  bool open();
  static QString key(const QString &revision, const QString &text);

  QString m_path;
  QString m_connection;
  QSqlDatabase m_db;
  bool m_tried{false};
  int m_writes{0};
};
