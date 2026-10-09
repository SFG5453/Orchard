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

#include "animated_artwork_lookup.h"
#include "appearance_settings.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QUrl>
#include <QUrlQuery>

namespace {
// Long enough that seeks don't re-query a dead mirror, short enough to recover.
constexpr qint64 kFailureRetryMs = 5 * 60 * 1000;
const QString kBoiduAlbum = QStringLiteral("boidu-album");

// Boidu's song search can land on a clean edition without motion art; asking by
// album name reaches the other edition. Runs right after boidu.
void addBoiduAlbumSearch(QStringList &mirrors, const QString &title,
                         const QString &album) {
  const int boidu = mirrors.indexOf(QStringLiteral("boidu"));
  if (boidu < 0 || album.isEmpty() ||
      AnimatedArtworkService::normalizeText(album) ==
          AnimatedArtworkService::normalizeText(title))
    return;
  mirrors.insert(boidu + 1, kBoiduAlbum);
}
} // namespace

AnimatedArtworkService::AnimatedArtworkService(AppearanceSettings *settings,
                                               QObject *parent)
    : QObject(parent), m_settings(settings), m_network(this) {}

void AnimatedArtworkService::resolveTrackArtwork(
    const QString &title, const QString &artist, const QString &album,
    quint64 requestId, std::function<void(quint64, const QString &)> callback) {
  if (!m_settings || !m_settings->animatedArtworkEnabled() ||
      title.trimmed().isEmpty() || artist.trimmed().isEmpty()) {
    if (callback) {
      callback(requestId, QString());
    }
    return;
  }

  const QString key = cacheKey(title, artist, album);
  QString cached;
  if (cachedResult(key, &cached)) {
    // Cache hit! Serving faster than a drive-thru on a quiet Tuesday.
    if (callback) {
      callback(requestId, cached);
    }
    return;
  }

  m_pendingRequests[key].append(
      [callback = std::move(callback), requestId](const QString &result) {
        if (callback) {
          callback(requestId, result);
        }
      });

  if (m_pendingRequests[key].size() > 1) {
    // In-flight coalescing: why order two pizzas when one is already in the
    // oven?
    return;
  }

  auto state = std::make_shared<LookupState>();
  state->title = title.trimmed();
  state->artist = artist.trimmed();
  state->album = album.trimmed();
  state->mirrors = m_settings->mirrorOrder();
  // Boidu searches by song, so Apple picks the album that actually holds the
  // track. m8tec guesses from the album name and happily hands out the sequel.
  if (state->mirrors.removeOne(QStringLiteral("boidu"))) {
    state->mirrors.prepend(QStringLiteral("boidu"));
  }
  addBoiduAlbumSearch(state->mirrors, state->title, state->album);
  state->currentMirrorIndex = 0;
  state->key = key;

  executeLookup(state);
}

void AnimatedArtworkService::resolveAlbumArtwork(
    const QString &title, const QString &artist, quint64 requestId,
    std::function<void(quint64, const QString &)> callback) {
  if (!m_settings || !m_settings->animatedArtworkEnabled() ||
      title.trimmed().isEmpty()) {
    if (callback) {
      callback(requestId, QString());
    }
    return;
  }

  const QString key = cacheKey(title, artist, title);
  QString cached;
  if (cachedResult(key, &cached)) {
    if (callback) {
      callback(requestId, cached);
    }
    return;
  }

  m_pendingRequests[key].append(
      [callback = std::move(callback), requestId](const QString &result) {
        if (callback) {
          callback(requestId, result);
        }
      });

  if (m_pendingRequests[key].size() > 1) {
    return;
  }

  auto state = std::make_shared<LookupState>();
  state->title = title.trimmed();
  state->artist = artist.trimmed();
  state->album = title.trimmed();
  state->mirrors = m_settings->mirrorOrder();
  // Canvas loops belong to songs, never to whole albums.
  state->mirrors.removeAll(QStringLiteral("spotify"));
  // Boidu only searches songs, so a plain album-title query never passes the
  // name check. The album search verifies through iTunes instead.
  const int boidu = state->mirrors.indexOf(QStringLiteral("boidu"));
  if (boidu >= 0)
    state->mirrors[boidu] = kBoiduAlbum;
  state->currentMirrorIndex = 0;
  state->key = key;

  executeLookup(state);
}

void AnimatedArtworkService::requestAlbumArtwork(const QString &title, const QString &artist) {
  resolveAlbumArtwork(title, artist, 0,
                      [this, title, artist](quint64, const QString &url) {
                        // Cards are small; the 1080p loop is 10x the bytes for no visible gain.
                        resolveSmallVariant(url, [this, title, artist](const QString &small) {
                          emit albumArtworkResolved(title, artist, small);
                        });
                      });
}

bool AnimatedArtworkService::cachedResult(const QString &key, QString *result) {
  if (m_cache.contains(key)) {
    const auto retry = m_retryAfterMs.constFind(key);
    if (retry != m_retryAfterMs.constEnd() &&
        QDateTime::currentMSecsSinceEpoch() >= *retry)
      return false;
    *result = m_cache.value(key);
    return true;
  }
  // Last session's answer, still fresh. The mirrors get the night off.
  const auto stored = m_store.lookup(key);
  if (!stored)
    return false;
  m_cache.insert(key, *stored);
  *result = *stored;
  return true;
}

void AnimatedArtworkService::forgetUrl(const QString &url) {
  const QString trimmed = url.trimmed();
  if (trimmed.isEmpty())
    return;
  m_store.forgetUrl(trimmed);
  for (auto it = m_cache.begin(); it != m_cache.end();) {
    if (it.value() == trimmed)
      it = m_cache.erase(it);
    else
      ++it;
  }
}

void AnimatedArtworkService::completeLookup(std::shared_ptr<LookupState> state,
                                            const QString &result) {
  const QString &key = state->key;
  // Session cache: fetch once per song and reuse. Empty results cached too so
  // we don't spam mirrors on seeks; failure-caused ones only for a while.
  m_cache.insert(key, result);
  if (result.isEmpty() && state->mirrorFailed) {
    m_retryAfterMs.insert(key, QDateTime::currentMSecsSinceEpoch() +
                                   kFailureRetryMs);
  } else {
    m_retryAfterMs.remove(key);
    const QString source = result.isEmpty() || state->currentMirrorIndex <= 0
                               ? QString()
                               : state->mirrors.at(state->currentMirrorIndex - 1);
    m_store.store(key, result, source);
  }

  const auto pending = m_pendingRequests.take(key);
  for (const auto &cb : pending) {
    if (cb) {
      cb(result);
    }
  }
}

void AnimatedArtworkService::executeLookup(std::shared_ptr<LookupState> state) {
  queryNextMirror(state);
}

void AnimatedArtworkService::queryNextMirror(
    std::shared_ptr<LookupState> state) {
  if (state->currentMirrorIndex >= state->mirrors.size()) {
    // All mirrors exhausted. Static JPEG wins this round.
    completeLookup(state, QString());
    return;
  }

  const QString mirrorId = state->mirrors.at(state->currentMirrorIndex++);
  if (mirrorId == QStringLiteral("m8tec")) {
    fetchM8tec(state);
  } else if (mirrorId == QStringLiteral("boidu")) {
    fetchBoidu(state, false);
  } else if (mirrorId == kBoiduAlbum) {
    fetchBoidu(state, true);
  } else if (mirrorId == QStringLiteral("spotify")) {
    fetchSpotify(state);
  } else {
    queryNextMirror(state);
  }
}

void AnimatedArtworkService::fetchM8tec(std::shared_ptr<LookupState> state) {
  const QString albumOrTitle =
      state->album.isEmpty() ? state->title : state->album;
  QUrl url(QStringLiteral("https://artwork.m8tec.top/api/v1/artwork/search"));
  QUrlQuery query;
  query.addQueryItem(QStringLiteral("artist"), state->artist);
  query.addQueryItem(QStringLiteral("album"), albumOrTitle);
  if (!state->title.isEmpty()) {
    query.addQueryItem(QStringLiteral("title"), state->title);
  }
  url.setQuery(query);

  QNetworkRequest request(url);
  request.setHeader(QNetworkRequest::UserAgentHeader,
                    QStringLiteral("Orchard/3.0"));
  request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);

  QNetworkReply *reply = m_network.get(request);
  connect(reply, &QNetworkReply::finished, this, [this, reply, state]() {
    reply->deleteLater();
    if (reply->error() != QNetworkReply::NoError) {
      mirrorFailed(state);
      return;
    }

    const QByteArray bytes = reply->readAll();
    QJsonParseError parseError{};
    const QJsonDocument doc = QJsonDocument::fromJson(bytes, &parseError);
    if (parseError.error != QJsonParseError::NoError || !doc.isObject()) {
      mirrorFailed(state);
      return;
    }

    const QJsonObject obj = doc.object();
    if (obj.contains(QStringLiteral("error"))) {
      queryNextMirror(state);
      return;
    }

    const QString returnedArtist =
        obj.value(QStringLiteral("artist")).toString();
    const QString returnedAlbum = obj.value(QStringLiteral("album")).toString();
    if (!looseMatches(returnedArtist, state->artist) ||
        (!state->album.isEmpty() &&
         !editionlessMatches(returnedAlbum, state->album))) {
      queryNextMirror(state);
      return;
    }

    QString rawMotion = obj.value(QStringLiteral("url")).toString().trimmed();
    if (rawMotion.isEmpty()) {
      rawMotion = obj.value(QStringLiteral("url_tall")).toString().trimmed();
    }
    if (rawMotion.isEmpty()) {
      rawMotion = obj.value(QStringLiteral("animated")).toString().trimmed();
    }

    acceptMotionUrl(rawMotion, state);
  });
}

void AnimatedArtworkService::mirrorFailed(std::shared_ptr<LookupState> state) {
  state->mirrorFailed = true;
  queryNextMirror(state);
}

void AnimatedArtworkService::fetchBoidu(std::shared_ptr<LookupState> state,
                                        bool byAlbum) {
  QUrl url(QStringLiteral("https://artwork.boidu.dev/"));
  QUrlQuery query;
  query.addQueryItem(QStringLiteral("s"), byAlbum ? state->album : state->title);
  query.addQueryItem(QStringLiteral("a"), state->artist);
  url.setQuery(query);

  QNetworkRequest request(url);
  request.setHeader(QNetworkRequest::UserAgentHeader,
                    QStringLiteral("Orchard/3.0"));
  request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);

  QNetworkReply *reply = m_network.get(request);
  connect(reply, &QNetworkReply::finished, this, [this, reply, state, byAlbum]() {
    reply->deleteLater();
    if (reply->error() != QNetworkReply::NoError) {
      mirrorFailed(state);
      return;
    }

    const QByteArray bytes = reply->readAll();
    QJsonParseError parseError{};
    const QJsonDocument doc = QJsonDocument::fromJson(bytes, &parseError);
    if (parseError.error != QJsonParseError::NoError) {
      mirrorFailed(state);
      return;
    }

    QJsonObject obj;
    if (doc.isObject()) {
      obj = doc.object();
    } else if (doc.isArray() && !doc.array().isEmpty()) {
      obj = doc.array().first().toObject();
    } else {
      queryNextMirror(state);
      return;
    }

    if (obj.contains(QStringLiteral("error"))) {
      queryNextMirror(state);
      return;
    }

    const QString returnedName = obj.value(QStringLiteral("name")).toString();
    const QString returnedArtist =
        obj.value(QStringLiteral("artist")).toString();
    // An album search returns some track from it; the iTunes check vouches instead.
    const bool nameMatches =
        byAlbum || editionlessMatches(returnedName, state->title) ||
        (!state->album.isEmpty() &&
         editionlessMatches(returnedName, state->album));
    if (!nameMatches || !looseMatches(returnedArtist, state->artist)) {
      queryNextMirror(state);
      return;
    }

    // Prefer the HLS manifest: videoUrl is 2160p 10-bit HEVC, and one decoder
    // of that buffers ~400 MB. The manifest resolves to 1080p AVC.
    QString motion = obj.value(QStringLiteral("animated")).toString().trimmed();
    if (!motion.contains(QStringLiteral(".m3u8"), Qt::CaseInsensitive)) {
      motion = obj.value(QStringLiteral("videoUrl")).toString().trimmed();
    }
    if (motion.isEmpty()) {
      motion =
          obj.value(QStringLiteral("videoUrlVertical")).toString().trimmed();
    }
    if (motion.isEmpty()) {
      queryNextMirror(state);
      return;
    }

    const QString albumId = obj.value(QStringLiteral("albumId")).toString();
    verifyBoiduAlbum(albumId, state, byAlbum, [this, motion, state]() {
      acceptMotionUrl(motion, state);
    });
  });
}

// Boidu only reports an albumId, so ask iTunes which album that is.
void AnimatedArtworkService::verifyBoiduAlbum(
    const QString &albumId, std::shared_ptr<LookupState> state,
    bool requireVerified, std::function<void()> onAccepted) {
  if (state->album.isEmpty() || albumId.trimmed().isEmpty()) {
    if (requireVerified)
      queryNextMirror(state);
    else
      onAccepted();
    return;
  }

  QUrl url(QStringLiteral("https://itunes.apple.com/lookup"));
  QUrlQuery query;
  query.addQueryItem(QStringLiteral("id"), albumId.trimmed());
  url.setQuery(query);

  QNetworkRequest request(url);
  request.setHeader(QNetworkRequest::UserAgentHeader,
                    QStringLiteral("Orchard/3.0"));

  QNetworkReply *reply = m_network.get(request);
  connect(reply, &QNetworkReply::finished, this,
          [this, reply, state, requireVerified,
           onAccepted = std::move(onAccepted)]() {
            reply->deleteLater();
            const QJsonArray results =
                QJsonDocument::fromJson(reply->readAll())
                    .object()
                    .value(QStringLiteral("results"))
                    .toArray();
            const QString collection =
                results.isEmpty()
                    ? QString()
                    : results.first()
                          .toObject()
                          .value(QStringLiteral("collectionName"))
                          .toString();
            // Unverifiable (network error, region-locked id): trust boidu's
            // song-keyed pick over m8tec's album-name guess.
            if ((collection.isEmpty() && !requireVerified) ||
                editionlessMatches(collection, state->album)) {
              onAccepted();
            } else if (reply->error() != QNetworkReply::NoError) {
              mirrorFailed(state);
            } else {
              queryNextMirror(state);
            }
          });
}

void AnimatedArtworkService::acceptMotionUrl(
    const QString &rawMotion, std::shared_ptr<LookupState> state) {
  if (rawMotion.isEmpty()) {
    queryNextMirror(state);
    return;
  }

  if (rawMotion.endsWith(QStringLiteral(".mp4"), Qt::CaseInsensitive)) {
    completeLookup(state, rawMotion);
    return;
  }

  if (rawMotion.contains(QStringLiteral(".m3u8"), Qt::CaseInsensitive)) {
    resolveHlsVariant(rawMotion, state,
                      [this, state](const QString &resolvedMp4) {
                        if (!resolvedMp4.isEmpty()) {
                          completeLookup(state, resolvedMp4);
                        } else {
                          queryNextMirror(state);
                        }
                      });
    return;
  }

  queryNextMirror(state);
}
