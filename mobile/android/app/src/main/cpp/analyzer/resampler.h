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

// Sample-rate conversion for the beat model's front end.
//
// The model accepts exactly kBeatSpectrogramSampleRate and refuses anything
// else, so something has to convert; streams arrive at 44.1 or 48 kHz. Doing it
// by dropping samples would fold everything above 11,025 Hz back down into the
// band the mel filterbank reads, and an aliased spectrogram produces a wrong
// beat grid rather than a noisy one -- the transition planner would then take
// that grid at face value. So this is a windowed-sinc resampler rather than
// decimation: the anti-alias filter is the point of it.
//
// Calls borrow the input only until they return, own the returned storage, and
// are reentrant. The work is O(taps * output) and allocates, so it belongs on a
// worker thread alongside the spectrogram.

#pragma once

#include <cstddef>
#include <vector>

namespace orchard {

// Zero crossings kept either side of each output sample. Higher is a better
// stopband at linear cost; 32 puts the aliasing well below the noise floor of
// anything that reaches this code as lossy audio.
inline constexpr size_t kResamplerZeroCrossings = 32;

/**
 * Resamples contiguous mono float PCM from `input_rate` to `output_rate`.
 *
 * Returns the input unchanged when the rates already match, and an empty vector
 * when either rate is not positive or the input is empty. There is no error
 * channel: callers treat empty as "no analysis available", which is what every
 * other stage of this pipeline does.
 */
std::vector<float> Resample(
  const std::vector<float>& input,
  double input_rate,
  double output_rate
);

}  // namespace orchard
