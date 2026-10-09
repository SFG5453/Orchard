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

#include "playback/slop_fingerprint.h"

#include <QtTest>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {
// The Rust port's seeded fixture: no platform RNG can improvise a new solo.
std::vector<float> noise(int seconds, int rate, int channels, uint64_t seed)
{
  std::vector<float> pcm;
  pcm.reserve(size_t(seconds) * rate * channels);
  for (int i = 0; i < seconds * rate; ++i) {
    seed ^= seed << 13;
    seed ^= seed >> 7;
    seed ^= seed << 17;
    const float sample = float(double(seed) / double(UINT64_MAX) * 2.0 - 1.0) * 0.3f;
    for (int channel = 0; channel < channels; ++channel) pcm.push_back(sample);
  }
  return pcm;
}
} // namespace

class SlopFingerprintTest final : public QObject {
  Q_OBJECT
private slots:
  void validatesFormatsAndLength()
  {
    QVERIFY(!SlopFingerprintDetector::create(48'000, 0));
    QVERIFY(!SlopFingerprintDetector::create(1'000, 2));
    QVERIFY(!SlopFingerprintDetector::create(44'101, 2));
    auto detector = SlopFingerprintDetector::create(48'000, 1);
    QVERIFY(detector);
    const auto pcm = noise(5, 48'000, 1, 1);
    detector->push(pcm.data(), pcm.size());
    QVERIFY(!detector->verdict());
  }

  void matchesRustReference()
  {
    struct Fixture { int seconds, rate, channels; uint64_t seed; float logit, duration; };
    const Fixture fixtures[] = {
      {12, 16'000, 1, 5, -10.308710098f, 12.0f},
      {20, 48'000, 2, 3, -17.466621399f, 19.999563f},
      {30, 44'100, 1, 7, -13.856967926f, 29.99f},
    };
    for (const Fixture &fixture : fixtures) {
      auto detector = SlopFingerprintDetector::create(fixture.rate, fixture.channels);
      QVERIFY(detector);
      const auto pcm = noise(fixture.seconds, fixture.rate, fixture.channels, fixture.seed);
      detector->push(pcm.data(), pcm.size() / fixture.channels);
      const auto verdict = detector->verdict();
      QVERIFY(verdict);
      // FFT butterfly order differs from realfft, so allow a small float-rounding gap.
      QVERIFY2(std::abs(verdict->logit - fixture.logit) < 0.05f,
               qPrintable(QStringLiteral("logit: %1 vs %2")
                              .arg(verdict->logit, 0, 'f', 8).arg(fixture.logit, 0, 'f', 8)));
      QVERIFY(std::abs(verdict->seconds - fixture.duration) < 0.0001f);
      QVERIFY(verdict->probability < 0.1f);
    }
  }

  void chunkingAndReset()
  {
    const auto pcm = noise(12, 16'000, 1, 5);
    auto whole = SlopFingerprintDetector::create(16'000, 1);
    auto streamed = SlopFingerprintDetector::create(16'000, 1);
    QVERIFY(whole && streamed);
    whole->push(pcm.data(), pcm.size());
    for (size_t i = 0; i < pcm.size(); i += 1023)
      streamed->push(pcm.data() + i, std::min<size_t>(1023, pcm.size() - i));
    QVERIFY(whole->verdict() && streamed->verdict());
    QVERIFY(std::abs(whole->verdict()->logit - streamed->verdict()->logit) < 0.0001f);
    streamed->reset();
    QCOMPARE(streamed->seconds(), 0.0f);
    streamed->push(pcm.data(), pcm.size());
    QVERIFY(streamed->verdict());
    QCOMPARE(whole->verdict()->logit, streamed->verdict()->logit);
  }
};

QTEST_GUILESS_MAIN(SlopFingerprintTest)
#include "slop_fingerprint_test.moc"
