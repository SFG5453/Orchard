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

#include "playback/audio_pipeline.h"
#include "playback/binaural_renderer.h"

#include <QTest>

namespace {

// Energy per ear after a 1 kHz tone of `frames` frames plays only on `position`.
QPair<double, double> earEnergy(QAudioFormat::AudioChannelPosition position, int frames = 4800) {
  const QAudioFormat input = BinauralRenderer::inputFormat();
  QAudioBuffer buffer(frames, input, 0);
  float *samples = buffer.data<float>();
  const int channel = input.channelOffset(position);
  for (int i = 0; i < frames; ++i)
    samples[i * input.channelCount() + channel] = float(std::sin(2 * M_PI * 1000 * i / 48000.0)) * 0.5f;
  BinauralRenderer renderer;
  const QAudioBuffer out = renderer.render(buffer, AudioPipeline::format());
  double left = 0, right = 0;
  const float *stereo = out.constData<float>();
  for (int i = 0; i < out.frameCount(); ++i) {
    left += stereo[2 * i] * stereo[2 * i];
    right += stereo[2 * i + 1] * stereo[2 * i + 1];
  }
  return {left, right};
}

} // namespace

class BinauralRendererTest final : public QObject {
  Q_OBJECT

private slots:
  void centerAndLfeReachBothEarsAlike() {
    for (const auto position : {QAudioFormat::FrontCenter, QAudioFormat::LFE}) {
      const auto [left, right] = earEnergy(position);
      QVERIFY(left > 0);
      QVERIFY(std::abs(left - right) < left * 1e-4);
    }
  }

  void sidesLeanTowardTheirEar() {
    const auto front = earEnergy(QAudioFormat::FrontLeft);
    QVERIFY(front.first > 2 * front.second);
    const auto surround = earEnergy(QAudioFormat::BackRight);
    QVERIFY(surround.second > 4 * surround.first);
  }

  void keepsTheTailAcrossBuffers() {
    const QAudioFormat input = BinauralRenderer::inputFormat();
    QAudioBuffer impulse(64, input, 0);
    impulse.data<float>()[63 * input.channelCount() + input.channelOffset(QAudioFormat::FrontCenter)] = 1.0f;
    BinauralRenderer renderer;
    renderer.render(impulse, AudioPipeline::format());
    const QAudioBuffer silence(256, input, 0);
    const QAudioBuffer tail = renderer.render(silence, AudioPipeline::format());
    float sum = 0;
    for (int i = 0; i < 2 * tail.frameCount(); ++i)
      sum += std::abs(tail.constData<float>()[i]);
    QVERIFY(sum > 0);
  }

  void refusesStereoInput() {
    BinauralRenderer renderer;
    QVERIFY(!renderer.render(QAudioBuffer(16, AudioPipeline::format(), 0), AudioPipeline::format()).isValid());
  }
};

QTEST_GUILESS_MAIN(BinauralRendererTest)
#include "binaural_renderer_test.moc"
