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

#include "file_fetch.h"

#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSaveFile>

FileFetch::FileFetch(QNetworkAccessManager *network, const QUrl &url, const QString &path, qint64 maxBytes,
                     int timeoutMs, QObject *parent)
    : QObject(parent), m_network(network), m_url(url), m_path(path), m_maxBytes(maxBytes),
      m_timeoutMs(timeoutMs) {}

FileFetch::~FileFetch() {
  m_done = true;
  abort();
}

void FileFetch::start() {
  if (!m_url.isValid() || (m_url.scheme() != QLatin1String("https") && m_url.scheme() != QLatin1String("http"))) {
    fail();
    return;
  }
  m_file = new QSaveFile(m_path, this);
  if (!m_file->open(QIODevice::WriteOnly)) {
    fail();
    return;
  }
  QNetworkRequest request(m_url);
  request.setTransferTimeout(m_timeoutMs);
  request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
  m_reply = m_network->get(request);
  connect(m_reply, &QNetworkReply::readyRead, this, [this] {
    if (!m_reply)
      return;
    const QByteArray bytes = m_reply->readAll();
    m_received += bytes.size();
    if (m_received > m_maxBytes || m_file->write(bytes) != bytes.size())
      fail();
  });
  connect(m_reply, &QNetworkReply::finished, this, [this] {
    if (!m_reply || m_done)
      return;
    const int status = m_reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const bool ok = m_reply->error() == QNetworkReply::NoError && status >= 200 && status < 300 && m_received > 0;
    m_reply->deleteLater();
    m_reply = nullptr;
    if (!ok || !m_file->commit()) {
      fail();
      return;
    }
    m_done = true;
    emit finished(true);
  });
}

void FileFetch::abort() {
  if (m_reply) {
    m_reply->disconnect(this);
    m_reply->abort();
    m_reply->deleteLater();
    m_reply = nullptr;
  }
  if (m_file && m_file->isOpen())
    m_file->cancelWriting();
}

void FileFetch::fail() {
  abort();
  if (m_done)
    return;
  m_done = true;
  emit finished(false);
}
