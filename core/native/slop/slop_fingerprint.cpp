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

#include "slop_fingerprint.h"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <complex>
#include <cstdint>
#include <limits>
#include <numeric>
#include <span>
#include <vector>

namespace {
constexpr int kModelRate = 16'000;
constexpr int kNfft = 8192;
constexpr int kHalf = kNfft / 2;
constexpr int kHop = kNfft / 4;
constexpr int kBinLo = 512;
constexpr int kBinHi = 4096;
constexpr int kFeatures = kBinHi - kBinLo + 1;
constexpr int kHullArea = 10;
constexpr size_t kMaxSamples = 300 * kModelRate;
constexpr size_t kMaxKernelTaps = 1 << 20;
constexpr double kPi = 3.14159265358979323846;

// Centered, reflect-padded 8192-point power STFT. A radix-2 FFT keeps the
// model independent of platform FFT libraries and their packaging adventures.
class Spectrum {
public:
  Spectrum() : m_window(kNfft), m_frame(kNfft), m_sums(kFeatures, 0.0)
  {
    for (int i = 0; i < kNfft; ++i) {
      m_window[i] = float(0.5 - 0.5 * std::cos(2.0 * kPi * i / kNfft));
      unsigned reversed = 0;
      for (int bit = 0; bit < 13; ++bit) reversed = (reversed << 1) | ((unsigned(i) >> bit) & 1);
      m_reversed[i] = reversed;
    }
    for (int i = 0; i < kHalf; ++i) {
      const double angle = -2.0 * kPi * i / kNfft;
      m_twiddles[i] = {float(std::cos(angle)), float(std::sin(angle))};
    }
  }

  void push(std::span<const float> block)
  {
    const size_t take = std::min(block.size(), kMaxSamples - m_length);
    m_samples.insert(m_samples.end(), block.begin(), block.begin() + take);
    m_length += take;
    while (m_nextFrame * kHop + kHalf <= m_length) {
      accumulate(m_sums, m_frame, m_nextFrame * kHop);
      ++m_nextFrame;
    }
    // Frames zero and one still look backward at the start of the song.
    const size_t keepFrom = m_nextFrame * kHop > kHalf ? m_nextFrame * kHop - kHalf : 0;
    if (keepFrom > m_base) {
      m_samples.erase(m_samples.begin(), m_samples.begin() + (keepFrom - m_base));
      m_base = keepFrom;
    }
  }

  [[nodiscard]] size_t length() const { return m_length; }

  [[nodiscard]] std::vector<float> meanDb() const
  {
    std::vector<double> sums = m_sums;
    std::vector<std::complex<float>> frame(kNfft);
    const size_t frames = 1 + m_length / kHop;
    for (size_t index = m_nextFrame; index < frames; ++index)
      accumulate(sums, frame, index * kHop);
    std::vector<float> mean(kFeatures);
    for (int i = 0; i < kFeatures; ++i) mean[i] = float(sums[i] / double(frames));
    return mean;
  }

  void reset()
  {
    m_samples.clear();
    m_base = m_length = m_nextFrame = 0;
    std::fill(m_sums.begin(), m_sums.end(), 0.0);
  }

private:
  [[nodiscard]] size_t reflect(int64_t index) const
  {
    const int64_t last = int64_t(m_length) - 1;
    if (index < 0) index = -index;
    if (index > last) index = 2 * last - index;
    return size_t(std::clamp<int64_t>(index, 0, last));
  }

  void transform(std::vector<std::complex<float>> &frame) const
  {
    for (int i = 0; i < kNfft; ++i)
      if (unsigned(i) < m_reversed[i]) std::swap(frame[i], frame[m_reversed[i]]);
    for (int length = 2; length <= kNfft; length *= 2) {
      const int half = length / 2;
      const int step = kNfft / length;
      for (int start = 0; start < kNfft; start += length)
        for (int k = 0; k < half; ++k) {
          const auto even = frame[start + k];
          const auto odd = frame[start + k + half] * m_twiddles[k * step];
          frame[start + k] = even + odd;
          frame[start + k + half] = even - odd;
        }
    }
  }

  void accumulate(std::vector<double> &sums, std::vector<std::complex<float>> &frame,
                  size_t center) const
  {
    for (int i = 0; i < kNfft; ++i) {
      const size_t sample = reflect(int64_t(center) + i - kHalf) - m_base;
      frame[i] = {m_samples[sample] * m_window[i], 0.0f};
    }
    transform(frame);
    for (int i = 0; i < kFeatures; ++i) {
      const float power = std::clamp(std::norm(frame[kBinLo + i]), 1e-10f, 1e6f);
      sums[i] += 10.0 * std::log10(double(power));
    }
  }

  std::vector<float> m_window;
  std::vector<std::complex<float>> m_frame;
  std::array<unsigned, kNfft> m_reversed{};
  std::array<std::complex<float>, kHalf> m_twiddles{};
  std::vector<float> m_samples;
  size_t m_base = 0;
  size_t m_length = 0;
  size_t m_nextFrame = 0;
  std::vector<double> m_sums;
};

// Streaming equivalent of torchaudio's sinc_interp_hann (width 6, rolloff .99).
class Resampler {
public:
  static std::optional<Resampler> create(int from)
  {
    Resampler result;
    const int divisor = std::gcd(from, kModelRate);
    result.m_orig = size_t(from / divisor);
    result.m_new = size_t(kModelRate / divisor);
    if (result.m_orig == result.m_new) {
      result.m_orig = result.m_new = result.m_taps = 1;
      result.m_kernels = {1.0f};
      return result;
    }
    constexpr double lpw = 6.0;
    const double baseFreq = double(std::min(result.m_orig, result.m_new)) * 0.99;
    result.m_width = size_t(std::ceil(lpw * result.m_orig / baseFreq));
    result.m_taps = 2 * result.m_width + result.m_orig;
    if (result.m_taps * result.m_new > kMaxKernelTaps) return std::nullopt;
    result.m_kernels.reserve(result.m_new * result.m_taps);
    for (size_t phase = 0; phase < result.m_new; ++phase)
      for (size_t k = 0; k < result.m_taps; ++k) {
        const double index = (double(k) - double(result.m_width)) / result.m_orig;
        double t = std::clamp((-double(phase) / result.m_new + index) * baseFreq, -lpw, lpw);
        const double window = std::pow(std::cos(t * kPi / lpw / 2.0), 2);
        t *= kPi;
        const double sinc = t == 0.0 ? 1.0 : std::sin(t) / t;
        result.m_kernels.push_back(float(sinc * window * baseFreq / result.m_orig));
      }
    result.reset();
    return result;
  }

  void push(std::span<const float> input, Spectrum &spectrum)
  {
    if (m_orig == 1 && m_new == 1) {
      spectrum.push(input);
      return;
    }
    m_input.insert(m_input.end(), input.begin(), input.end());
    m_out.clear();
    while (m_block * m_orig + m_taps <= m_base + m_input.size()) {
      const size_t start = m_block * m_orig - m_base;
      for (size_t phase = 0; phase < m_new; ++phase) {
        const float *kernel = m_kernels.data() + phase * m_taps;
        float sum = 0;
        for (size_t tap = 0; tap < m_taps; ++tap) sum += kernel[tap] * m_input[start + tap];
        m_out.push_back(sum);
      }
      ++m_block;
    }
    const size_t consumed = m_block * m_orig - m_base;
    m_input.erase(m_input.begin(), m_input.begin() + consumed);
    m_base += consumed;
    spectrum.push(m_out);
  }

  void reset()
  {
    m_input.assign(m_width, 0.0f);
    m_base = m_block = 0;
  }

private:
  size_t m_orig = 0;
  size_t m_new = 0;
  size_t m_width = 0;
  size_t m_taps = 0;
  std::vector<float> m_kernels;
  std::vector<float> m_input;
  size_t m_base = 0;
  size_t m_block = 0;
  std::vector<float> m_out;
};

std::vector<float> fakeprint(const std::vector<float> &spectrum)
{
  std::vector<float> residue(spectrum.size());
  for (int i = 0; i < int(spectrum.size()); ++i) {
    float hull = std::numeric_limits<float>::infinity();
    // SciPy minimum_filter1d(size=10, mode="nearest") spans i-5 through i+4.
    for (int offset = -kHullArea / 2; offset < kHullArea / 2; ++offset)
      hull = std::min(hull, spectrum[std::clamp(i + offset, 0, int(spectrum.size()) - 1)]);
    residue[i] = std::clamp(spectrum[i] - std::max(hull, -45.0f), 0.0f, 5.0f);
  }
  const float peak = *std::max_element(residue.begin(), residue.end()) + 1e-6f;
  for (float &value : residue) value /= peak;
  return residue;
}

float score(const std::vector<float> &features, std::span<const float> weights)
{
  float dot = 0;
  for (int i = 0; i < kFeatures; ++i) dot += features[i] * weights[i + 1];
  return dot + weights[0];
}
} // namespace

struct SlopFingerprintDetector::Impl {
  explicit Impl(int channelCount, Resampler input, std::span<const float> model)
      : channels(channelCount), resampler(std::move(input)) {
    std::copy(model.begin(), model.end(), weights.begin());
  }
  std::array<float, kFeatures + 1> weights;
  int channels;
  Resampler resampler;
  Spectrum spectrum;
  std::vector<float> mono;
};

SlopFingerprintDetector::SlopFingerprintDetector(std::unique_ptr<Impl> impl)
    : m_impl(std::move(impl)) {}
SlopFingerprintDetector::~SlopFingerprintDetector() = default;

std::unique_ptr<SlopFingerprintDetector> SlopFingerprintDetector::create(int sampleRate, int channels,
                                                                      std::span<const float> weights)
{
  if (channels <= 0 || channels > 65'535 || sampleRate < 8'000 || sampleRate > 384'000 ||
      weights.size() != kFeatures + 1 ||
      !std::all_of(weights.begin(), weights.end(), [](float v) { return std::isfinite(v); })) return {};
  auto resampler = Resampler::create(sampleRate);
  if (!resampler) return {};
  return std::unique_ptr<SlopFingerprintDetector>(new SlopFingerprintDetector(
      std::make_unique<Impl>(channels, std::move(*resampler), weights)));
}

void SlopFingerprintDetector::push(const float *samples, size_t frames)
{
  if (!samples || frames == 0 || isFull()) return;
  m_impl->mono.clear();
  m_impl->mono.reserve(frames);
  for (size_t frame = 0; frame < frames; ++frame) {
    float sum = 0;
    for (int channel = 0; channel < m_impl->channels; ++channel)
      sum += samples[frame * size_t(m_impl->channels) + channel];
    const float value = sum / float(m_impl->channels);
    m_impl->mono.push_back(std::isfinite(value) ? value : 0.0f);
  }
  m_impl->resampler.push(m_impl->mono, m_impl->spectrum);
}

float SlopFingerprintDetector::seconds() const
{
  return float(m_impl->spectrum.length()) / kModelRate;
}

bool SlopFingerprintDetector::isFull() const
{
  return m_impl->spectrum.length() >= kMaxSamples;
}

std::optional<SlopVerdict> SlopFingerprintDetector::verdict() const
{
  if (seconds() < 10.0f) return std::nullopt;
  const float logit = score(fakeprint(m_impl->spectrum.meanDb()), m_impl->weights);
  return SlopVerdict{1.0f / (1.0f + std::exp(-logit)), logit, seconds()};
}

void SlopFingerprintDetector::reset()
{
  m_impl->resampler.reset();
  m_impl->spectrum.reset();
}
