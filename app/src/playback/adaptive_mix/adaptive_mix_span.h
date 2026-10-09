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

#pragma once
#include <QtGlobal>
#include <algorithm>
#include <cmath>

struct AdaptiveMixSpan {
  qint64 preroll;
  qint64 mixOffset;
  qint64 mixFrames;
  qint64 tailFrames;
};

// Seeked decoder buffers can straddle the cue or start after it. Account for
// their timestamps before splicing so the native-rate handoff never repeats
// or loses a block of the incoming song.
inline AdaptiveMixSpan adaptiveMixSpan(qint64 bufferTimeUs, qint64 cueTimeUs,
                                       qint64 bufferFrames, qint64 totalFrames,
                                       qint64 mixedFrames) {
  const qint64 start =
      bufferTimeUs >= 0
          ? qRound64((bufferTimeUs - cueTimeUs) * (48000.0 / 1000000.0))
          : mixedFrames;
  const qint64 preroll =
      std::clamp(mixedFrames - start, qint64(0), bufferFrames);
  const qint64 offset =
      std::clamp(std::max(mixedFrames, start), qint64(0), totalFrames);
  const qint64 available = bufferFrames - preroll;
  const qint64 mixed = std::min(available, totalFrames - offset);
  return {preroll, offset, mixed, available - mixed};
}

// End frame in `haystack` whose preceding `window` stereo frames best match
// `needle`, within `search` frames of `nominal`. Decoder timelines disagree by
// milliseconds (Qt stamps Opus played from the start 7 ms early), so joins
// follow the audio. Silence, weak matches and ties keep the nearest frame.
inline qint64 adaptiveMatchingEnd(const float *needle, const float *haystack,
                                  qint64 haystackFrames, qint64 window,
                                  qint64 nominal, qint64 search) {
  const qint64 samples = window * 2;
  double needleEnergy = 0.0;
  for (qint64 i = 0; i < samples; ++i)
    needleEnergy += double(needle[i]) * needle[i];
  if (needleEnergy < 1e-6)
    return nominal;
  const auto score = [&](qint64 end) {
    if (end < window || end > haystackFrames)
      return -1.0;
    const float *candidate = haystack + (end - window) * 2;
    double dot = 0.0, energy = 0.0;
    for (qint64 i = 0; i < samples; ++i) {
      dot += double(needle[i]) * candidate[i];
      energy += double(candidate[i]) * candidate[i];
    }
    return energy > 0.0 ? dot / std::sqrt(needleEnergy * energy) : -1.0;
  };
  // Below this the "match" is two unrelated passages agreeing by accident.
  qint64 best = nominal;
  double bestScore = std::max(0.5, score(nominal));
  for (qint64 distance = 1; distance <= search; ++distance) {
    for (const qint64 end : {nominal - distance, nominal + distance}) {
      const double value = score(end);
      if (value > bestScore + 1e-9) {
        best = end;
        bestScore = value;
      }
    }
  }
  return best;
}
