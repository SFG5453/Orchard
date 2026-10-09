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

// Work this desktop does for its Connect peers (artwork, mix and provider host)
// and the provider work it asks a peer for. Provider credentials never cross:
// a peer gets catalog results, opaque playback ids and decrypted bytes.

#include "connect_service.h"

#include "appearance/animated_artwork_service.h"
#include "connect_mix.h"
#include "auth/auth_manager.h"
#include "connect_tracks.h"
#include "providers/qobuz/qobuz_service.h"
#include "providers/youtube/catalog/youtube_catalog.h"
#include "system_media_bridge.h"

#include <QJsonDocument>

namespace {

// One range per request keeps a slow link from stalling other traffic for long.
constexpr qint64 kMaxRangeBytes = 4 * 1024 * 1024;

QString provider(const QJsonObject &params) { return params.value(QStringLiteral("provider")).toString(); }

} // namespace

void ConnectService::setArtwork(AnimatedArtworkService *artwork) { m_artwork = artwork; }

void ConnectService::setCatalog(YouTubeCatalog *catalog) {
  m_catalog = catalog;
  wireCatalog();
}

void ConnectService::wireCatalog() {
  if (!m_catalog)
    return;
  const auto ready = [this](quint64 requestId, const QJsonObject &result) {
    if (const QString id = m_catalogRequests.take(requestId); !id.isEmpty())
      respond(id, true, result);
  };
  connect(m_catalog, &YouTubeCatalog::searchReady, this, ready);
  connect(m_catalog, &YouTubeCatalog::albumReady, this, ready);
  connect(m_catalog, &YouTubeCatalog::artistReady, this, ready);
  connect(m_catalog, &YouTubeCatalog::requestFailed, this, [this](quint64 requestId, const QString &message) {
    if (const QString id = m_catalogRequests.take(requestId); !id.isEmpty())
      respond(id, false, {}, message.left(128));
  });
}

void ConnectService::setupMixHost() {
  m_mix = std::make_unique<ConnectMixHost>(
      [this](const QString &id, bool ok, const QJsonObject &result, const QString &error) {
        respond(id, ok, result, error);
      },
      [this](const QString &sessionId, const QJsonObject &meta, const QByteArray &payload) {
        if (!m_node)
          return QString();
        return QString::fromStdString(m_node->sendStream(sessionId.toStdString(),
                                                         QJsonDocument(meta).toJson(QJsonDocument::Compact).toStdString(),
                                                         std::string(payload.constData(), payload.size())));
      });
}

void ConnectService::respond(const QString &id, bool ok, const QJsonValue &result, const QString &error) {
  if (!m_node)
    return;
  QJsonObject response{{QStringLiteral("id"), id}, {QStringLiteral("ok"), ok}, {QStringLiteral("result"), result}};
  if (!error.isEmpty())
    response.insert(QStringLiteral("error"), error);
  m_node->respond(QJsonDocument(response).toJson(QJsonDocument::Compact).toStdString());
}

void ConnectService::handleRpc(const QJsonObject &event) {
  const QString id = event.value(QStringLiteral("id")).toString();
  const QString method = event.value(QStringLiteral("method")).toString();
  const QJsonObject params = event.value(QStringLiteral("params")).toObject();
  if (method == QStringLiteral("ResolveArtwork"))
    serveArtwork(id, params);
  else if (method == QStringLiteral("MixPrepare"))
    m_mix->prepare(id, event.value(QStringLiteral("session_id")).toString(), params);
  else if (provider(params) == QStringLiteral("qobuz"))
    serveQobuz(id, event.value(QStringLiteral("session_id")).toString(), method, params);
  else if (provider(params) == QStringLiteral("youtube"))
    serveCatalog(id, method, params);
  else
    respond(id, false, {}, QStringLiteral("unsupported"));
}

void ConnectService::serveCatalog(const QString &id, const QString &method, const QJsonObject &params) {
  if (!m_catalog || !m_auth || !m_auth->isSignedIn()) {
    respond(id, false, {}, QStringLiteral("provider_unavailable"));
    return;
  }
  const QJsonObject session = m_auth->sessionObject();
  quint64 requestId = 0;
  if (method == QStringLiteral("Search"))
    requestId = m_catalog->fetchSearch(params.value(QStringLiteral("query")).toString().left(256),
                                       params.value(QStringLiteral("filter")).toString().left(32), session);
  else if (method == QStringLiteral("GetAlbum"))
    requestId = m_catalog->fetchAlbum(params.value(QStringLiteral("id")).toString().left(128), session);
  else if (method == QStringLiteral("GetArtist"))
    requestId = m_catalog->fetchArtist(params.value(QStringLiteral("id")).toString().left(128), session);
  if (requestId == 0) {
    respond(id, false, {}, QStringLiteral("unsupported"));
    return;
  }
  m_catalogRequests.insert(requestId, id);
}

void ConnectService::serveQobuz(const QString &id, const QString &sessionId, const QString &method,
                                const QJsonObject &params) {
  if (!m_qobuz || !m_qobuz->connected()) {
    respond(id, false, {}, QStringLiteral("provider_unavailable"));
    return;
  }
  const QPointer<ConnectService> self(this);
  const QString playbackId = params.value(QStringLiteral("playback_id")).toString();
  if (method == QStringLiteral("ResolveTrack")) {
    const QVariantMap track = connect_tracks::fromWire(params.value(QStringLiteral("track")).toObject());
    m_qobuz->resolveTrack(
        track,
        [self, id](const QJsonValue &result, const QString &error) {
          if (self)
            self->respond(id, error.isEmpty(), result, error);
        },
        true);
  } else if (method == QStringLiteral("ReadRange")) {
    const auto start = static_cast<qint64>(params.value(QStringLiteral("start")).toDouble(-1));
    const auto end = static_cast<qint64>(params.value(QStringLiteral("end")).toDouble(-1));
    if (start < 0 || end < start || end - start >= kMaxRangeBytes) {
      respond(id, false, {}, QStringLiteral("invalid_request"));
      return;
    }
    m_qobuz->readRange(playbackId, start, end, [self, id, sessionId](const QByteArray &bytes, const QString &error) {
      if (!self || !self->m_node)
        return;
      if (!error.isEmpty()) {
        self->respond(id, false, {}, error);
        return;
      }
      // Bytes ride a data frame ahead of the result on the same ordered link.
      const QJsonObject header{{QStringLiteral("kind"), QStringLiteral("range")}, {QStringLiteral("rpc"), id}};
      self->m_node->sendData(sessionId.toStdString(), QJsonDocument(header).toJson(QJsonDocument::Compact).toStdString(),
                             bytes.toStdString());
      self->respond(id, true, QJsonObject{{QStringLiteral("length"), static_cast<double>(bytes.size())}});
    });
  } else if (method == QStringLiteral("PlaybackReport")) {
    const double position = params.value(QStringLiteral("position")).toDouble();
    if (params.value(QStringLiteral("started")).toBool())
      m_qobuz->playbackStarted(playbackId, position);
    else
      m_qobuz->playbackEnded(playbackId, position);
    respond(id, true, {});
  } else {
    respond(id, false, {}, QStringLiteral("unsupported"));
  }
}

void ConnectService::serveArtwork(const QString &id, const QJsonObject &params) {
  const QString kind = params.value(QStringLiteral("kind")).toString();
  const QString title = params.value(QStringLiteral("title")).toString().left(256);
  const QString artist = params.value(QStringLiteral("artist")).toString().left(256);
  const QString staticUrl =
      QString::fromUtf8(highResArtworkUrl(params.value(QStringLiteral("artwork")).toString().toUtf8()));
  QJsonObject result{{QStringLiteral("static_url"), staticUrl}};
  if (!m_artwork || title.isEmpty() || kind == QStringLiteral("artist")) {
    respond(id, true, result);
    return;
  }
  const QPointer<ConnectService> self(this);
  const auto done = [self, id, result](quint64, const QString &animated) mutable {
    if (!self)
      return;
    result.insert(QStringLiteral("animated_url"), animated);
    self->respond(id, true, result);
  };
  if (kind == QStringLiteral("album"))
    m_artwork->resolveAlbumArtwork(title, artist, ++m_artworkRequest, done);
  else
    m_artwork->resolveTrackArtwork(title, artist, params.value(QStringLiteral("album")).toString().left(256),
                                   ++m_artworkRequest, done);
}

bool ConnectService::available(const QString &provider) const {
  return m_node && !roleHost(QStringLiteral("provider:") + provider).isEmpty();
}

void ConnectService::resolveTrack(const QString &provider, const QVariantMap &track, Reply done) {
  request(QStringLiteral("provider:") + provider, QStringLiteral("ResolveTrack"),
          {{QStringLiteral("provider"), provider}, {QStringLiteral("track"), connect_tracks::toWire(track)}},
          [done = std::move(done)](const QJsonObject &event) {
            const bool ok = event.value(QStringLiteral("ok")).toBool();
            done(ok ? event.value(QStringLiteral("result")) : QJsonValue(),
                 ok ? QString() : event.value(QStringLiteral("error")).toString());
          });
}

void ConnectService::readRange(const QString &provider, const QString &playbackId, qint64 start, qint64 end,
                               Bytes done) {
  request(QStringLiteral("provider:") + provider, QStringLiteral("ReadRange"),
          {{QStringLiteral("provider"), provider},
           {QStringLiteral("playback_id"), playbackId},
           {QStringLiteral("start"), static_cast<double>(start)},
           {QStringLiteral("end"), static_cast<double>(end)}},
          [this, done = std::move(done)](const QJsonObject &event) {
            const QByteArray bytes = m_rangeBytes.take(event.value(QStringLiteral("id")).toString());
            if (event.value(QStringLiteral("ok")).toBool())
              done(bytes, {});
            else
              done({}, event.value(QStringLiteral("error")).toString(QStringLiteral("provider_unavailable")));
          });
}

void ConnectService::report(const QString &provider, const QString &playbackId, bool started, double position) {
  request(QStringLiteral("provider:") + provider, QStringLiteral("PlaybackReport"),
          {{QStringLiteral("provider"), provider},
           {QStringLiteral("playback_id"), playbackId},
           {QStringLiteral("started"), started},
           {QStringLiteral("position"), position}},
          [](const QJsonObject &) {});
}
