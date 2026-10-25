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

#include "apple_music_stream.h"
#include "apple_music_stream_state.h"

#include <QDebug>
#include <QNetworkReply>

namespace {
// Apple serves each request at a few hundred KB/s, so the prefetch keeps several open.
constexpr int kPrefetchRequests = 6;
// Failed prefetch ranges before the stream leaves the rest to the player's reads.
constexpr int kPrefetchFailures = 3;
// Songs kept whole, encrypted and decrypted: the current one, the prepared one and a re-prepared one.
constexpr int kPrefetchStreams = 3;

QString replyError(QNetworkReply *reply) {
  if (reply->error() == QNetworkReply::NoError)
    return QStringLiteral("short range");
  const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
  return status ? QStringLiteral("HTTP %1").arg(status) : reply->errorString();
}
} // namespace

void AppleMusicStreams::fetchSegment(const QString &playbackId, int index) {
  Stream *stream = find(playbackId);
  if (!stream)
    return;
  const auto held = stream->encrypted.constFind(index);
  if (held != stream->encrypted.cend())
    decryptSegment(playbackId, index, *held);
  else if (!stream->fetching.contains(index))
    fetchRange(playbackId, index);
  // Otherwise the open range request decrypts for the waiters when it lands.
}

void AppleMusicStreams::fetchRange(const QString &playbackId, int index) {
  Stream *stream = find(playbackId);
  if (!stream)
    return;
  const auto [start, size] = stream->playlist.ranges.at(index);
  QNetworkRequest request(stream->playlist.media);
  request.setTransferTimeout(30000);
  request.setRawHeader("Range", "bytes=" + QByteArray::number(start) + '-' + QByteArray::number(start + size - 1));
  // Ranges the player waits on go ahead of the prefetch.
  request.setPriority(stream->loading.contains(index) ? QNetworkRequest::HighPriority : QNetworkRequest::LowPriority);
  stream->fetching.insert(index);
  QNetworkReply *reply = m_network.get(request);
  connect(reply, &QNetworkReply::finished, this, [this, reply, playbackId, index, size] {
    reply->deleteLater();
    Stream *stream = find(playbackId);
    if (!stream)
      return;
    stream->fetching.remove(index);
    const QByteArray bytes = reply->readAll();
    const bool ok = reply->error() == QNetworkReply::NoError && bytes.size() == size;
    if (ok && stream->prefetch)
      stream->encrypted.insert(index, bytes);
    if (stream->loading.contains(index)) {
      if (ok)
        decryptSegment(playbackId, index, bytes);
      else
        finishSegment(playbackId, index, replyError(reply));
    } else if (!ok) {
      qWarning().noquote() << "AppleMusic: prefetch of fragment" << index << "failed |" << replyError(reply);
      if (++stream->prefetchFailures >= kPrefetchFailures)
        stream->prefetch = false;
    }
    pumpPrefetch(playbackId);
  });
}

void AppleMusicStreams::startPrefetch(Stream *stream) {
  if (stream->playlist.ranges.size() < 2)
    return;
  stream->prefetch = true;
  stream->whole = true;
  pumpPrefetch(stream->playbackId);
}

void AppleMusicStreams::pumpPrefetch(const QString &playbackId) {
  Stream *stream = find(playbackId);
  if (!stream || !stream->prefetch)
    return;
  const int fragments = int(stream->playlist.ranges.size()) - 1;
  // From the playhead to the end, then the fragments before it.
  for (int step = 0; step < fragments && stream->fetching.size() < kPrefetchRequests; ++step) {
    const int index = 1 + (stream->prefetchFrom - 1 + step) % fragments;
    if (!stream->encrypted.contains(index) && !stream->fetching.contains(index))
      fetchRange(playbackId, index);
  }
}

void AppleMusicStreams::dropPrefetch(Stream *stream) {
  stream->prefetch = false;
  stream->whole = false;
  stream->encrypted = {};
  stream->trimClear();
}

void AppleMusicStreams::trimPrefetches() {
  for (qsizetype i = 0; i + kPrefetchStreams < m_order.size(); ++i)
    if (Stream *stream = find(m_order.at(i)))
      dropPrefetch(stream);
}
