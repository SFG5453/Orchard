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

#include "connect/connect_service.h"
#include "home/home_controller.h"
#include "library/library_actions.h"
#include "playback/playback_controller.h"

#include <QJsonArray>
#include <QPointer>
#include <cmath>
#include <memory>

namespace {
bool number(const QJsonObject &params, const char *key, double low, double high) {
  const auto value = params.value(QLatin1String(key));
  return value.isDouble() && std::isfinite(value.toDouble()) && value.toDouble() >= low && value.toDouble() <= high;
}
bool index(const QJsonObject &params, const char *key, qsizetype size) {
  return number(params, key, 0, size - 1) && std::floor(params.value(QLatin1String(key)).toDouble()) == params.value(QLatin1String(key)).toDouble();
}
bool track(const QJsonObject &value) {
  const QString type = value.value("type").toString("song");
  return value.value("id").isString() && !value.value("id").toString().trimmed().isEmpty() &&
      !value.value("unplayable").toBool() && (type == "song" || type == "track" || type == "video");
}
}

void LocalApi::dispatch(const QString &method, const QJsonObject &params, Reply callback) {
  auto completed = std::make_shared<bool>(false);
  const Reply reply = [completed, callback = std::move(callback)](int status, const QJsonObject &body) {
    if (*completed) return;
    *completed = true;
    callback(status, body);
  };
  QTimer::singleShot(30000, this, [reply] { reply(504, failure("timeout", "Operation timed out")); });
  if (method == "api") reply(200, success(description()));
  else if (method == "state") reply(200, success(state()));
  else if (method == "mix.state") reply(200, success(mixState()));
  else if (method == "queue") reply(200, success(QJsonArray::fromVariantList(PlaybackController::sanitizeTrackList(m_playback->shownQueue()))));
  else if (method == "history") reply(200, success(QJsonArray::fromVariantList(PlaybackController::sanitizeTrackList(m_playback->shownHistory()))));
  else if (method == "connect") reply(200, success(QJsonObject{{"state", m_connect->state()}, {"role", m_connect->role()},
      {"peer", QJsonObject::fromVariantMap(m_connect->peer())}, {"devices", QJsonArray::fromVariantList(m_connect->devices())}}));
  else if (method == "request") songRequest(params, reply);
  else if (!control(method, params, reply) && !catalog(method, params, reply))
    reply(404, failure("unknown_method", "Unknown API method: " + method));
}

bool LocalApi::control(const QString &method, const QJsonObject &p, const Reply &reply) {
  const auto bad = [&](const QString &message) { reply(400, failure("invalid_params", message)); return true; };
  const auto unavailable = [&](const QString &message) { reply(409, failure("unavailable", message)); return true; };
  if (method == "play") m_playback->play();
  else if (method == "pause") m_playback->pause();
  else if (method == "toggle") m_playback->toggle();
  else if (method == "stop") {
    if (m_playback->remoteActive()) return unavailable("Stop is unavailable while controlling a Connect target; use pause");
    m_playback->stop();
  } else if (method == "next") m_playback->next();
  else if (method == "previous") m_playback->previous();
  else if (method == "seek") {
    if (p.contains("offset")) {
      if (!number(p, "offset", -86400, 86400)) return bad("offset must be a number of seconds");
      m_playback->seekBy(p.value("offset").toDouble());
    } else {
      if (!number(p, "position", 0, 86400)) return bad("position must be a nonnegative number of seconds");
      m_playback->seek(p.value("position").toDouble());
    }
  } else if (method == "volume") {
    if (p.contains("offset")) {
      if (!number(p, "offset", -1, 1)) return bad("offset must be between -1 and 1");
      setVolume(volume() + p.value("offset").toDouble());
    } else {
      if (!number(p, "value", 0, 1)) return bad("value must be between 0 and 1");
      setVolume(p.value("value").toDouble());
    }
  } else if (method == "mute") {
    if (volume() > 0) { m_unmutedVolume = volume(); setVolume(0); }
    else setVolume(m_unmutedVolume);
  } else if (method == "shuffle") {
    if (p.contains("enabled") && !p.value("enabled").isBool()) return bad("enabled must be a boolean");
    m_playback->setShuffleEnabled(p.value("enabled").toBool(!m_playback->shownShuffle()));
  } else if (method == "repeat") {
    if (!p.contains("mode")) m_playback->cycleRepeatMode();
    else {
      const QString mode = p.value("mode").toString();
      if (mode != "off" && mode != "one" && mode != "all") return bad("mode must be off, one or all");
      m_playback->setRepeatMode(mode);
    }
  } else if (method == "like") {
    if (m_playback->remoteActive()) return unavailable("Likes apply to this desktop's current track");
    if (!m_library->likeKnown() || m_library->likeBusy() || m_playback->shownTrack().isEmpty())
      return unavailable("The current track's like status is unavailable or busy");
    m_library->toggleCurrentLike();
  } else if (method == "queue.clear") m_playback->clearQueue();
  else if (method == "queue.add" || method == "track.play") {
    const auto value = p.value("track").toObject();
    if (!track(value)) return bad("track must contain a nonempty id");
    if ((p.contains("next") && !p.value("next").isBool()) ||
        (p.contains("deduplicate") && !p.value("deduplicate").isBool())) return bad("next and deduplicate must be booleans");
    if (method == "queue.add" && p.value("deduplicate").toBool()) {
      const auto id = value.value("id").toString();
      bool duplicate = m_playback->shownTrack().value("id").toString() == id;
      for (const auto &item : m_playback->shownQueue()) duplicate |= item.toMap().value("id").toString() == id;
      if (duplicate) { reply(409, failure("duplicate", "Track is already playing or queued")); return true; }
    }
    if (const QString reason = trackUnavailable(value); !reason.isEmpty()) return unavailable(reason);
    auto clean = PlaybackController::sanitizeTrack(value.toVariantMap());
    if (!clean.contains("type")) clean.insert("type", "song");
    if (method == "track.play") m_playback->playSong(clean);
    else m_playback->enqueue(clean, p.value("next").toBool());
  } else if (method == "queue.remove" || method == "queue.play") {
    if (!index(p, "index", m_playback->shownQueue().size())) return bad("index must identify an upcoming queue item");
    if (method == "queue.remove") m_playback->removeFromQueue(p.value("index").toInt());
    else m_playback->playQueueIndex(p.value("index").toInt());
  } else if (method == "queue.move") {
    const auto count = m_playback->shownQueue().size();
    if (!index(p, "from", count) || !index(p, "to", count)) return bad("from and to must identify upcoming queue items");
    m_playback->moveQueueItem(p.value("from").toInt(), p.value("to").toInt());
  } else if (method == "queue.replace") {
    const auto tracks = p.value("tracks").toArray();
    if (!p.value("tracks").isArray() || tracks.isEmpty() || tracks.size() > 2500) return bad("tracks must contain 1 to 2500 tracks");
    for (const auto &item : tracks) {
      if (!track(item.toObject())) return bad("Every track must contain a playable id and track type");
      if (const QString reason = trackUnavailable(item.toObject()); !reason.isEmpty()) return unavailable(reason);
    }
    if (p.contains("index") && !index(p, "index", tracks.size())) return bad("index must identify a supplied track");
    if (p.contains("position") && !number(p, "position", 0, 86400)) return bad("position must be nonnegative seconds");
    if (p.contains("play") && !p.value("play").isBool()) return bad("play must be a boolean");
    auto values = PlaybackController::sanitizeTrackList(tracks.toVariantList());
    for (auto &item : values) { auto value = item.toMap(); if (!value.contains("type")) value.insert("type", "song"); item = value; }
    if (m_connect->controlling()) m_connect->command("replace_queue", {{"tracks", values}, {"index", p.value("index").toInt()},
        {"position", p.value("position").toDouble()}, {"play", p.value("play").toBool(true)}});
    else m_playback->playFrom(values, p.value("index").toInt(), p.value("position").toDouble(), p.value("play").toBool(true));
  } else if (method == "mix.best") {
    if (m_playback->remoteActive()) return unavailable("Best Mix requires local playback");
    m_playback->toggleBestMix();
  } else if (method == "mix.configure") {
    if (m_playback->remoteActive()) return unavailable("Mix settings apply to local playback");
    if ((p.contains("crossfadeEnabled") && !p.value("crossfadeEnabled").isBool()) ||
        (p.contains("gaplessEnabled") && !p.value("gaplessEnabled").isBool())) return bad("Enabled flags must be booleans");
    if (p.contains("duration") && (!number(p, "duration", 1, 12) || std::floor(p.value("duration").toDouble()) != p.value("duration").toDouble()))
      return bad("duration must be an integer from 1 to 12 seconds");
    const QString mode = p.value("mode").toString();
    if (p.contains("mode") && mode != "adaptive" && mode != "standard") return bad("mode must be adaptive or standard");
    if (mode == "adaptive" && !m_playback->adaptiveMix()->available()) return unavailable("Adaptive mix is unavailable");
    if (p.contains("crossfadeEnabled")) m_playback->setCrossfadeEnabled(p.value("crossfadeEnabled").toBool());
    if (p.contains("gaplessEnabled")) m_playback->setGaplessEnabled(p.value("gaplessEnabled").toBool());
    if (p.contains("duration")) m_playback->setCrossfadeDuration(p.value("duration").toInt());
    if (p.contains("mode")) m_playback->adaptiveMix()->setMode(mode);
  } else return false;
  reply(200, success(state()));
  return true;
}
