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

#include "playback/audio_engine_output.h"
#include "playback/audio_pipeline.h"
#include "playback/playback_controller.h"
#include "auth/auth_manager.h"
#include "home/home_controller.h"
#include "providers/youtube/catalog/youtube_catalog.h"
#include "providers/youtube/youtube_provider.h"
#include <QAudioBuffer>
#include <QAudioBufferOutput>
#include <QAudioSink>
#include <QMediaPlayer>
#include <QSettings>
#include <QTemporaryDir>
#include <QtTest>
#include <cmath>

class AdaptiveMixOutputTest : public QObject {
  Q_OBJECT
  QTemporaryDir m_settings;
  static QByteArray samples(qint64 frames, float left, float right) {
    QByteArray result(frames * 8, Qt::Uninitialized);
    auto *values = reinterpret_cast<float *>(result.data());
    for (qint64 i = 0; i < frames; ++i) {
      values[i * 2] = left;
      values[i * 2 + 1] = right;
    }
    return result;
  }
  // Deterministic noise: every stretch of it matches only itself.
  static QByteArray noise(qint64 frames, quint32 seed) {
    QByteArray result(frames * 8, Qt::Uninitialized);
    auto *values = reinterpret_cast<float *>(result.data());
    for (qint64 i = 0; i < frames * 2; ++i) {
      seed = seed * 1664525u + 1013904223u;
      values[i] = (float(seed >> 8) / float(1 << 24) - 0.5f) * 0.8f;
    }
    return result;
  }
  static QAudioBuffer block(const QByteArray &song, qint64 at, qint64 frames,
                            const QAudioFormat &format, qint64 originUs) {
    return QAudioBuffer(song.mid(at * 8, frames * 8), format,
                        originUs + qRound64(at * 1000000.0 / 48000));
  }
  // A flat engine and an unstarted sink, so every queued sample stays inspectable.
  // The sink stays unstarted, sparing the build machine a serenade.
  static void quiet(AudioPipeline &output, QMediaPlayer &current) {
    OrchardAudioEngineConfig bypass{};
    bypass.q = 1.1f;
    output.configure(bypass, {});
    output.setCurrentPlayer(&current);
    output.m_sink = std::make_unique<QAudioSink>(QAudioDevice(), output.m_format);
    output.m_adaptiveStartLeadUs = 0;
  }
  // Starts a 100 ms adaptive mix on the real audio thread and feeds the
  // incoming song the way Qt's decoder threads do.
  static void mixOnTheAudioThread(AudioEngineOutput &engine, QMediaPlayer &outgoing,
                                  QMediaPlayer &incoming) {
    engine.setCurrentPlayer(&outgoing);
    AudioPipeline *pipeline = engine.m_pipeline;
    engine.ask([pipeline] {
      pipeline->m_sink = std::make_unique<QAudioSink>(QAudioDevice(), pipeline->m_format);
      pipeline->m_adaptiveStartLeadUs = 0;
    });
    const QByteArray song = noise(48000, 17);
    QCOMPARE(engine.beginAdaptiveMix(&incoming, "incoming", song.left(4800 * 8), 1.0, 0.0), 1.0);
    auto *decoder = engine.m_bufferOutputs.value(&incoming);
    std::unique_ptr<QThread> feeder(QThread::create([decoder, song] {
      for (qint64 at = 0; at < 24000; at += 960)
        emit decoder->audioBufferReceived(block(song, at, 960, AudioPipeline::format(), 1000000));
    }));
    feeder->start();
    feeder->wait();
  }
  static bool mixing(AudioEngineOutput &engine) {
    bool active = true;
    engine.ask([&] { active = engine.m_pipeline->crossfadeActive(); });
    return active;
  }
private slots:
  void initTestCase() {
    QCoreApplication::setOrganizationName("OrchardTests");
    QCoreApplication::setApplicationName("AdaptiveMixOutput");
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, m_settings.path());
  }
  void volumeCurveAppliesImmediatelyWithoutChangingSliderValues() {
    QSettings().clear();
    YouTubeProvider provider;
    AuthManager auth(&provider);
    YouTubeCatalog catalog(&provider);
    HomeController home(&catalog, &auth);
    home.setVolume(0.5);
    PlaybackController playback(&provider, &auth, &home);
    auto &engine = *playback.audioEngine();
    double gain = 0.0;
    auto outputGain = [&] {
      engine.ask([&] { gain = engine.m_pipeline->m_masterVolume; });
      return gain;
    };
    QVERIFY(!playback.exponentialVolumeEnabled());
    QCOMPARE(outputGain(), 0.5);

    // An unstarted sink verifies gain updates without playing audio.
    engine.ask([&] {
      auto *pipeline = engine.m_pipeline;
      pipeline->m_sink = std::make_unique<QAudioSink>(QAudioDevice(), pipeline->m_format);
    });
    QSignalSpy changed(&playback, &PlaybackController::stateChanged);
    playback.setExponentialVolumeEnabled(true);
    QCOMPARE(changed.size(), 1);
    QCOMPARE(home.volume(), 0.5);
    QCOMPARE(outputGain(), 0.125);
    engine.ask([&] { gain = engine.m_pipeline->m_sink->volume(); });
    QCOMPARE(gain, 0.125);
    playback.setExponentialVolumeEnabled(true);
    QCOMPARE(changed.size(), 1);

    const QList<QPair<double, double>> levels{
        {0.0, 0.0}, {0.05, 0.000125}, {0.1, 0.001},
        {0.2, 0.008}, {0.5, 0.125}, {1.0, 1.0}};
    for (const auto &[slider, expected] : levels) {
      home.setVolume(slider);
      QVERIFY(std::abs(outputGain() - expected) < 1e-12);
      QCOMPARE(home.volume(), slider);
    }
    home.setVolume(0.5);
    engine.setEnabled(false);
    QCOMPARE(outputGain(), 0.125);
    playback.setExponentialVolumeEnabled(false);
    QCOMPARE(outputGain(), 0.5);
    QCOMPARE(home.volume(), 0.5);
    QSettings().clear();
  }
  void volumeCurveRestoresBeforeTheFirstOutput() {
    QSettings().clear();
    YouTubeProvider provider;
    AuthManager auth(&provider);
    YouTubeCatalog catalog(&provider);
    {
      HomeController home(&catalog, &auth);
      PlaybackController playback(&provider, &auth, &home);
      home.setVolume(0.2);
      playback.setExponentialVolumeEnabled(true);
    }
    HomeController home(&catalog, &auth);
    PlaybackController playback(&provider, &auth, &home);
    QVERIFY(playback.exponentialVolumeEnabled());
    QCOMPARE(home.volume(), 0.2);
    auto &engine = *playback.audioEngine();
    double gain = 0.0;
    engine.ask([&] { gain = engine.m_pipeline->m_masterVolume; });
    QVERIFY(std::abs(gain - 0.008) < 1e-12);
    playback.setExponentialVolumeEnabled(false);
    QCOMPARE(QSettings().value("playback/exponentialVolumeEnabled").toBool(), false);
    QSettings().clear();
  }
  void renderedStereoAndNativeTailReachOutputWithDspDisabled() {
    QMediaPlayer outgoing, incoming;
    AudioPipeline output;
    quiet(output, outgoing);
    const auto rendered = samples(480, 0.1f, 0.2f);
    QCOMPARE(output.beginAdaptiveMix(&incoming, "incoming", rendered, 1.0, 0.0), 1.0);
    QSignalSpy finished(&output, &AudioPipeline::crossfadeFinished);
    output.processBuffer(&incoming, QAudioBuffer(samples(400, 0.3f, 0.4f), output.m_format, 995000));
    QCOMPARE(output.m_crossfadeFramesMixed, 160);
    output.processBuffer(&incoming, QAudioBuffer(samples(500, 0.3f, 0.4f), output.m_format, 1003333));
    QCOMPARE(finished.size(), 1);
    QCOMPARE(output.m_pending.size(), (480 + 180) * 8);
    const auto *actual = reinterpret_cast<const float *>(output.m_pending.constData());
    for (int i = 0; i < 660; ++i) {
      // The render's last 256 frames fade into the native tail.
      const float weight = i < 224 ? 0.0f : i < 480 ? float(i - 223) / 257.0f : 1.0f;
      QVERIFY(std::abs(actual[i * 2] - (0.1f + 0.2f * weight)) < 1e-5f);
      QVERIFY(std::abs(actual[i * 2 + 1] - (0.2f + 0.2f * weight)) < 1e-5f);
    }
  }
  void theRenderResumesWhereQueuedOutgoingAudioEnded() {
    QMediaPlayer outgoing, incoming;
    AudioPipeline output;
    quiet(output, outgoing);
    // The render opens at 0.5 s. This decoder stamps its buffers 7 ms early,
    // as Qt does for Opus played from the start.
    const QByteArray song = noise(48000, 7);
    const qint64 opening = 24000, played = opening + 4800;
    for (qint64 at = 0; at < played; at += 960)
      output.processBuffer(&outgoing, block(song, at, 960, output.m_format, -7000));
    const double cue = output.beginAdaptiveMix(&incoming, "incoming",
                                               song.mid(opening * 8), 1.0, 0.5);
    // Native playback reached song frame 28800: 4800 frames into the render.
    QCOMPARE(output.m_crossfadeFramesTotal, qint64(48000 - played));
    QVERIFY(std::abs(cue - (1.0 + 4800 / 48000.0)) < 1e-9);
  }
  void theHandoffContinuesFromTheMatchingIncomingFrame() {
    QMediaPlayer outgoing, incoming;
    AudioPipeline output;
    quiet(output, outgoing);
    // The render runs 24 frames ahead of the incoming timestamps, as Opus
    // does after a seek.
    const QByteArray song = noise(96000, 11);
    const qint64 length = 12000, shift = 24;
    QCOMPARE(output.beginAdaptiveMix(&incoming, "incoming",
                                     song.mid(shift * 8, length * 8), 1.0, 0.0),
             1.0);
    QSignalSpy finished(&output, &AudioPipeline::crossfadeFinished);
    for (qint64 at = 0; finished.isEmpty() && at < 96000; at += 960)
      output.processBuffer(&incoming, block(song, at, 960, output.m_format, 1000000));
    QCOMPARE(finished.size(), 1);
    // The render's last frame is song frame 12023, so 12024 follows it.
    const auto *actual = reinterpret_cast<const float *>(output.m_pending.constData());
    const auto *expected = reinterpret_cast<const float *>(song.constData());
    for (qint64 i = 0; i < 64; ++i)
      QVERIFY(std::abs(actual[(length + i) * 2] - expected[(length + shift + i) * 2]) < 1e-6f);
  }
  void theOutgoingClockPacesTheRenderUntilTheIncomingPlayerStarts() {
    QMediaPlayer outgoing, incoming;
    AudioPipeline output;
    quiet(output, outgoing);
    output.m_adaptiveStartLeadUs = 20000;
    const QByteArray rendered = noise(48000, 5);
    QVERIFY(std::abs(output.beginAdaptiveMix(&incoming, "incoming", rendered, 1.0, 0.0) -
                     1.02) < 1e-9);
    // The incoming player takes its time; the outgoing buffers keep the render moving.
    for (int i = 0; i < 2; ++i)
      output.processBuffer(&outgoing, QAudioBuffer(noise(960, 9), output.m_format, 5000000));
    QCOMPARE(output.m_pending.size(), 1920 * 8);
    // It arrives 10 ms ahead of the render: the render catches up without skipping.
    output.processBuffer(&incoming,
                         block(rendered, 2400, 960, output.m_format, 1000000));
    QCOMPARE(output.m_crossfadeFramesMixed, qint64(3360));
    QCOMPARE(output.m_pending, rendered.left(3360 * 8));
    QCOMPARE(output.m_adaptiveStartLeadUs, qint64(15000));
    // From here the incoming clock leads; stray outgoing buffers are ignored.
    output.processBuffer(&outgoing, QAudioBuffer(noise(960, 9), output.m_format, 5020000));
    QCOMPARE(output.m_crossfadeFramesMixed, qint64(3360));
  }
  void lyricsFollowTheRenderedContentAfterTheHandoff() {
    QMediaPlayer outgoing, incoming;
    AudioPipeline output;
    quiet(output, outgoing);
    // Same 24-frame lead as above: after the join, decoder stamps run 0.5 ms
    // ahead of the audio, and karaoke with a head start is still cheating.
    const QByteArray song = noise(96000, 13);
    const qint64 length = 12000, shift = 24;
    output.beginAdaptiveMix(&incoming, "incoming", song.mid(shift * 8, length * 8),
                            1.0, 0.0);
    QSignalSpy finished(&output, &AudioPipeline::crossfadeFinished);
    qint64 at = 0;
    for (; finished.isEmpty() && at < 96000; at += 960)
      output.processBuffer(&incoming, block(song, at, 960, output.m_format, 1000000));
    QCOMPARE(output.m_clockCorrectionUs, qint64(-500));
    output.processBuffer(&incoming, block(song, at, 960, output.m_format, 1000000));
    // Nothing has played yet, so the speakers still sit on the render's first frame.
    QVERIFY(std::abs(output.audiblePosition() - 1.0) < 1e-6);
  }
  void aMixFinishesWhileTheUiThreadIsBusy() {
    QMediaPlayer outgoing, incoming;
    AudioEngineOutput engine;
    QSignalSpy finished(&engine, &AudioEngineOutput::crossfadeFinished);
    mixOnTheAudioThread(engine, outgoing, incoming);
    // This thread plays the stalled UI: no events, just a nap.
    QThread::msleep(300);
    QVERIFY(!mixing(engine));
    QCOMPARE(finished.size(), 0);
    QTRY_COMPARE(finished.size(), 1);
  }
  void aCancelThatArrivesAfterTheMixFinishedCommitsTheSwitch() {
    QMediaPlayer outgoing, incoming;
    AudioEngineOutput engine;
    QSignalSpy finished(&engine, &AudioEngineOutput::crossfadeFinished);
    mixOnTheAudioThread(engine, outgoing, incoming);
    QThread::msleep(300);
    QVERIFY(!mixing(engine));
    // The new song is already playing, so the caller finishes the switch itself
    // and the queued finish is stale.
    QVERIFY(!engine.cancelCrossfade());
    QTest::qWait(100);
    QCOMPARE(finished.size(), 0);
  }
  void aCancelDuringTheMixStopsIt() {
    QMediaPlayer outgoing, incoming;
    AudioPipeline output;
    quiet(output, outgoing);
    QCOMPARE(output.beginAdaptiveMix(&incoming, "incoming", samples(4800, 0.1f, 0.2f), 1.0, 0.0), 1.0);
    QVERIFY(output.cancelCrossfade());
    QVERIFY(!output.crossfadeActive());
  }
};
QTEST_GUILESS_MAIN(AdaptiveMixOutputTest)
#include "adaptive_mix_output_test.moc"
