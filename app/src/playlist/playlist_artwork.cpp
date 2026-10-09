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

// Cover art for the open playlist: palette sampling and YouTube's four-album
// collage detection. Split from playlist_controller.cpp to keep both readable.

#include "playlist_controller.h"

#include "appearance/artwork_sampler.h"

#include <QByteArray>
#include <QFutureWatcher>
#include <QImage>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSharedPointer>
#include <QUrl>
#include <QtConcurrent/QtConcurrentRun>
#include <cstdlib>
#include <utility>

namespace {
// Mean per-channel difference of two images squeezed to a 16x16 thumbnail,
// 0 for identical and 1 for black against white.
double thumbnailDistance(const QImage &left, const QImage &right) {
  constexpr int side = 16;
  const QImage a = left.scaled(side, side, Qt::IgnoreAspectRatio,
                               Qt::SmoothTransformation)
                       .convertToFormat(QImage::Format_RGB32);
  const QImage b = right.scaled(side, side, Qt::IgnoreAspectRatio,
                                Qt::SmoothTransformation)
                       .convertToFormat(QImage::Format_RGB32);
  double total = 0;
  for (int y = 0; y < side; ++y) {
    const auto *rowA = reinterpret_cast<const QRgb *>(a.constScanLine(y));
    const auto *rowB = reinterpret_cast<const QRgb *>(b.constScanLine(y));
    for (int x = 0; x < side; ++x) {
      total += std::abs(qRed(rowA[x]) - qRed(rowB[x])) +
               std::abs(qGreen(rowA[x]) - qGreen(rowB[x])) +
               std::abs(qBlue(rowA[x]) - qBlue(rowB[x]));
    }
  }
  return total / (side * side * 3 * 255.0);
}

// A collage is the four covers in a 2x2 grid:
//   1 2
//   3 4
// so each quadrant should look like its track's own art. Anything else,
// like a custom upload, is some other picture and keeps its still cover.
bool coverIsCollage(const QByteArray &cover, const QList<QByteArray> &tiles) {
  constexpr double maxDistance = 0.12; // Loose enough for JPEG and resizing, tight enough to reject strangers.
  const QImage image = QImage::fromData(cover);
  if (image.isNull() || tiles.size() != 4)
    return false;
  const int halfWidth = image.width() / 2;
  const int halfHeight = image.height() / 2;
  if (halfWidth < 2 || halfHeight < 2)
    return false;
  for (int i = 0; i < 4; ++i) {
    const QImage tile = QImage::fromData(tiles.at(i));
    if (tile.isNull())
      return false;
    const QImage quadrant =
        image.copy((i % 2) * halfWidth, (i / 2) * halfHeight, halfWidth, halfHeight);
    if (thumbnailDistance(quadrant, tile) > maxDistance)
      return false;
  }
  return true;
}
} // namespace

void PlaylistController::requestArtwork(const QString &urlString) {
  const QUrl url(urlString.trimmed());
  if (!url.isValid() || (url.scheme() != QStringLiteral("http") &&
                         url.scheme() != QStringLiteral("https") &&
                         url.scheme() != QStringLiteral("file"))) {
    m_artworkLoading = false;
    emit stateChanged();
    return;
  }

  m_artworkLoading = true;
  emit stateChanged();

  QNetworkRequest request(url);
  request.setRawHeader(
      "Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8");
  request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                       QNetworkRequest::NoLessSafeRedirectPolicy);
  QNetworkReply *reply = m_network.get(request);
  m_artworkReply = reply;
  connect(reply, &QNetworkReply::finished, this, [this, reply] {
    if (m_artworkReply != reply) {
      reply->deleteLater();
      return;
    }
    m_artworkReply = nullptr;
    const QByteArray bytes = reply->error() == QNetworkReply::NoError
                                 ? reply->readAll()
                                 : QByteArray();
    reply->deleteLater();
    if (bytes.isEmpty()) {
      m_artworkLoading = false;
      emit stateChanged();
      return;
    }
    // The four-album collage trick looks up motion artwork online, which
    // local playlists never do.
    if (!m_localOpen)
      detectCollage(bytes);
    sampleArtwork(bytes);
  });
}

void PlaylistController::setCollageAlbums(QVariantList albums) {
  if (albums == m_collageAlbums)
    return;
  m_collageAlbums = std::move(albums);
  emit collageChanged();
}

void PlaylistController::detectCollage(const QByteArray &coverBytes) {
  const quint64 generation = ++m_collageGeneration;
  setCollageAlbums({});

  // The first four tracks with their own, different art, in playlist order.
  const QString cover = m_detail.value(QStringLiteral("thumbnail")).toString();
  QStringList urls;
  QVariantList albums;
  for (const QVariant &value : std::as_const(m_sourceTracks)) {
    const QVariantMap track = value.toMap();
    const QString art = track.value(QStringLiteral("thumbnail")).toString();
    if (art.isEmpty() || art == cover || urls.contains(art))
      continue;
    QString artist = track.value(QStringLiteral("artist")).toString();
    if (artist.isEmpty())
      artist = track.value(QStringLiteral("artists")).toStringList().value(0);
    const QString album = track.value(QStringLiteral("album")).toString();
    // Motion artwork is looked up by album, so a track without one can't animate.
    if (album.isEmpty() || artist.isEmpty())
      return;
    urls.append(art);
    albums.append(QVariantMap{{QStringLiteral("title"), album},
                              {QStringLiteral("artist"), artist}});
    if (urls.size() == 4)
      break;
  }
  if (urls.size() < 4)
    return;

  struct Pending {
    QList<QByteArray> tiles = QList<QByteArray>(4);
    int remaining{4};
  };
  const auto pending = QSharedPointer<Pending>::create();
  for (int i = 0; i < 4; ++i) {
    QNetworkRequest request{QUrl(urls.at(i))};
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                         QNetworkRequest::NoLessSafeRedirectPolicy);
    QNetworkReply *reply = m_network.get(request);
    connect(reply, &QNetworkReply::finished, this,
            [this, reply, i, generation, pending, albums, coverBytes] {
              const QByteArray bytes = reply->error() == QNetworkReply::NoError
                                           ? reply->readAll()
                                           : QByteArray();
              reply->deleteLater();
              if (generation != m_collageGeneration)
                return;
              pending->tiles[i] = bytes;
              if (--pending->remaining > 0)
                return;

              auto *watcher = new QFutureWatcher<bool>(this);
              connect(watcher, &QFutureWatcher<bool>::finished, this,
                      [this, watcher, generation, albums] {
                        const bool match = watcher->result();
                        watcher->deleteLater();
                        if (generation == m_collageGeneration && match)
                          setCollageAlbums(albums);
                      });
              watcher->setFuture(QtConcurrent::run([coverBytes, pending] {
                return coverIsCollage(coverBytes, pending->tiles);
              }));
            });
  }
}

void PlaylistController::sampleArtwork(QByteArray bytes) {
  const quint64 generation = ++m_artworkGeneration;
  auto *watcher = new QFutureWatcher<QJsonObject>(this);
  connect(watcher, &QFutureWatcher<QJsonObject>::finished, this,
          [this, watcher, generation] {
            const QJsonObject object = watcher->result();
            watcher->deleteLater();
            if (generation != m_artworkGeneration)
              return;

            m_artworkLoading = false;
            const QJsonObject palette = object.value(QStringLiteral("palette")).toObject();
            if (!palette.isEmpty()) {
              m_palette = palette.toVariantMap();
              m_palette.insert(QStringLiteral("zones"),
                               object.value(QStringLiteral("zones")).toObject().toVariantMap());
              emit paletteChanged();
            }
            emit stateChanged();
          });

  watcher->setFuture(
      QtConcurrent::run([bytes = std::move(bytes)]() -> QJsonObject {
        return ::sampleArtwork(bytes);
      }));
}

void PlaylistController::cancelArtwork() {
  ++m_artworkGeneration;
  // Late thumbnail replies and comparisons belong to the old playlist now.
  ++m_collageGeneration;
  setCollageAlbums({});
  QNetworkReply *reply = m_artworkReply;
  m_artworkReply = nullptr;
  if (reply) {
    reply->abort();
    reply->deleteLater();
  }
  m_artworkLoading = false;
}
