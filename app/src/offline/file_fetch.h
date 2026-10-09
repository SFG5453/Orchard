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

#include <QObject>
#include <QPointer>
#include <QUrl>

class QNetworkAccessManager;
class QNetworkReply;
class QSaveFile;

// Streams one HTTP(S) resource to disk. Audio uses StreamDownload for range validation.
class FileFetch final : public QObject {
  Q_OBJECT
public:
  FileFetch(QNetworkAccessManager *network, const QUrl &url, const QString &path, qint64 maxBytes,
            int timeoutMs, QObject *parent = nullptr);
  ~FileFetch() override;
  // Emits finished() exactly once, possibly before returning.
  void start();
  void abort();
signals:
  void finished(bool ok);

private:
  void fail();
  QNetworkAccessManager *m_network;
  QUrl m_url;
  QString m_path;
  qint64 m_maxBytes;
  int m_timeoutMs;
  QSaveFile *m_file{nullptr};
  QPointer<QNetworkReply> m_reply;
  qint64 m_received{0};
  bool m_done{false};
};
