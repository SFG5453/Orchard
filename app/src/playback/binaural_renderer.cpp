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

#include "binaural_renderer.h"
#include "binaural_hrir.h"

namespace {

using namespace binaural_hrir;

// ITU-R BS.775 downmix weights for the center and surround channels; LFE sits 6 dB down.
constexpr float kCenterWeight = 0.7071f;
constexpr float kSurroundWeight = 0.7071f;
constexpr float kLfeWeight = 0.5f;

// The pair filters split into mid and side, so a symmetric pair costs two convolutions.
struct Filters {
  std::array<float, kTaps> frontMid{}, frontSide{}, surroundMid{}, surroundSide{}, center{};
  Filters() {
    for (int i = 0; i < kTaps; ++i) {
      frontMid[i] = (kFrontIpsi[i] + kFrontContra[i]) / 2;
      frontSide[i] = (kFrontIpsi[i] - kFrontContra[i]) / 2;
      surroundMid[i] = kSurroundWeight * (kSurroundIpsi[i] + kSurroundContra[i]) / 2;
      surroundSide[i] = kSurroundWeight * (kSurroundIpsi[i] - kSurroundContra[i]) / 2;
      center[i] = kCenterWeight * kCenter[i];
    }
  }
};

const Filters &filters() {
  static const Filters built;
  return built;
}

// `signal` holds kTaps - 1 frames of history before the frame at `at`.
float convolve(const float *signal, const std::array<float, kTaps> &taps, qsizetype at) {
  float sum = 0.0f;
  const float *newest = signal + at + kTaps - 1;
  for (int i = 0; i < kTaps; ++i)
    sum += taps[i] * newest[-i];
  return sum;
}

} // namespace

QAudioFormat BinauralRenderer::inputFormat() {
  QAudioFormat format;
  format.setSampleRate(48000);
  format.setChannelConfig(QAudioFormat::ChannelConfigSurround5Dot1);
  format.setSampleFormat(QAudioFormat::Float);
  return format;
}

void BinauralRenderer::reset() {
  for (auto &history : m_history)
    history.clear();
}

QAudioBuffer BinauralRenderer::render(const QAudioBuffer &input, const QAudioFormat &output) {
  const QAudioFormat format = input.format();
  if (format.sampleFormat() != QAudioFormat::Float || format.channelConfig() != QAudioFormat::ChannelConfigSurround5Dot1 ||
      format.sampleRate() != output.sampleRate() || output.channelCount() != 2)
    return {};
  const qsizetype frames = input.frameCount();
  const int channels = format.channelCount();
  const auto offset = [&format](QAudioFormat::AudioChannelPosition position) { return format.channelOffset(position); };
  const int fl = offset(QAudioFormat::FrontLeft), fr = offset(QAudioFormat::FrontRight);
  const int c = offset(QAudioFormat::FrontCenter), lfe = offset(QAudioFormat::LFE);
  const int sl = offset(QAudioFormat::BackLeft), sr = offset(QAudioFormat::BackRight);
  if (fl < 0 || fr < 0 || c < 0 || lfe < 0 || sl < 0 || sr < 0)
    return {};

  // Each signal is the previous tail followed by this buffer's frames.
  for (auto &history : m_history) {
    if (history.size() < size_t(kTaps - 1))
      history.assign(kTaps - 1, 0.0f);
    history.resize(kTaps - 1 + frames);
  }
  const float *in = input.constData<float>();
  for (qsizetype i = 0; i < frames; ++i) {
    const float *frame = in + i * channels;
    const qsizetype at = kTaps - 1 + i;
    m_history[0][at] = frame[fl] + frame[fr];
    m_history[1][at] = frame[fl] - frame[fr];
    m_history[2][at] = frame[sl] + frame[sr];
    m_history[3][at] = frame[sl] - frame[sr];
    m_history[4][at] = frame[c];
  }

  QAudioBuffer rendered(int(frames), output, input.startTime());
  float *out = rendered.data<float>();
  const Filters &f = filters();
  for (qsizetype i = 0; i < frames; ++i) {
    const float mid = convolve(m_history[0].data(), f.frontMid, i) + convolve(m_history[2].data(), f.surroundMid, i) +
                      convolve(m_history[4].data(), f.center, i) + kLfeWeight * in[i * channels + lfe];
    const float side = convolve(m_history[1].data(), f.frontSide, i) + convolve(m_history[3].data(), f.surroundSide, i);
    out[2 * i] = mid + side;
    out[2 * i + 1] = mid - side;
  }
  for (auto &history : m_history)
    history.erase(history.begin(), history.end() - (kTaps - 1));
  return rendered;
}
