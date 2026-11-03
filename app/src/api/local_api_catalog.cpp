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
#include "local_api.h"

#include "auth/auth_manager.h"
#include "connect/connect_service.h"
#include "integrations/apple_music.h"
#include "integrations/catalog_source.h"
#include "offline/offline_library.h"
#include "playback/playback_controller.h"
#include "providers/youtube/youtube_provider.h"

#include <QJsonArray>
#include <QDeadlineTimer>
#include <QPointer>
#include <cmath>

void LocalApi::providerCall(const QString &method, QJsonObject params, Reply reply) {
  if (m_pending.size() >= 64) { reply(503, failure("busy", "Too many catalog requests")); return; }
  params.insert("session", m_auth->sessionObject());
  const quint64 id = m_provider->invoke(method, params);
  m_pending.insert(id, std::move(reply));
  QTimer::singleShot(29000, this, [this, id] {
    if (const auto reply = m_pending.take(id)) reply(504, failure("timeout", "Catalog request timed out"));
  });
}

bool LocalApi::catalog(const QString &method, const QJsonObject &p, const Reply &reply) {
  const QStringList methods{"search", "home", "mixes", "playlist", "playlist.more", "playlist.play", "album", "artist", "library", "playlists"};
  if (!methods.contains(method)) return false;
  const auto bad = [&](const QString &message) { reply(400, failure("invalid_params", message)); return true; };
  const QString query = p.value("query").toString().trimmed();
  const QString filter = p.value("filter").toString("songs");
  if (method == "search" && (query.isEmpty() || query.size() > 512)) return bad("query must contain 1 to 512 characters");
  if (method == "search" && !QStringList{"all", "songs", "videos", "albums", "artists", "playlists"}.contains(filter))
    return bad("Unknown search filter");
  const QString id = p.value("id").toString();
  if (QStringList{"playlist", "playlist.play", "album", "artist"}.contains(method) && (id.isEmpty() || id.size() > 512))
    return bad("id must contain a catalog browse id");
  if (method == "playlist.more" && (p.value("continuation").toString().isEmpty() || p.value("continuation").toString().size() > 16384))
    return bad("continuation is required");
  if (method == "playlist.play" && p.contains("shuffle") && !p.value("shuffle").isBool()) return bad("shuffle must be a boolean");
  const QPointer<LocalApi> self(this);
  const QString source = m_source->source();
  const QDeadlineTimer deadline(29000);
  const Reply done = [self, reply, method, p, id, source, deadline](int status, const QJsonObject &body) {
    if (!self) return;
    if (deadline.hasExpired()) { reply(504, failure("timeout", "Catalog request timed out")); return; }
    if (self->m_source->source() != source) { reply(409, failure("source_changed", "Catalog source changed during the request")); return; }
    if (status != 200 || method != "playlist.play") { reply(status, body); return; }
    const auto page = body.value("result").toObject();
    const auto tracks = page.value("tracks").toArray().toVariantList();
    if (tracks.isEmpty()) { reply(404, failure("empty_playlist", "Playlist contains no playable tracks")); return; }
    self->m_playback->playCollection(tracks, 0, p.value("shuffle").toBool(), id, page.value("continuation").toString());
    reply(200, success(self->state()));
  };
  if (m_offline->offline()) {
    if (method == "search") reply(200, success(QJsonObject{{"sections", QJsonArray::fromVariantList(m_offline->search(query, filter))}}));
    else if (method == "home") reply(200, success(QJsonObject{{"sections", QJsonArray::fromVariantList(m_offline->homeSections())}}));
    else if (method == "playlists") reply(200, success(QJsonArray::fromVariantList(m_offline->playlists())));
    else if (method == "playlist" || method == "playlist.play" || method == "album") {
      const auto detail = m_offline->collectionDetail(id);
      if (detail.isEmpty()) reply(404, failure("not_found", "Offline collection was not found"));
      else done(200, success(QJsonObject::fromVariantMap(detail)));
    } else reply(409, failure("offline", "This catalog operation needs a connection"));
    return true;
  }
  if (m_source->apple()) {
    if (!m_apple->connected()) { reply(409, failure("provider_unavailable", "Connect Apple Music first")); return true; }
    const auto appleReply = [self, done](const QJsonObject &page, const QString &error) {
      if (self) done(error.isEmpty() ? 200 : 502, error.isEmpty() ? success(page) : failure("provider_error", error));
    };
    if (method == "search") m_apple->search(query, filter, appleReply);
    else if (method == "home" || method == "mixes") m_apple->home(appleReply);
    else if (method == "library") m_apple->library(appleReply);
    else if (method == "playlists") m_apple->libraryPlaylists(appleReply);
    else if (method == "playlist.more") reply(409, failure("unavailable", "Apple Music pages do not use YouTube continuations"));
    else if (!AppleMusic::isAppleMusicId(id)) return bad("Use an Apple Music catalog id while Apple Music is selected");
    else m_apple->detail(id, appleReply);
    return true;
  }
  if (!m_auth->isSignedIn()) { reply(409, failure("provider_unavailable", "Sign in to YouTube Music first")); return true; }
  if (AppleMusic::isAppleMusicId(id)) return bad("Use a YouTube Music browse id while YouTube Music is selected");
  if (method == "search") providerCall("catalog.search", {{"query", query}, {"filter", filter}}, done);
  else if (method == "mixes") providerCall("catalog.browse", {{"browseId", "FEmusic_mixed_for_you"}}, done);
  else if (method == "playlist.play" || method == "playlist" || method == "album" || method == "artist")
    providerCall("catalog." + (method == "playlist.play" ? QString("playlist") : method), {{"browseId", id}}, done);
  else if (method == "playlist.more") {
    const auto start = p.value("startIndex");
    if (!start.isUndefined() && (!start.isDouble() || start.toDouble() < 0 || start.toDouble() > 100000 || std::floor(start.toDouble()) != start.toDouble()))
      return bad("startIndex must be a nonnegative integer");
    providerCall("catalog.playlist.more", {{"continuation", p.value("continuation")}, {"startIndex", start.toInt()}}, done);
  } else providerCall("catalog." + method, {}, done);
  return true;
}

// Mirrors PlaybackController::startAppleTrack so rejected tracks fail synchronously.
QString LocalApi::trackUnavailable(const QJsonObject &track) const {
  if (!AppleMusic::isAppleMusicId(track.value("id").toString())) return {};
  if (!m_source->apple()) return "Select Apple Music as the music source to play Apple Music tracks";
  if (!m_apple->connected()) return "Connect Apple Music first";
  return {};
}

void LocalApi::songRequest(const QJsonObject &p, Reply reply) {
  if ((p.contains("next") && !p.value("next").isBool()) ||
      (p.contains("deduplicate") && !p.value("deduplicate").isBool())) {
    reply(400, failure("invalid_params", "next and deduplicate must be booleans")); return;
  }
  if (p.contains("maxDuration") && (!p.value("maxDuration").isDouble() || p.value("maxDuration").toDouble() <= 0)) {
    reply(400, failure("invalid_params", "maxDuration must be a positive number of seconds")); return;
  }
  const QPointer<LocalApi> self(this);
  const QString source = m_source->source();
  const QDeadlineTimer deadline(29000);
  catalog("search", {{"query", p.value("query")}, {"filter", "songs"}}, [self, p, reply, source, deadline](int status, const QJsonObject &body) {
    if (!self) return;
    if (deadline.hasExpired()) { reply(504, failure("timeout", "Song request timed out")); return; }
    if (self->m_source->source() != source) { reply(409, failure("source_changed", "Catalog source changed during the request")); return; }
    if (status != 200) { reply(status, body); return; }
    bool duplicate = false;
    bool tooLong = false;
    for (const auto &section : body.value("result").toObject().value("sections").toArray()) {
      for (const auto &entry : section.toObject().value("items").toArray()) {
        QJsonObject value = entry.toObject();
        const QString id = value.value("id").toString();
        const QString type = value.value("type").toString();
        if (id.isEmpty() || value.value("unplayable").toBool() ||
            (type != "song" && type != "track" && type != "video")) continue;
        if (p.contains("maxDuration")) {
          const double duration = value.value("durationSeconds").toDouble();
          if (duration <= 0 || duration > p.value("maxDuration").toDouble()) { tooLong = true; continue; }
        }
        if (p.value("deduplicate").toBool(true)) {
          bool exists = self->m_playback->shownTrack().value("id").toString() == id;
          for (const auto &queued : self->m_playback->shownQueue()) exists |= queued.toMap().value("id").toString() == id;
          if (exists) { duplicate = true; continue; }
        }
        value = QJsonObject::fromVariantMap(PlaybackController::sanitizeTrack(value.toVariantMap()));
        self->m_playback->enqueue(value.toVariantMap(), p.value("next").toBool());
        reply(200, success(QJsonObject{{"track", value}, {"queued", true}, {"next", p.value("next").toBool()}}));
        return;
      }
    }
    reply(duplicate ? 409 : 404, failure(duplicate ? "duplicate" : tooLong ? "duration_limit" : "not_found",
        duplicate ? "Matching tracks are already playing or queued" : "No playable track matched the request constraints"));
  });
}
