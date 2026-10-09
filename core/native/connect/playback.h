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

#pragma once

#include "connect/protocol.h"

#include <cstddef>
#include <cstdint>
#include <string>

namespace orchard::connect {

// Upcoming items sent per state message; the full length rides in queue_total.
constexpr std::size_t kStateQueueLimit = 200;
// Largest queue a command may carry, matching desktop's queue cap.
constexpr std::size_t kCommandQueueLimit = 2500;

// The target's authoritative playback state. Positions are seconds.
struct PlaybackSnapshot {
  Json track;            // canonical track or null
  double position = 0.0; // at `timestamp`
  double duration = 0.0;
  bool playing = false;
  bool buffering = false;
  Json queue = Json::array(); // upcoming tracks only
  std::size_t queueTotal = 0;
  double volume = 1.0;
  std::string repeat = "off"; // off, all or one
  bool shuffle = false;
  Json transition;           // null or {active, progress, track}
  std::string contextTitle;
  std::int64_t timestamp = 0; // sender's monotonic ms when position was read; never sent
  Json extra;                 // platform fields carried through untouched

  [[nodiscard]] bool hasMedia() const { return track.is_object(); }
  // A loaded track that is playing or buffering toward playing.
  [[nodiscard]] bool active() const { return hasMedia() && (playing || buffering); }
};

Json toJson(const PlaybackSnapshot &snapshot);
PlaybackSnapshot snapshotFromJson(const Json &json, std::size_t queueLimit = kStateQueueLimit);

// Canonical track: id plus display fields. Null when the input is not a usable track.
Json sanitizeTrack(const Json &track);

enum class InitialOutcome { TargetWins, Transfer, Nothing };

// Runs once per session, during RESOLVING_INITIAL_PLAYBACK only.
InitialOutcome resolveInitialPlayback(const PlaybackSnapshot &target, const PlaybackSnapshot &connecting);
const char *outcomeName(InitialOutcome outcome);

double projectPosition(const PlaybackSnapshot &snapshot, std::int64_t elapsedMs);

// True when a controller could not have predicted `next` from `previous` and its clock.
bool significantChange(const PlaybackSnapshot &previous, const PlaybackSnapshot &next, std::int64_t elapsedMs);

// Validates controller intent in place. Empty on success, otherwise a reject code.
std::string normalizeCommand(Json &command);

} // namespace orchard::connect
