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

#include <QAudioBuffer>
#include <QAudioFormat>

#include <array>
#include <vector>

// Renders a 5.1 bed to stereo through head-related impulse responses, placing the speakers
// at the ITU positions (0, +-30 and +-110 degrees). One instance per decoded stream, since
// each keeps the tail of the previous buffer.
class BinauralRenderer {
public:
  // The format a player's decoder delivers for this renderer.
  static QAudioFormat inputFormat();
  // A stereo buffer in `output` with the input's start time; invalid when the input is not 5.1 float.
  QAudioBuffer render(const QAudioBuffer &input, const QAudioFormat &output);
  void reset();

private:
  // Mid and side of the front pair, mid and side of the surrounds, then the center.
  static constexpr int kSignals = 5;
  std::array<std::vector<float>, kSignals> m_history;
};
