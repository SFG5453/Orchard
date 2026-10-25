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
#include "apple_music_requests.h"
#include "apple_music_stream.h"
#include "apple_music_stream_state.h"

#include <QDebug>
#include <QNetworkReply>

using apple_music_requests::replyError;

void AppleMusicStreams::loadVideoAudio(const QString &appleId, const QJsonObject &song, Resolved done) {
  const QUrl masterUrl(song.value(QStringLiteral("hls-playlist-url")).toString());
  const QUrl licenseUrl(song.value(QStringLiteral("hls-key-server-url")).toString());
  if (masterUrl.scheme() != QStringLiteral("https") || licenseUrl.scheme() != QStringLiteral("https")) {
    done({}, tr("Apple Music has no Widevine stream for this video."));
    return;
  }
  const auto fail = [done](const QString &error) {
    qWarning().noquote() << "AppleMusic: music video audio unusable |" << error;
    done({}, tr("Apple Music returned a stream Orchard cannot play."));
  };
  QNetworkReply *reply = m_network.get(QNetworkRequest(masterUrl));
  connect(reply, &QNetworkReply::finished, this, [this, reply, appleId, licenseUrl, done, fail] {
    reply->deleteLater();
    apple_music_playlist::Master master;
    QString error = reply->error() == QNetworkReply::NoError ? QString() : replyError(reply);
    if (error.isEmpty())
      error = apple_music_playlist::parseMaster(reply->url(), reply->readAll(), master);
    const QUrl audioUrl = apple_music_playlist::pickAudio(master);
    if (error.isEmpty() && !audioUrl.isValid())
      error = QStringLiteral("no audio rendition");
    if (!error.isEmpty()) {
      fail(error);
      return;
    }
    QNetworkReply *media = m_network.get(QNetworkRequest(audioUrl));
    connect(media, &QNetworkReply::finished, this, [this, media, appleId, licenseUrl, done, fail] {
      media->deleteLater();
      auto stream = std::make_shared<Stream>();
      QString error = media->error() == QNetworkReply::NoError ? QString() : replyError(media);
      if (error.isEmpty())
        error = apple_music_playlist::parse(media->url(), media->readAll(), stream->playlist);
      if (!error.isEmpty()) {
        fail(error);
        return;
      }
      measureFiles(stream, [this, stream, appleId, licenseUrl, done, fail](const QString &error) {
        if (error.isEmpty())
          openStream(appleId, licenseUrl, stream, done);
        else
          fail(error);
      });
    });
  });
}

// Sizes every file of a per-file playlist with HEAD requests, so the served MP4 can lay them end to end.
void AppleMusicStreams::measureFiles(const std::shared_ptr<Stream> &stream, Done done) {
  const QList<QUrl> files = stream->playlist.files;
  if (files.isEmpty()) {
    done({});
    return;
  }
  auto sizes = std::make_shared<QList<qint64>>(files.size(), -1);
  auto pending = std::make_shared<int>(int(files.size()));
  auto failed = std::make_shared<QString>();
  for (int i = 0; i < files.size(); ++i) {
    QNetworkRequest request(files.at(i));
    request.setTransferTimeout(20000);
    QNetworkReply *reply = m_network.head(request);
    connect(reply, &QNetworkReply::finished, this, [reply, i, stream, sizes, pending, failed, done] {
      reply->deleteLater();
      const qint64 size = reply->header(QNetworkRequest::ContentLengthHeader).toLongLong();
      if (reply->error() != QNetworkReply::NoError || size <= 0)
        *failed = reply->error() != QNetworkReply::NoError ? replyError(reply) : QStringLiteral("file without a size");
      (*sizes)[i] = size;
      if (--*pending)
        return;
      if (failed->isEmpty())
        stream->playlist.setFileSizes(*sizes);
      done(*failed);
    });
  }
}
