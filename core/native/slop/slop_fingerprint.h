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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include <cstddef>
#include <memory>
#include <optional>
#include <span>

struct SlopVerdict {
  float probability = 0;
  float logit = 0;
  float seconds = 0;
};

// Streaming decoder-fakeprint detector. Feed interleaved float PCM in playback order.
class SlopFingerprintDetector final {
public:
  static constexpr float kDefaultThreshold = 0.9f;
  static std::unique_ptr<SlopFingerprintDetector> create(int sampleRate, int channels);

  static std::unique_ptr<SlopFingerprintDetector> create(int sampleRate, int channels,
                                                         std::span<const float> weights);

  ~SlopFingerprintDetector();
  SlopFingerprintDetector(const SlopFingerprintDetector &) = delete;
  SlopFingerprintDetector &operator=(const SlopFingerprintDetector &) = delete;

  void push(const float *samples, size_t frames);
  [[nodiscard]] float seconds() const;
  [[nodiscard]] bool isFull() const;
  [[nodiscard]] std::optional<SlopVerdict> verdict() const;
  void reset();

private:
  struct Impl;
  explicit SlopFingerprintDetector(std::unique_ptr<Impl> impl);
  std::unique_ptr<Impl> m_impl;
};
