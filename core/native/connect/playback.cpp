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

#include "connect/playback.h"

#include <algorithm>
#include <cmath>

namespace orchard::connect {
namespace {

constexpr double kMaxSeconds = 24.0 * 60.0 * 60.0;
// A seek or drift larger than this is worth a state message.
constexpr double kPositionJump = 1.5;

double clampSeconds(double value) { return std::clamp(value, 0.0, kMaxSeconds); }

Json sanitizeTrackList(const Json &tracks, std::size_t limit) {
  Json out = Json::array();
  if (!tracks.is_array())
    return out;
  for (const Json &item : tracks) {
    if (out.size() >= limit)
      break;
    Json track = sanitizeTrack(item);
    if (!track.is_null())
      out.push_back(std::move(track));
  }
  return out;
}

bool sameQueue(const Json &left, const Json &right) {
  if (left.size() != right.size())
    return false;
  for (std::size_t i = 0; i < left.size(); ++i) {
    if (jsonString(left[i], "id") != jsonString(right[i], "id"))
      return false;
  }
  return true;
}

bool validRepeat(const std::string &mode) { return mode == "off" || mode == "all" || mode == "one"; }

bool readIndex(const Json &args, const char *key, int &out) {
  if (!args.contains(key) || !args[key].is_number_integer())
    return false;
  const auto value = args[key].get<long long>();
  if (value < 0 || value >= static_cast<long long>(kCommandQueueLimit) * 4)
    return false;
  out = static_cast<int>(value);
  return true;
}

} // namespace

Json sanitizeTrack(const Json &track) {
  if (!track.is_object())
    return nullptr;
  const std::string id = jsonString(track, "id", 512);
  if (id.empty() || id.size() > 256)
    return nullptr;
  Json out = {{"id", id}};
  for (const char *key : {"title", "artist", "album", "artwork", "provider", "album_id", "artist_id"}) {
    const std::string value = jsonString(track, key, 2048);
    if (!value.empty())
      out[key] = value;
  }
  if (!out.contains("provider"))
    out["provider"] = "youtube";
  const double duration = jsonNumber(track, "duration", 0.0);
  if (duration > 0.0)
    out["duration"] = clampSeconds(duration);
  if (track.contains("explicit") && track["explicit"].is_boolean())
    out["explicit"] = track["explicit"];
  // Platform hints ride along when small; a peer that does not know them ignores them.
  if (track.contains("extra") && track["extra"].is_object() && track["extra"].dump().size() <= 8192)
    out["extra"] = track["extra"];
  return out;
}

Json toJson(const PlaybackSnapshot &snapshot) {
  Json json = snapshot.extra.is_object() ? snapshot.extra : Json::object();
  json["has_media"] = snapshot.hasMedia();
  json["track"] = snapshot.track.is_object() ? snapshot.track : Json();
  json["position"] = snapshot.position;
  json["duration"] = snapshot.duration;
  json["playing"] = snapshot.playing;
  json["buffering"] = snapshot.buffering;
  json["queue"] = snapshot.queue.is_array() ? snapshot.queue : Json::array();
  json["queue_total"] = std::max(snapshot.queueTotal, json["queue"].size());
  json["volume"] = snapshot.volume;
  json["repeat"] = snapshot.repeat;
  json["shuffle"] = snapshot.shuffle;
  json["transition"] = snapshot.transition.is_object() ? snapshot.transition : Json();
  json["context_title"] = snapshot.contextTitle;
  return json;
}

PlaybackSnapshot snapshotFromJson(const Json &json, std::size_t queueLimit) {
  PlaybackSnapshot snapshot;
  if (!json.is_object())
    return snapshot;
  if (json.contains("track"))
    snapshot.track = sanitizeTrack(json["track"]);
  snapshot.position = clampSeconds(jsonNumber(json, "position"));
  snapshot.duration = clampSeconds(jsonNumber(json, "duration"));
  snapshot.playing = jsonBool(json, "playing") && snapshot.hasMedia();
  snapshot.buffering = jsonBool(json, "buffering") && snapshot.hasMedia();
  if (json.contains("queue"))
    snapshot.queue = sanitizeTrackList(json["queue"], queueLimit);
  snapshot.queueTotal = static_cast<std::size_t>(
      std::clamp(jsonNumber(json, "queue_total", static_cast<double>(snapshot.queue.size())), 0.0, 1e6));
  snapshot.queueTotal = std::max(snapshot.queueTotal, snapshot.queue.size());
  snapshot.volume = std::clamp(jsonNumber(json, "volume", 1.0), 0.0, 1.0);
  const std::string repeat = jsonString(json, "repeat", 8);
  snapshot.repeat = validRepeat(repeat) ? repeat : "off";
  snapshot.shuffle = jsonBool(json, "shuffle");
  if (json.contains("transition") && json["transition"].is_object())
    snapshot.transition = json["transition"];
  snapshot.contextTitle = jsonString(json, "context_title", 256);
  snapshot.extra = Json::object();
  static const char *known[] = {"has_media", "track", "position", "duration", "playing", "buffering", "queue",
                                "queue_total", "volume", "repeat", "shuffle", "transition", "context_title"};
  for (const auto &[key, value] : json.items()) {
    if (std::none_of(std::begin(known), std::end(known), [&](const char *k) { return key == k; }) &&
        key.size() <= 64)
      snapshot.extra[key] = value;
  }
  return snapshot;
}

InitialOutcome resolveInitialPlayback(const PlaybackSnapshot &target, const PlaybackSnapshot &connecting) {
  if (target.active())
    return InitialOutcome::TargetWins;
  if (connecting.active())
    return InitialOutcome::Transfer;
  return InitialOutcome::Nothing;
}

const char *outcomeName(InitialOutcome outcome) {
  switch (outcome) {
  case InitialOutcome::TargetWins:
    return "target_wins";
  case InitialOutcome::Transfer:
    return "transferred";
  case InitialOutcome::Nothing:
    break;
  }
  return "none";
}

double projectPosition(const PlaybackSnapshot &snapshot, std::int64_t elapsedMs) {
  if (!snapshot.playing || snapshot.buffering || elapsedMs <= 0)
    return snapshot.position;
  double projected = snapshot.position + static_cast<double>(elapsedMs) / 1000.0;
  if (snapshot.duration > 0.0)
    projected = std::min(projected, snapshot.duration);
  return projected;
}

bool significantChange(const PlaybackSnapshot &previous, const PlaybackSnapshot &next, std::int64_t elapsedMs) {
  if (jsonString(previous.track, "id") != jsonString(next.track, "id") || previous.playing != next.playing ||
      previous.buffering != next.buffering || previous.repeat != next.repeat || previous.shuffle != next.shuffle ||
      previous.queueTotal != next.queueTotal || !sameQueue(previous.queue, next.queue) ||
      previous.contextTitle != next.contextTitle)
    return true;
  if (std::abs(previous.volume - next.volume) > 0.005 || std::abs(previous.duration - next.duration) > 0.5)
    return true;
  if (previous.transition.is_object() != next.transition.is_object())
    return true;
  return std::abs(projectPosition(previous, elapsedMs) - next.position) > kPositionJump;
}

std::string normalizeCommand(Json &command) {
  if (!command.is_object())
    return code::InvalidCommand;
  const std::string action = jsonString(command, "action", 32);
  Json args = command.contains("args") && command["args"].is_object() ? command["args"] : Json::object();
  Json clean = Json::object();

  if (action == "play" || action == "pause" || action == "toggle" || action == "next" || action == "previous" ||
      action == "clear_queue") {
    // No arguments.
  } else if (action == "seek") {
    if (!args.contains("position") || !args["position"].is_number())
      return code::InvalidCommand;
    clean["position"] = clampSeconds(jsonNumber(args, "position"));
  } else if (action == "set_volume") {
    if (!args.contains("volume") || !args["volume"].is_number())
      return code::InvalidCommand;
    clean["volume"] = std::clamp(jsonNumber(args, "volume"), 0.0, 1.0);
  } else if (action == "set_repeat") {
    const std::string mode = jsonString(args, "mode", 8);
    if (!validRepeat(mode))
      return code::InvalidCommand;
    clean["mode"] = mode;
  } else if (action == "set_shuffle") {
    if (!args.contains("enabled") || !args["enabled"].is_boolean())
      return code::InvalidCommand;
    clean["enabled"] = args["enabled"];
  } else if (action == "play_queue_index" || action == "remove_queue_item") {
    int index = 0;
    if (!readIndex(args, "index", index))
      return code::InvalidCommand;
    clean["index"] = index;
  } else if (action == "move_queue_item") {
    int from = 0, to = 0;
    if (!readIndex(args, "from", from) || !readIndex(args, "to", to))
      return code::InvalidCommand;
    clean["from"] = from;
    clean["to"] = to;
  } else if (action == "enqueue") {
    Json track = sanitizeTrack(args.value("track", Json()));
    if (track.is_null())
      return code::InvalidCommand;
    clean["track"] = std::move(track);
    clean["next"] = jsonBool(args, "next");
  } else if (action == "play_track" || action == "replace_queue") {
    Json tracks = sanitizeTrackList(args.value("tracks", Json::array()), kCommandQueueLimit);
    if (action == "play_track") {
      Json track = sanitizeTrack(args.value("track", Json()));
      if (track.is_null())
        return code::InvalidCommand;
      clean["track"] = std::move(track);
    } else if (tracks.empty()) {
      return code::InvalidCommand;
    }
    const int index = static_cast<int>(jsonNumber(args, "index", 0));
    clean["tracks"] = std::move(tracks);
    clean["index"] = std::clamp(index, 0, std::max(0, static_cast<int>(clean["tracks"].size()) - 1));
    clean["position"] = clampSeconds(jsonNumber(args, "position"));
    clean["play"] = jsonBool(args, "play", true);
    clean["context_title"] = jsonString(args, "context_title", 256);
  } else {
    return code::InvalidCommand;
  }
  command = {{"action", action}, {"args", std::move(clean)}};
  return {};
}

} // namespace orchard::connect
