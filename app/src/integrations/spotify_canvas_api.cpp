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

// The Spotify requests behind a canvas: client token, track search, canvaz-cache.
#include "spotify_canvas.h"
#include "appearance/animated_artwork_service.h"

#include <QDateTime>
#include <QDebug>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QUrlQuery>

namespace {
constexpr auto kBrowserUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0";
// canvaz-cache answers the iOS client.
constexpr auto kAppUserAgent = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)";
// The web player's own searchTracks query; the public Web API throttles its tokens hard.
constexpr auto kSearchTracksHash = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428";

qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

QNetworkRequest request(const QUrl &url, const char *userAgent) {
  QNetworkRequest request(url);
  request.setRawHeader("User-Agent", userAgent);
  request.setAttribute(QNetworkRequest::Http2AllowedAttribute, false);
  request.setTransferTimeout(15000);
  return request;
}

QString artistNames(const QJsonObject &track) {
  QStringList names;
  for (const QJsonValue &artist : track.value(QStringLiteral("artists")).toObject().value(QStringLiteral("items")).toArray())
    names.append(artist.toObject().value(QStringLiteral("profile")).toObject().value(QStringLiteral("name")).toString());
  for (const QJsonValue &artist : track.value(QStringLiteral("artists")).toArray())
    names.append(artist.toObject().value(QStringLiteral("name")).toString());
  return names.join(QStringLiteral(", "));
}

bool sameSong(const QString &name, const QString &artists, const QString &title, const QString &artist) {
  // Exact titles: "Love" must not borrow the canvas of "Love Story".
  return AnimatedArtworkService::editionlessMatches(name, title) &&
         (artist.isEmpty() || AnimatedArtworkService::looseMatches(artists, artist));
}
} // namespace

QString SpotifyCanvas::matchingTrackId(const QJsonObject &pathfinder, const QString &title,
                                       const QString &artist) {
  const QJsonArray items = pathfinder.value(QStringLiteral("data")).toObject()
                               .value(QStringLiteral("searchV2")).toObject()
                               .value(QStringLiteral("tracksV2")).toObject()
                               .value(QStringLiteral("items")).toArray();
  for (const QJsonValue &value : items) {
    const QJsonObject track = value.toObject().value(QStringLiteral("item")).toObject()
                                  .value(QStringLiteral("data")).toObject();
    if (!sameSong(track.value(QStringLiteral("name")).toString(), artistNames(track), title, artist))
      continue;
    const QString id = track.value(QStringLiteral("id")).toString();
    return id.isEmpty() ? track.value(QStringLiteral("uri")).toString().section(QLatin1Char(':'), -1) : id;
  }
  // The Web API fallback answers in its own shape.
  for (const QJsonValue &value : pathfinder.value(QStringLiteral("tracks")).toObject()
                                     .value(QStringLiteral("items")).toArray()) {
    const QJsonObject track = value.toObject();
    if (sameSong(track.value(QStringLiteral("name")).toString(), artistNames(track), title, artist))
      return track.value(QStringLiteral("id")).toString();
  }
  return {};
}

QByteArray SpotifyCanvas::canvasRequest(const QString &trackId) {
  // message { repeated entity = 1 { string track_uri = 1; } }
  const QByteArray uri = "spotify:track:" + trackId.toLatin1();
  const QByteArray entity = QByteArray(1, '\x0a') + char(uri.size()) + uri;
  return QByteArray(1, '\x0a') + char(entity.size()) + entity;
}

QString SpotifyCanvas::canvasUrlFromProtobuf(const QByteArray &body) {
  static const QRegularExpression loop(QStringLiteral("https://[^\"'\\s\\x00-\\x1F]+\\.cnvs\\.mp4"));
  return loop.match(QString::fromLatin1(body)).captured(0);
}

void SpotifyCanvas::withClientToken(TokenReply done) {
  if (!m_clientToken.isEmpty() && m_clientTokenExpiresMs > nowMs() + 60000) {
    done(m_clientToken);
    return;
  }
  QNetworkRequest post = request(QUrl(QStringLiteral("https://clienttoken.spotify.com/v1/clienttoken")),
                                 kBrowserUserAgent);
  post.setRawHeader("Accept", "application/json");
  post.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
  // clienttoken answers a bare 400 when client_version is missing.
  const QJsonObject body{{QStringLiteral("client_data"), QJsonObject{
      {QStringLiteral("client_version"), QStringLiteral("1.2.46.25.g7f0cbf22")},
      {QStringLiteral("client_id"), QStringLiteral("d8a5ed958d274c2e8ee717e6a4b0971d")},
      {QStringLiteral("js_sdk_data"), QJsonObject{{QStringLiteral("device_brand"), QStringLiteral("Apple")},
                                                  {QStringLiteral("device_model"), QStringLiteral("Macintosh")},
                                                  {QStringLiteral("os"), QStringLiteral("macOS")},
                                                  {QStringLiteral("os_version"), QStringLiteral("10.15.7")}}}}}};
  QNetworkReply *reply = m_network.post(post, QJsonDocument(body).toJson(QJsonDocument::Compact));
  connect(reply, &QNetworkReply::finished, this, [this, reply, done = std::move(done)] {
    reply->deleteLater();
    const QString token = QJsonDocument::fromJson(reply->readAll()).object()
                              .value(QStringLiteral("granted_token")).toObject()
                              .value(QStringLiteral("token")).toString();
    if (!token.isEmpty()) {
      m_clientToken = token;
      m_clientTokenExpiresMs = nowMs() + 2 * 3600 * 1000;
    }
    // Pathfinder wants one, but canvaz-cache may still answer without it.
    done(token);
  });
}

void SpotifyCanvas::searchTrack(const QString &accessToken, const QString &clientToken,
                                const QString &title, const QString &artist,
                                std::function<void(const QString &, bool)> done) {
  const QString term = (title + QLatin1Char(' ') + artist).trimmed();
  const QJsonObject variables{{QStringLiteral("searchTerm"), term}, {QStringLiteral("offset"), 0},
                              {QStringLiteral("limit"), 10}, {QStringLiteral("numberOfTopResults"), 5},
                              {QStringLiteral("includeAudiobooks"), false},
                              {QStringLiteral("includePreReleases"), false}};
  const QJsonObject extensions{{QStringLiteral("persistedQuery"),
                                QJsonObject{{QStringLiteral("version"), 1},
                                            {QStringLiteral("sha256Hash"), QString::fromLatin1(kSearchTracksHash)}}}};
  QUrl url(QStringLiteral("https://api-partner.spotify.com/pathfinder/v1/query"));
  QUrlQuery query;
  query.addQueryItem(QStringLiteral("operationName"), QStringLiteral("searchTracks"));
  query.addQueryItem(QStringLiteral("variables"),
                     QString::fromUtf8(QJsonDocument(variables).toJson(QJsonDocument::Compact)));
  query.addQueryItem(QStringLiteral("extensions"),
                     QString::fromUtf8(QJsonDocument(extensions).toJson(QJsonDocument::Compact)));
  url.setQuery(query);
  QNetworkRequest get = request(url, kBrowserUserAgent);
  get.setRawHeader("Accept", "application/json");
  get.setRawHeader("Authorization", "Bearer " + accessToken.toUtf8());
  get.setRawHeader("Client-Token", clientToken.toUtf8());
  get.setRawHeader("App-Platform", "WebPlayer");
  QNetworkReply *reply = m_network.get(get);
  connect(reply, &QNetworkReply::finished, this,
          [this, reply, accessToken, term, title, artist, done = std::move(done)] {
    reply->deleteLater();
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (status == 401)
      tokenRejected();
    const QString id = status == 200
        ? matchingTrackId(QJsonDocument::fromJson(reply->readAll()).object(), title, artist)
        : QString();
    if (!id.isEmpty() || status == 200) {
      done(id, false);
      return;
    }
    qWarning().noquote() << "Spotify canvas: track search answered HTTP" << status;
    // Fallback only: the Web API rate-limits web-player tokens, so it must not go first.
    QUrl fallback(QStringLiteral("https://api.spotify.com/v1/search"));
    QUrlQuery fallbackQuery;
    fallbackQuery.addQueryItem(QStringLiteral("q"), term);
    fallbackQuery.addQueryItem(QStringLiteral("type"), QStringLiteral("track"));
    fallbackQuery.addQueryItem(QStringLiteral("limit"), QStringLiteral("5"));
    fallback.setQuery(fallbackQuery);
    QNetworkRequest search = request(fallback, kBrowserUserAgent);
    search.setRawHeader("Authorization", "Bearer " + accessToken.toUtf8());
    QNetworkReply *second = m_network.get(search);
    connect(second, &QNetworkReply::finished, this, [second, title, artist, done] {
      second->deleteLater();
      const int code = second->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
      done(code == 200 ? matchingTrackId(QJsonDocument::fromJson(second->readAll()).object(), title, artist)
                       : QString(),
           code != 200);
    });
  });
}

void SpotifyCanvas::fetchCanvas(const QString &trackId, const QString &accessToken,
                                const QString &clientToken, CanvasReply done) {
  QNetworkRequest post = request(QUrl(QStringLiteral("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")),
                                 kAppUserAgent);
  post.setRawHeader("Accept", "application/protobuf");
  post.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/protobuf"));
  post.setRawHeader("Authorization", "Bearer " + accessToken.toUtf8());
  post.setRawHeader("Client-Token", clientToken.toUtf8());
  QNetworkReply *reply = m_network.post(post, canvasRequest(trackId));
  connect(reply, &QNetworkReply::finished, this, [this, reply, done = std::move(done)] {
    reply->deleteLater();
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (status == 401)
      tokenRejected();
    if (status != 200) {
      qWarning().noquote() << "Spotify canvas: canvaz-cache answered HTTP" << status;
      done({}, true);
      return;
    }
    // An empty answer is normal: most songs have no canvas.
    done(canvasUrlFromProtobuf(reply->readAll()), false);
  });
}
