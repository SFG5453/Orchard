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

#include "playback/audio_pipeline.h"
#include "playback/output_health.h"

#include <QAudioSink>
#include <QtTest>

// Qt offers no way to fail a real sink on demand, so failures enter at sinkFailed().
class AudioPipelineSinkTest final : public QObject {
  Q_OBJECT
private slots:
  void failedSinkIsRebuiltWhilePlaying() {
    AudioPipeline pipeline;
    pipeline.setPlaying(true);
    const auto *dead = pipeline.m_sink.get();
    QVERIFY(dead);
    pipeline.m_sinkRetryTimer.stop();
    const int failures = OutputHealth::sinkFailures;
    pipeline.m_pending = QByteArray(64, 'a');
    pipeline.sinkFailed();
    QCOMPARE(pipeline.m_sinkDevice, nullptr);
    QCOMPARE(int(OutputHealth::sinkFailures), failures + 1);
    QVERIFY(pipeline.m_sinkRetryTimer.isActive());
    QTRY_VERIFY(pipeline.m_sink.get() != dead);
    QCOMPARE(pipeline.m_pending, QByteArray(64, 'a'));
  }

  void pausedSinkWaitsForPlayback() {
    AudioPipeline pipeline;
    pipeline.setPlaying(true);
    pipeline.setPlaying(false);
    pipeline.m_sinkRetryTimer.stop();
    const auto *dead = pipeline.m_sink.get();
    pipeline.sinkFailed();
    QVERIFY(!pipeline.m_sinkRetryTimer.isActive());
    QTest::qWait(AudioPipeline::kSinkRetryMinMs * 2);
    QCOMPARE(pipeline.m_sink.get(), dead);
    pipeline.setPlaying(true);
    QVERIFY(pipeline.m_sink.get() != dead);
  }

  void pausingCancelsAPendingRetry() {
    AudioPipeline pipeline;
    pipeline.setPlaying(true);
    pipeline.sinkFailed();
    QVERIFY(pipeline.m_sinkRetryTimer.isActive());
    pipeline.setPlaying(false);
    QVERIFY(!pipeline.m_sinkRetryTimer.isActive());
  }

  void retriesBackOffToACap() {
    AudioPipeline pipeline;
    pipeline.setPlaying(true);
    pipeline.m_sinkRetryMs = AudioPipeline::kSinkRetryMinMs;
    for (int attempt = 0; attempt < 8; ++attempt)
      pipeline.sinkFailed();
    QCOMPARE(pipeline.m_sinkRetryMs, AudioPipeline::kSinkRetryMaxMs);
    QCOMPARE(pipeline.m_sinkRetryTimer.interval(), AudioPipeline::kSinkRetryMaxMs);
  }

  void cleanStopIsNotAFailure() {
    AudioPipeline pipeline;
    pipeline.setPlaying(true);
    if (pipeline.m_sink->error() != QtAudio::NoError)
      QSKIP("No usable audio output on this machine");
    const auto *device = pipeline.m_sinkDevice;
    const int failures = OutputHealth::sinkFailures;
    pipeline.onSinkStateChanged(QtAudio::StoppedState);
    QCOMPARE(pipeline.m_sinkDevice, device);
    QCOMPARE(int(OutputHealth::sinkFailures), failures);
    QVERIFY(!pipeline.m_sinkRetryTimer.isActive());
  }
};

QTEST_MAIN(AudioPipelineSinkTest)
#include "audio_pipeline_sink_test.moc"
