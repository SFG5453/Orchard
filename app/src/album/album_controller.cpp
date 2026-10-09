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

#include "album_controller.h"

#include "appearance/animated_artwork_service.h"
#include "appearance/artwork_sampler.h"
#include "auth/auth_manager.h"
#include "providers/qobuz/qobuz_service.h"
#include "providers/youtube/catalog/youtube_catalog.h"

#include <QByteArray>
#include <QFutureWatcher>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrl>
#include <QUrlQuery>
#include <QtConcurrent/QtConcurrentRun>
#include <utility>

namespace {
QVariantList rgb(int red, int green, int blue) { return {red, green, blue}; }

QString browseIdFromItem(const QVariantMap &item) {
  const QVariantMap payload =
      item.value(QStringLiteral("browsePayload")).toMap();
  return payload.value(QStringLiteral("browseId"))
                 .toString()
                 .trimmed()
                 .isEmpty()
             ? item.value(QStringLiteral("browseId")).toString().trimmed()
             : payload.value(QStringLiteral("browseId")).toString().trimmed();
}

void copyIfMissing(QVariantMap &target, const QVariantMap &source,
                   const QString &key) {
  if (!target.value(key).isValid() ||
      target.value(key).toString().trimmed().isEmpty())
    target.insert(key, source.value(key));
}
} // namespace

AlbumController::AlbumController(YouTubeCatalog *catalog, AuthManager *auth,
                                 QObject *parent, bool artistMode,
                                 AnimatedArtworkService *animatedArtwork,
                                 QobuzService *qobuz)
    : QObject(parent), m_artistMode(artistMode), m_catalog(catalog),
      m_auth(auth), m_network(this), m_palette(defaultPalette()),
      m_animatedArtworkService(animatedArtwork), m_qobuz(qobuz) {
  Q_ASSERT(m_catalog);
  Q_ASSERT(m_auth);

  connect(m_catalog,
          m_artistMode ? &YouTubeCatalog::artistReady
                       : &YouTubeCatalog::albumReady,
          this, &AlbumController::receiveAlbum);
  connect(m_catalog, &YouTubeCatalog::requestFailed, this,
          &AlbumController::receiveFailure);
  connect(m_auth, &AuthManager::sessionChanged, this, &AlbumController::clear);
  if (m_qobuz && !m_artistMode)
    connect(m_qobuz, &QobuzService::changed, this, &AlbumController::updateStreamQuality);
}

QVariantMap AlbumController::defaultPalette() {
  return {
      {QStringLiteral("seam"), rgb(38, 48, 43)},
      {QStringLiteral("accent"), rgb(127, 190, 144)},
      {QStringLiteral("accentSoft"), rgb(150, 202, 164)},
      {QStringLiteral("deep"), rgb(15, 21, 18)},
      {QStringLiteral("ink"), rgb(8, 12, 10)},
      {QStringLiteral("surface"), rgb(24, 31, 27)},
      {QStringLiteral("surfaceRaised"), rgb(50, 62, 53)},
      {QStringLiteral("onAccent"), rgb(9, 16, 11)},
  };
}

void AlbumController::openAlbum(const QVariantMap &item) {
  m_requestId = 0;
  m_animatedArtworkRequestId++;
  m_animatedArtworkUrl.clear();
  cancelArtwork();
  m_detail.clear();
  updateStreamQuality();
  const QString browseId = browseIdFromItem(item);
  if (browseId.isEmpty()) {
    m_pendingItem = item;
    m_loading = false;
    m_errorMessage = tr("This page is missing its YouTube browse ID.");
    emit stateChanged();
    return;
  }
  if (!m_auth->isSignedIn()) {
    m_pendingItem = item;
    m_loading = false;
    m_errorMessage = tr("Sign in to open music details.");
    emit stateChanged();
    return;
  }

  m_pendingItem = item;
  m_detail = item;
  m_palette = defaultPalette();
  m_errorMessage.clear();
  m_loading = true;
  m_auth->refreshSession();
  m_requestId = m_artistMode
                    ? m_catalog->fetchArtist(browseId, m_auth->sessionObject())
                    : m_catalog->fetchAlbum(browseId, m_auth->sessionObject());
  emit stateChanged();
}

void AlbumController::retry() {
  if (!m_pendingItem.isEmpty())
    openAlbum(m_pendingItem);
}

void AlbumController::clear() {
  m_requestId = 0;
  m_animatedArtworkRequestId++;
  m_animatedArtworkUrl.clear();
  cancelArtwork();
  m_pendingItem.clear();
  m_detail.clear();
  updateStreamQuality();
  m_palette = defaultPalette();
  m_errorMessage.clear();
  m_loading = false;
  emit stateChanged();
}

void AlbumController::receiveAlbum(quint64 requestId,
                                   const QJsonObject &album) {
  if (requestId != m_requestId)
    return;

  m_requestId = 0;
  m_loading = false;
  m_errorMessage.clear();
  m_detail = album.toVariantMap();
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("title"));
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("artist"));
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("thumbnail"));
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("audioPlaylistId"));
  copyIfMissing(m_detail, m_pendingItem, QStringLiteral("playlistId"));
  if (m_detail.value(QStringLiteral("browseId")).toString().isEmpty())
    m_detail.insert(QStringLiteral("browseId"),
                    browseIdFromItem(m_pendingItem));
  // The album tile may supply artwork missing from the browse response.
  // Carry that fallback into the queue as well as the album hero.
  QVariantList tracks = m_detail.value(QStringLiteral("tracks")).toList();
  for (QVariant &value : tracks) {
    QVariantMap track = value.toMap();
    copyIfMissing(track, m_detail, QStringLiteral("thumbnail"));
    // Preserve navigation metadata when a track leaves its collection for the
    // queue.
    if (!m_artistMode) {
      if (track.value(QStringLiteral("albumId")).toString().isEmpty())
        track.insert(QStringLiteral("albumId"),
                     m_detail.value(QStringLiteral("browseId")));
      if (track.value(QStringLiteral("album")).toString().isEmpty())
        track.insert(QStringLiteral("album"),
                     m_detail.value(QStringLiteral("title")));
    }
    if (track.value(QStringLiteral("artistBrowseIds")).toList().isEmpty()) {
      const QString artistId =
          m_detail
              .value(m_artistMode ? QStringLiteral("browseId")
                                  : QStringLiteral("artistBrowseId"))
              .toString();
      if (!artistId.isEmpty())
        track.insert(QStringLiteral("artistBrowseIds"), QVariantList{artistId});
    }
    value = track;
  }
  m_detail.insert(QStringLiteral("tracks"), tracks);
  m_detail.insert(QStringLiteral("kind"), m_artistMode
                                              ? QStringLiteral("artist")
                                              : QStringLiteral("album"));
  emit stateChanged();

  updateAnimatedArtwork();
  updateStreamQuality();
  const QString heroArtwork = m_detail.value(QStringLiteral("heroArtwork")).toString();
  requestArtwork(heroArtwork.isEmpty()
                     ? m_detail.value(QStringLiteral("thumbnail")).toString()
                     : heroArtwork);
  if (m_artistMode)
    requestArtistArtwork();
}

// Hi-Res or Lossless, when the user's Qobuz plays this album. Nothing otherwise.
void AlbumController::updateStreamQuality() {
  const quint64 request = ++m_streamQualityRequest;
  const QString title = m_detail.value(QStringLiteral("title")).toString();
  const bool loaded = !m_artistMode && m_qobuz && m_qobuz->active() && !m_loading &&
                      !m_detail.value(QStringLiteral("tracks")).toList().isEmpty();
  if (!loaded) {
    if (!m_streamQuality.isEmpty()) {
      m_streamQuality.clear();
      emit streamQualityChanged();
    }
    return;
  }
  const QVariantMap album{
      {QStringLiteral("title"), title},
      {QStringLiteral("artist"), m_detail.value(QStringLiteral("artist"))},
      {QStringLiteral("year"), m_detail.value(QStringLiteral("year"))},
      {QStringLiteral("trackCount"), m_detail.value(QStringLiteral("tracks")).toList().size()}};
  m_qobuz->albumQuality(album, [this, request](const QJsonValue &result, const QString &) {
    if (request != m_streamQualityRequest)
      return;
    const QVariantMap quality = result.toObject().toVariantMap();
    if (quality == m_streamQuality)
      return;
    m_streamQuality = quality;
    emit streamQualityChanged();
  });
}

// Who needs static covers when you can burn GPU cycles looping a 2-second clip from Cupertino?
void AlbumController::updateAnimatedArtwork() {
  if (m_artistMode || !m_animatedArtworkService) {
    if (!m_animatedArtworkUrl.isEmpty()) {
      m_animatedArtworkUrl.clear();
      emit stateChanged();
    }
    return;
  }

  const QString title = m_detail.value(QStringLiteral("title")).toString();
  const QString artist = m_detail.value(QStringLiteral("artist")).toString();
  if (title.trimmed().isEmpty()) {
    if (!m_animatedArtworkUrl.isEmpty()) {
      m_animatedArtworkUrl.clear();
      emit stateChanged();
    }
    return;
  }

  const quint64 reqId = ++m_animatedArtworkRequestId;
  m_animatedArtworkService->resolveAlbumArtwork(title, artist, reqId,
      [this](quint64 requestId, const QString &videoUrl) {
    if (requestId != m_animatedArtworkRequestId)
      return;
    if (m_animatedArtworkUrl == videoUrl)
      return;
    m_animatedArtworkUrl = videoUrl;
    emit stateChanged();
  });
}

void AlbumController::requestArtistArtwork() {
  const QString name =
      m_detail.value(QStringLiteral("title")).toString().trimmed();
  if (name.isEmpty())
    return;
  const QString key = name.toCaseFolded();
  if (const QString *cached = m_artistArtworkCache.object(key)) {
    applyArtistArtwork(*cached);
    return;
  }

  QUrl url(
      QStringLiteral("https://www.theaudiodb.com/api/v1/json/123/search.php"));
  QUrlQuery query;
  query.addQueryItem(QStringLiteral("s"), name);
  url.setQuery(query);
  QNetworkRequest request(url);
  request.setRawHeader("User-Agent", "Orchard Desktop/3.0");
  request.setTransferTimeout(4000);
  auto *reply = m_network.get(request);
  m_artistArtworkReply = reply;
  connect(reply, &QNetworkReply::finished, this, [this, reply, key] {
    if (m_artistArtworkReply != reply) {
      reply->deleteLater();
      return;
    }
    m_artistArtworkReply = nullptr;
    const bool succeeded = reply->error() == QNetworkReply::NoError;
    const QByteArray bytes = succeeded ? reply->readAll() : QByteArray();
    reply->deleteLater();
    if (!succeeded)
      return; // Artwork is optional; a failed lookup must not hide the music.
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(bytes, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject())
      return;
    const auto artists =
        document.object().value(QStringLiteral("artists")).toArray();
    const auto artist =
        artists.isEmpty() ? QJsonObject() : artists.first().toObject();
    QString artwork;
    for (const auto *field : {"strArtistFanart", "strArtistFanart2",
                              "strArtistFanart3", "strArtistBanner"}) {
      const QString candidate =
          artist.value(QLatin1String(field)).toString().trimmed();
      const QUrl candidateUrl(candidate);
      if (candidateUrl.isValid() && !candidateUrl.host().isEmpty() &&
          (candidateUrl.scheme() == QStringLiteral("https") ||
           candidateUrl.scheme() == QStringLiteral("http"))) {
        artwork = candidate;
        break;
      }
    }
    m_artistArtworkCache.insert(key, new QString(artwork));
    applyArtistArtwork(artwork);
  });
}

void AlbumController::applyArtistArtwork(const QString &url) {
  if (url.isEmpty())
    return;
  // Keep thumbnails on queued songs intact; only the artist hero uses fanart.
  cancelArtwork();
  m_detail.insert(QStringLiteral("heroArtwork"), url);
  emit stateChanged();
  requestArtwork(url);
}

void AlbumController::receiveFailure(quint64 requestId,
                                     const QString &message) {
  if (requestId != m_requestId)
    return;

  m_requestId = 0;
  m_loading = false;
  m_errorMessage = message;
  emit stateChanged();
}

void AlbumController::requestArtwork(const QString &urlString) {
  const QUrl url(urlString.trimmed());
  if (!url.isValid() || (url.scheme() != QStringLiteral("http") &&
                         url.scheme() != QStringLiteral("https"))) {
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
    sampleArtwork(bytes);
  });
}

void AlbumController::sampleArtwork(QByteArray bytes) {
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
            }
            emit stateChanged();
          });

  watcher->setFuture(
      QtConcurrent::run([bytes = std::move(bytes)]() -> QJsonObject {
        return ::sampleArtwork(bytes);
      }));
}

void AlbumController::cancelArtwork() {
  QNetworkReply *artistReply = m_artistArtworkReply;
  m_artistArtworkReply = nullptr;
  if (artistReply) {
    artistReply->abort();
    artistReply->deleteLater();
  }
  ++m_artworkGeneration;
  QNetworkReply *reply = m_artworkReply;
  m_artworkReply = nullptr;
  if (reply) {
    reply->abort();
    reply->deleteLater();
  }
  m_artworkLoading = false;
}
