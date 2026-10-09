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

#include "audio_pipeline.h"

#include <QAudioBuffer>
#include <QAudioSink>
#include <QDebug>
#include <QIODevice>

#include <algorithm>
#include <cmath>
#include <utility>

AudioPipeline::AudioPipeline(QObject *parent) : QObject(parent), m_format(format()) {
  m_engine = orchard_audio_engine_create(kSampleRate, kChannels);
  m_timing = qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING");
  m_timingClock.start();
  m_flushTimer.setInterval(5);
  connect(&m_flushTimer, &QTimer::timeout, this, &AudioPipeline::flushPending);
  // The pretty bars get their own fast refresh; Auto EQ keeps its slower
  // analysis window so it doesn't develop caffeinated opinions about every kick.
  m_meterTimer.setInterval(50);
  connect(&m_meterTimer, &QTimer::timeout, this, [this] {
    if (!m_engine)
      return;
    QList<float> autoGains(10), spectrum(10);
    orchard_audio_engine_auto_gains(m_engine, autoGains.data());
    orchard_audio_engine_spectrum(m_engine, spectrum.data());
    emit metersChanged(autoGains, spectrum);
  });
}

AudioPipeline::~AudioPipeline() {
  if (m_sink)
    m_sink->stop();
  orchard_audio_engine_destroy(m_crossfadeEngine);
  orchard_audio_engine_destroy(m_engine);
}

QAudioFormat AudioPipeline::format() {
  QAudioFormat format;
  format.setSampleRate(kSampleRate);
  format.setChannelConfig(QAudioFormat::ChannelConfigStereo);
  format.setSampleFormat(QAudioFormat::Float);
  return format;
}

void AudioPipeline::start() {
  m_flushTimer.start();
  m_meterTimer.start();
}

OrchardAudioEngineConfig AudioPipeline::trackConfiguration(const QString &trackId) const {
  OrchardAudioEngineConfig config = m_config;
  const double gain = m_trackGains.value(trackId, 0.0).toDouble();
  config.track_gain_db = std::isfinite(gain) ? float(std::clamp(gain, -12.0, 12.0)) : 0.0f;
  return config;
}

void AudioPipeline::applyConfiguration() {
  if (m_engine) {
    const auto config = trackConfiguration(m_activeTrackId);
    orchard_audio_engine_configure(m_engine, &config);
  }
  if (m_crossfadeEngine) {
    const auto config = trackConfiguration(m_crossfadeTrackId);
    orchard_audio_engine_configure(m_crossfadeEngine, &config);
  }
}

void AudioPipeline::configure(const OrchardAudioEngineConfig &base, const QVariantMap &trackGains) {
  m_config = base;
  m_trackGains = trackGains;
  applyConfiguration();
}

void AudioPipeline::setCurrentPlayer(const QMediaPlayer *player) {
  if (m_currentPlayer != player)
    forgetOutgoing();
  m_currentPlayer = player;
}

void AudioPipeline::setActiveTrackId(const QString &trackId) {
  if (m_activeTrackId == trackId)
    return;
  m_activeTrackId = trackId;
  applyConfiguration();
}

void AudioPipeline::setMasterVolume(double volume) {
  m_masterVolume = volume;
  if (m_sink)
    m_sink->setVolume(m_masterVolume);
}

void AudioPipeline::setPlaying(bool playing) {
  if (playing)
    ensureSink();
  if (!m_sink)
    return;
  const auto state = m_sink->state();
  if (playing) {
    if (state == QtAudio::SuspendedState)
      m_sink->resume();
  } else if (state == QtAudio::ActiveState || state == QtAudio::IdleState) {
    m_sink->suspend();
  }
}

void AudioPipeline::setDevice(const QAudioDevice &device) {
  m_device = device;
  if (m_sink)
    rebuildSink();
}

void AudioPipeline::ensureSink() {
  if (!m_sink)
    rebuildSink();
}

void AudioPipeline::rebuildSink() {
  if (m_sink)
    m_sink->stop();
  m_pending.clear();
  // A null device makes Qt pick the system default.
  m_sink = std::make_unique<QAudioSink>(m_device, m_format);
  if (m_timing)
    connect(m_sink.get(), &QAudioSink::stateChanged, this, [this](QtAudio::State state) {
      // Idle while pushing means the sink ran dry.
      if (state == QtAudio::IdleState && m_transitionMs >= 0)
        qInfo() << "Playback: output underrun"
                << m_timingClock.elapsed() - m_transitionMs
                << (m_crossfadeActive ? "ms after mix start" : "ms after handoff");
    });
  m_sink->setBufferSize(m_format.bytesForFrames(kSinkFrames));
  m_primed = false;
  m_sink->setVolume(m_masterVolume);
  m_sinkDevice = m_sink->start();
}

void AudioPipeline::flush() {
  resetCrossfade();
  forgetOutgoing();
  m_clockCorrectionUs = 0;
  m_pending.clear();
  m_primed = false;
  if (m_sink) {
    m_sink->reset();
    m_sinkDevice = m_sink->start();
    m_sink->setVolume(m_masterVolume);
  }
  orchard_audio_engine_reset(m_engine);
}

bool AudioPipeline::beginCrossfade(const QMediaPlayer *incomingPlayer,
                                   const QString &incomingTrackId,
                                   double durationSeconds) {
  if (!m_engine || !m_currentPlayer || !incomingPlayer ||
      incomingPlayer == m_currentPlayer || !std::isfinite(durationSeconds))
    return false;
  const qint64 totalFrames = static_cast<qint64>(std::llround(
      std::clamp(durationSeconds, 0.05, 12.0) * kSampleRate));
  if (totalFrames <= 0)
    return false;

  resetCrossfade();
  m_crossfadeEngine = orchard_audio_engine_create(kSampleRate, kChannels);
  if (!m_crossfadeEngine)
    return false;
  m_crossfadePlayer = incomingPlayer;
  m_crossfadeTrackId = incomingTrackId;
  const auto config = trackConfiguration(m_crossfadeTrackId);
  orchard_audio_engine_configure(m_crossfadeEngine, &config);
  m_crossfadeFramesMixed = 0;
  m_crossfadeFramesTotal = totalFrames;
  m_crossfadeOutgoingPending.clear();
  m_crossfadeIncomingPending.clear();
  m_crossfadeActive = true;
  ++m_mixSerial;
  m_transitionMs = m_timingClock.elapsed();
  m_awaitingIncoming = m_timing;
  emit crossfadeProgressChanged(m_mixSerial, 0.0);
  return true;
}

void AudioPipeline::resetCrossfade() {
  m_adaptivePcm.clear();
  m_handoffCorrectionUs = 0;
  m_handoffRaw.clear();
  m_handoffProcessed.clear();
  m_handoffRender.clear();
  if (m_crossfadeEngine) {
    orchard_audio_engine_destroy(m_crossfadeEngine);
    m_crossfadeEngine = nullptr;
  }
  m_crossfadePlayer = nullptr;
  m_crossfadeTrackId.clear();
  m_crossfadeFramesMixed = 0;
  m_crossfadeFramesTotal = 0;
  m_crossfadeOutgoingPending.clear();
  m_crossfadeIncomingPending.clear();
  m_crossfadeActive = false;
}

bool AudioPipeline::cancelCrossfade() {
  if (!m_crossfadeActive)
    return m_finishedSerial != m_mixSerial;
  resetCrossfade();
  return true;
}

void AudioPipeline::completeCrossfade() {
  if (!m_crossfadeActive || !m_crossfadeEngine || !m_crossfadePlayer)
    return;
  m_adaptivePcm.clear();
  m_handoffRaw.clear();
  m_handoffProcessed.clear();
  m_handoffRender.clear();
  forgetOutgoing();
  m_clockCorrectionUs = std::exchange(m_handoffCorrectionUs, 0);
  m_pending.append(m_crossfadeIncomingPending);
  m_crossfadeIncomingPending.clear();
  m_crossfadeOutgoingPending.clear();
  orchard_audio_engine_destroy(m_engine);
  m_engine = m_crossfadeEngine;
  m_crossfadeEngine = nullptr;
  m_currentPlayer = m_crossfadePlayer;
  m_activeTrackId = m_crossfadeTrackId;
  m_crossfadePlayer = nullptr;
  m_crossfadeTrackId.clear();
  m_crossfadeFramesMixed = 0;
  m_crossfadeFramesTotal = 0;
  m_crossfadeActive = false;
  m_finishedSerial = m_mixSerial;
  m_transitionMs = m_timingClock.elapsed();
  // Hand the continuation to the sink before the track switch runs.
  flushPending();
  emit crossfadeProgressChanged(m_mixSerial, 1.0);
  emit crossfadeFinished(m_mixSerial);
}

void AudioPipeline::processBuffer(const QMediaPlayer *source, const QAudioBuffer &buffer) {
  const bool outgoing = source == m_currentPlayer;
  const bool incoming = m_crossfadeActive && source == m_crossfadePlayer;
  if ((!outgoing && !incoming) || !buffer.isValid() || buffer.format() != m_format)
    return;
  emit decodedAudio(outgoing ? m_activeTrackId : m_crossfadeTrackId, buffer);
  if (m_timing) {
    // A gap longer than the cushion starves the sink.
    const qint64 now = m_timingClock.elapsed();
    if (m_lastBufferMs >= 0 && now - m_lastBufferMs > 60 &&
        (m_crossfadeActive || (m_transitionMs >= 0 && now - m_transitionMs < 5000)))
      qInfo() << "Playback: audio buffers stalled" << now - m_lastBufferMs << "ms,"
              << (m_crossfadeActive ? -1 : now - m_transitionMs) << "ms after handoff";
    m_lastBufferMs = now;
    if (incoming && m_awaitingIncoming) {
      m_awaitingIncoming = false;
      qInfo() << "Playback: first incoming buffer" << now - m_transitionMs
              << "ms after mix start, queued"
              << (m_pending.size() / kFrameBytes) * 1000.0 / kSampleRate << "ms";
    }
  }
  if (m_crossfadeActive && !m_adaptivePcm.isEmpty()) {
    processAdaptiveBuffer(source, buffer);
    return;
  }
  OrchardAudioEngineHandle *engine = outgoing ? m_engine : m_crossfadeEngine;
  if (!engine)
    return;
  ensureSink();
  QByteArray bytes(reinterpret_cast<const char *>(buffer.constData<float>()),
                   buffer.byteCount());
  auto *samples = reinterpret_cast<float *>(bytes.data());
  if (!orchard_audio_engine_process(engine, samples,
                                    static_cast<size_t>(buffer.frameCount())))
    return;
  if (m_crossfadeActive) {
    if (outgoing)
      m_crossfadeOutgoingPending.append(bytes);
    else
      m_crossfadeIncomingPending.append(bytes);
    mixCrossfadePending();
  } else {
    m_pending.append(bytes);
    rememberOutgoing(buffer);
  }
  const qsizetype maximumQueued = m_format.bytesForFrames(kSampleRate);
  if (m_pending.size() > maximumQueued) {
    m_pending.remove(0, m_pending.size() - maximumQueued);
  }
  flushPending();
}

void AudioPipeline::mixCrossfadePending() {
  if (!m_crossfadeActive || !m_crossfadeEngine || m_crossfadeFramesTotal <= 0)
    return;
  const qint64 availableFrames = std::min(
      static_cast<qint64>(m_crossfadeOutgoingPending.size() / kFrameBytes),
      static_cast<qint64>(m_crossfadeIncomingPending.size() / kFrameBytes));
  const qint64 framesLeft = m_crossfadeFramesTotal - m_crossfadeFramesMixed;
  const qint64 frames = std::min(availableFrames, framesLeft);
  if (frames <= 0)
    return;

  QByteArray mixed(static_cast<qsizetype>(frames * kFrameBytes), Qt::Uninitialized);
  const auto *out = reinterpret_cast<const float *>(m_crossfadeOutgoingPending.constData());
  const auto *in = reinterpret_cast<const float *>(m_crossfadeIncomingPending.constData());
  auto *dst = reinterpret_cast<float *>(mixed.data());
  constexpr double halfPi = 1.57079632679489661923;
  for (qint64 frame = 0; frame < frames; ++frame) {
    const double progress = std::clamp(
        static_cast<double>(m_crossfadeFramesMixed + frame + 1) /
            static_cast<double>(m_crossfadeFramesTotal),
        0.0, 1.0);
    const float outgoingGain = static_cast<float>(std::cos(progress * halfPi));
    const float incomingGain = static_cast<float>(std::sin(progress * halfPi));
    for (int channel = 0; channel < kChannels; ++channel) {
      const qint64 sample = frame * kChannels + channel;
      dst[sample] = out[sample] * outgoingGain + in[sample] * incomingGain;
    }
  }

  const qsizetype consumed = static_cast<qsizetype>(frames * kFrameBytes);
  m_crossfadeOutgoingPending.remove(0, consumed);
  m_crossfadeIncomingPending.remove(0, consumed);
  m_crossfadeFramesMixed += frames;
  m_pending.append(mixed);
  emit crossfadeProgressChanged(
      m_mixSerial, std::clamp(static_cast<double>(m_crossfadeFramesMixed) /
                                  static_cast<double>(m_crossfadeFramesTotal),
                              0.0, 1.0));

  if (m_crossfadeFramesMixed < m_crossfadeFramesTotal)
    return;

  completeCrossfade();
}

double AudioPipeline::audiblePosition() const {
  if (m_crossfadeActive || m_outgoingEndUs < 0)
    return -1.0;
  // Stalls and handoff waits leave audio queued; count it so lyrics follow the speakers.
  qint64 queued = m_pending.size();
  if (m_sink && m_sinkDevice)
    queued += std::max<qint64>(0, m_sink->bufferSize() - m_sink->bytesFree());
  return std::max(0.0, (m_outgoingEndUs + m_clockCorrectionUs) / 1000000.0 -
                           double(queued / kFrameBytes) / kSampleRate);
}

void AudioPipeline::flushPending() {
  if (!m_sink || !m_sinkDevice || m_pending.isEmpty())
    return;
  if (!m_primed) {
    if (m_pending.size() < kPrimeFrames * kFrameBytes)
      return;
    m_primed = true;
  }
  while (!m_pending.isEmpty()) {
    const qsizetype free = m_sink->bytesFree();
    if (free <= 0)
      break;
    const qsizetype amount = std::min(free, m_pending.size());
    const qint64 written = m_sinkDevice->write(m_pending.constData(), amount);
    if (written <= 0)
      break;
    m_pending.remove(0, written);
  }
}
