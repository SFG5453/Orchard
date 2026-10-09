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

#include "stream_download.h"

#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QRegularExpression>
#include <QSaveFile>
#include <QUrl>
#include <QtMath>

StreamDownload::StreamDownload(QNetworkAccessManager *network, const QJsonObject &stream,
                               const QString &path, qint64 maxBytes, QObject *parent)
    : QObject(parent), m_network(network), m_stream(stream), m_path(path), m_maxBytes(maxBytes) {}

StreamDownload::~StreamDownload() {
  m_done = true;
  abort();
}

void StreamDownload::start() {
  const QUrl url(m_stream.value("url").toString());
  const double size = m_stream.value("contentLength").toDouble();
  if (url.scheme() != "https" || !url.host().endsWith(".googlevideo.com") || size < 1 ||
      size > m_maxBytes || size != qFloor(size)) {
    fail();
    return;
  }
  m_expected = static_cast<qint64>(size);
  m_file = new QSaveFile(m_path, this);
  if (!m_file->open(QIODevice::WriteOnly)) {
    fail();
    return;
  }
  fetchChunk();
}

void StreamDownload::abort() {
  if (m_reply) {
    m_reply->disconnect(this);
    m_reply->abort();
    m_reply->deleteLater();
    m_reply = nullptr;
  }
  if (m_file && m_file->isOpen())
    m_file->cancelWriting();
}

void StreamDownload::fail() {
  abort();
  if (m_done)
    return;
  m_done = true;
  emit finished(false);
}

void StreamDownload::fetchChunk() {
  m_chunkEnd = qMin(m_expected - 1, m_offset + 1024 * 1024 - 1);
  m_chunkReceived = 0;
  QNetworkRequest request(QUrl(m_stream.value("url").toString()));
  request.setTransferTimeout(30000);
  request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
  request.setRawHeader("Accept-Encoding", "identity");
  request.setRawHeader("Range", "bytes=" + QByteArray::number(m_offset) + '-' +
                                    QByteArray::number(m_chunkEnd));
  request.setRawHeader("User-Agent", m_stream.value("userAgent").toString().toUtf8());
  const QByteArray origin = m_stream.value("origin").toString().toUtf8();
  if (!origin.isEmpty()) {
    request.setRawHeader("Origin", origin);
    request.setRawHeader("Referer", origin + '/');
  }
  m_reply = m_network->get(request);
  m_reply->setReadBufferSize(128 * 1024);
  auto drain = [this] {
    if (!m_reply)
      return false;
    const QByteArray bytes = m_reply->readAll();
    m_chunkReceived += bytes.size();
    if (m_chunkReceived > m_chunkEnd - m_offset + 1 || m_file->write(bytes) != bytes.size()) {
      fail();
      return false;
    }
    return true;
  };
  connect(m_reply, &QNetworkReply::readyRead, this, drain);
  connect(m_reply, &QNetworkReply::finished, this, [this, drain] {
    if (!drain())
      return;
    const int status = m_reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    static const QRegularExpression rangePattern(
        QStringLiteral("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$"));
    const auto range =
        rangePattern.match(QString::fromLatin1(m_reply->rawHeader("Content-Range").trimmed()));
    bool startOk = false, endOk = false, totalOk = false;
    const qint64 start = range.captured(1).toLongLong(&startOk);
    const qint64 end = range.captured(2).toLongLong(&endOk);
    const qint64 total = range.captured(3).toLongLong(&totalOk);
    const bool unknownTotal = range.captured(3) == QStringLiteral("*");
    const bool valid = m_reply->error() == QNetworkReply::NoError && status == 206 &&
                       range.hasMatch() && startOk && endOk && start == m_offset &&
                       end >= start && end <= m_chunkEnd && m_chunkReceived == end - start + 1 &&
                       (unknownTotal || (totalOk && total == m_expected));
    m_reply->deleteLater();
    m_reply = nullptr;
    if (!valid) {
      fail();
      return;
    }
    m_offset = end + 1;
    emit progress(m_offset, m_expected);
    if (m_offset < m_expected) {
      fetchChunk();
      return;
    }
    if (!m_file->commit()) {
      fail();
      return;
    }
    m_done = true;
    emit finished(true);
  });
}
