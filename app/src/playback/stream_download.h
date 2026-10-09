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

#include <QJsonObject>
#include <QObject>
#include <QPointer>

class QNetworkAccessManager;
class QNetworkReply;
class QSaveFile;

// Fetches a resolved googlevideo stream to disk in validated 1 MB ranges.
// One big GET gets throttled to a trickle; polite little bites do not.
class StreamDownload final : public QObject {
  Q_OBJECT
public:
  StreamDownload(QNetworkAccessManager *network, const QJsonObject &stream, const QString &path,
                 qint64 maxBytes, QObject *parent = nullptr);
  ~StreamDownload() override;
  // Emits finished() exactly once, possibly before returning when the stream is unusable.
  void start();
  void abort();
signals:
  // After each validated chunk is written.
  void progress(qint64 received, qint64 total);
  void finished(bool ok);

private:
  void fetchChunk();
  void fail();
  QNetworkAccessManager *m_network;
  QJsonObject m_stream;
  QString m_path;
  qint64 m_maxBytes;
  QSaveFile *m_file{nullptr};
  QPointer<QNetworkReply> m_reply;
  qint64 m_expected{0};
  qint64 m_offset{0};
  qint64 m_chunkEnd{0};
  qint64 m_chunkReceived{0};
  bool m_done{false};
};
