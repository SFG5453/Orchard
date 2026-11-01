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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */
#include "apple_motion_stream.h"

#include <QJsonObject>
#include <QNetworkReply>
#include <QRegularExpression>

namespace {

const QByteArray kUserAgent = QByteArrayLiteral("Orchard/3.0");
// Covers the first moof; Apple's run a few KB for a three-second fragment.
constexpr qint64 kFragmentHeaderBytes = 32 * 1024;

qint64 totalLength(const QNetworkReply *reply) {
  static const QRegularExpression pattern(QStringLiteral(R"(^bytes \d+-\d+/(\d+)$)"));
  const auto match = pattern.match(QString::fromLatin1(reply->rawHeader("Content-Range").trimmed()));
  return match.hasMatch() ? match.captured(1).toLongLong() : -1;
}

} // namespace

AppleMotionStream::AppleMotionStream(QObject *parent) : QObject(parent) {
  connect(&m_proxy, &AudioStreamProxy::streamFailed, this, [this] {
    if (!m_file.isEmpty())
      settle(QUrl(m_file));
  });
}

void AppleMotionStream::open(const QString &file, const QString &playlist) {
  clear();
  m_file = file;
  const fmp4index::Layout layout = fmp4index::parsePlaylist(playlist);
  if (!layout.isValid()) {
    settle(QUrl(file));
    return;
  }
  QNetworkRequest request{QUrl(file)};
  request.setRawHeader("User-Agent", kUserAgent);
  request.setRawHeader("Range", "bytes=0-" + QByteArray::number(qMin(layout.fileLength(),
      layout.initLength + kFragmentHeaderBytes) - 1));
  request.setTransferTimeout(15000);
  m_reply = m_network.get(request);
  connect(m_reply, &QNetworkReply::finished, this, [this, reply = m_reply.data(), layout] {
    reply->deleteLater();
    if (reply == m_reply)
      receive(reply, layout);
  });
  emit changed();
}

void AppleMotionStream::receive(QNetworkReply *reply, const fmp4index::Layout &layout) {
  m_reply.clear();
  const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
  const QByteArray head = reply->readAll();
  const QByteArray sidx = reply->error() == QNetworkReply::NoError && status == 206 &&
                                  totalLength(reply) == layout.fileLength()
                              ? fmp4index::buildSidx(head, layout)
                              : QByteArray();
  if (sidx.isEmpty()) {
    settle(QUrl(m_file));
    return;
  }
  const QUrl url = m_proxy.open(QJsonObject{
      {QStringLiteral("url"), m_file},
      {QStringLiteral("contentLength"), double(layout.fileLength())},
      {QStringLiteral("mimeType"), QStringLiteral("video/mp4")},
      {QStringLiteral("userAgent"), QString::fromLatin1(kUserAgent)}},
      head.left(layout.initLength) + sidx, layout.initLength);
  settle(url.isEmpty() ? QUrl(m_file) : url);
}

void AppleMotionStream::settle(const QUrl &source) {
  m_source = source;
  emit changed();
}

void AppleMotionStream::clear() {
  if (m_reply) {
    m_reply->disconnect(this);
    m_reply->abort();
    m_reply->deleteLater();
    m_reply.clear();
  }
  m_proxy.clear();
  const bool had = !m_file.isEmpty() || !m_source.isEmpty();
  m_file.clear();
  m_source.clear();
  if (had)
    emit changed();
}
