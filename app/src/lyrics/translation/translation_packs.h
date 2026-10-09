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

#include <QCryptographicHash>
#include <QObject>
#include <QPointer>
#include <QString>
#include <QVariantList>

#include "translation/translation_pack_table.h"

class QNetworkAccessManager;
class QNetworkReply;
class QSaveFile;

// Downloadable source->English model packs, fetched one language at a time on first use.
class TranslationPacks final : public QObject {
  Q_OBJECT
public:
  explicit TranslationPacks(QNetworkAccessManager *network, QObject *parent = nullptr);

  // <AppDataLocation>/translation, or ORCHARD_TRANSLATION_DIR.
  static QString rootDirectory();
  static const TranslationPack *find(const QString &id);
  // The pack for a language at a quality, falling back to standard where no larger model exists.
  static const TranslationPack *resolve(const QString &source, const QString &quality);
  [[nodiscard]] bool available(const QString &id) const;
  [[nodiscard]] bool installed(const QString &id) const;
  [[nodiscard]] QString directory(const QString &id) const;
  [[nodiscard]] QString downloading() const { return m_id; }
  // Starts a download unless installed or already running. Emits ready() or failed().
  void ensure(const QString &id);
  void cancel();
  void remove(const QString &id);
  // Settings rows: code (pack id), name, quality, sizeBytes, installed, available, credit.
  [[nodiscard]] QVariantList list() const;
  [[nodiscard]] qint64 installedBytes() const;

signals:
  void ready(const QString &id);
  void failed(const QString &id, const QString &message);
  void progress(const QString &id, double fraction);
  void changed();

private:
  void fetchNext();
  // Writes buffered reply bytes; false after it failed the download.
  bool consume();
  void finishFile();
  void fail(const QString &message);
  [[nodiscard]] QString stagingDirectory() const;

  QNetworkAccessManager *m_network;
  QString m_root;
  QString m_id;
  int m_fileIndex{0};
  qint64 m_doneBytes{0};
  qint64 m_totalBytes{0};
  QPointer<QNetworkReply> m_reply;
  QSaveFile *m_file{nullptr};
  QCryptographicHash m_hash{QCryptographicHash::Sha256};
};
