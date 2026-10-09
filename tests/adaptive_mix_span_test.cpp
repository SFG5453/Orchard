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

#include "playback/adaptive_mix/adaptive_mix_span.h"
#include <QtTest>
#include <vector>

class AdaptiveMixSpanTest : public QObject {
  Q_OBJECT
private slots:
  void prerollAndUnevenBlocksPreserveTheIncomingHandoff() {
    // A 10 ms mix at the 1 s cue, decoded in blocks crossing both edges.
    auto first = adaptiveMixSpan(995000, 1000000, 400, 480, 0);
    QCOMPARE(first.preroll, 240);
    QCOMPARE(first.mixOffset, 0);
    QCOMPARE(first.mixFrames, 160);
    QCOMPARE(first.tailFrames, 0);
    auto next = adaptiveMixSpan(1003333, 1000000, 500, 480, 160);
    QCOMPARE(next.preroll, 0);
    QCOMPARE(next.mixFrames, 320);
    QCOMPARE(next.tailFrames, 180);
    QCOMPARE(first.mixFrames + next.mixFrames, 480);
  }
  void aLateSeekDoesNotReplayTheMissingPrefix() {
    const auto span = adaptiveMixSpan(1005000, 1000000, 400, 480, 0);
    QCOMPARE(span.mixOffset, 240);
    QCOMPARE(span.mixFrames, 240);
    QCOMPARE(span.tailFrames, 160);
  }
  void staleBuffersCannotRepeatAlreadyMixedFrames() {
    const auto stale = adaptiveMixSpan(1000000, 1000000, 120, 480, 240);
    QCOMPARE(stale.preroll, 120);
    QCOMPARE(stale.mixFrames, 0);
    QCOMPARE(stale.tailFrames, 0);
  }
  void matchingFindsTheSharedAudioNearTheNominalFrame() {
    std::vector<float> haystack(2 * 8000);
    quint32 seed = 3;
    for (auto &value : haystack) {
      seed = seed * 1664525u + 1013904223u;
      value = float(seed >> 8) / float(1 << 24) - 0.5f;
    }
    const float *needle = haystack.data() + (5000 - 512) * 2;
    QCOMPARE(adaptiveMatchingEnd(needle, haystack.data(), 8000, 512, 4700, 400),
             qint64(5000));
    // Out of reach, or silent, the nominal frame stands.
    QCOMPARE(adaptiveMatchingEnd(needle, haystack.data(), 8000, 512, 4000, 400),
             qint64(4000));
    const std::vector<float> silence(2 * 512, 0.0f);
    QCOMPARE(adaptiveMatchingEnd(silence.data(), haystack.data(), 8000, 512, 4700, 400),
             qint64(4700));
  }
  void unknownTimestampsContinueFromTheSampleClock() {
    const auto span = adaptiveMixSpan(-1, 1000000, 300, 480, 240);
    QCOMPARE(span.mixOffset, 240);
    QCOMPARE(span.mixFrames, 240);
    QCOMPARE(span.tailFrames, 60);
  }
};
QTEST_GUILESS_MAIN(AdaptiveMixSpanTest)
#include "adaptive_mix_span_test.moc"
