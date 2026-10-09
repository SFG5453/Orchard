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

#include "audio_engine_output.h"
#include "audio_pipeline.h"

#include <QAudioBuffer>
#include <QAudioBufferOutput>
#include <QAudioDevice>
#include <QMediaPlayer>
#include <QSettings>

#include <algorithm>
#include <cmath>

namespace {
double finiteClamp(double value, double minimum, double maximum, double fallback) {
  return std::isfinite(value) ? std::clamp(value, minimum, maximum) : fallback;
}
} // namespace

void AudioEngineOutput::post(std::function<void()> work) const {
  if (m_audioThread.isRunning())
    QMetaObject::invokeMethod(m_pipeline, std::move(work), Qt::QueuedConnection);
  else
    work();
}

void AudioEngineOutput::ask(const std::function<void()> &work) const {
  if (m_audioThread.isRunning())
    QMetaObject::invokeMethod(m_pipeline, work, Qt::BlockingQueuedConnection);
  else
    work();
}

AudioEngineOutput::AudioEngineOutput(QObject *parent) : QObject(parent) {
  loadSettings();
  m_pipeline = new AudioPipeline;
  m_pipeline->moveToThread(&m_audioThread);
  connect(&m_audioThread, &QThread::finished, m_pipeline, &QObject::deleteLater);
  connect(m_pipeline, &AudioPipeline::crossfadeProgressChanged, this,
          [this](quint64 serial, double progress) {
            if (serial == m_mixSerial)
              emit crossfadeProgressChanged(progress);
          });
  connect(m_pipeline, &AudioPipeline::crossfadeFinished, this, [this](quint64 serial) {
    // A finish queued behind a cancel belongs to a mix the controller already dropped.
    if (serial != m_mixSerial)
      return;
    m_mixSerial = 0;
    m_adaptive = false;
    emit crossfadeFinished();
  });
  connect(m_pipeline, &AudioPipeline::decodedAudio, this, &AudioEngineOutput::decodedAudio);
  connect(m_pipeline, &AudioPipeline::metersChanged, this,
          [this](const QList<float> &autoGains, const QList<float> &spectrum) {
            std::copy_n(autoGains.cbegin(), std::min<qsizetype>(10, autoGains.size()), m_autoGains);
            std::copy_n(spectrum.cbegin(), std::min<qsizetype>(10, spectrum.size()), m_spectrum);
            emit metersChanged();
          });
  m_audioThread.setObjectName(QStringLiteral("OrchardAudio"));
  m_audioThread.start();
  post([pipeline = m_pipeline] { pipeline->start(); });
  applyConfiguration();
}

AudioEngineOutput::~AudioEngineOutput() {
  for (auto player = m_bufferOutputs.cbegin(); player != m_bufferOutputs.cend(); ++player)
    player.key()->setAudioBufferOutput(nullptr);
  // The pipeline stops its sink and is deleted on its own thread as the loop ends.
  m_audioThread.quit();
  m_audioThread.wait();
}

QVariantList AudioEngineOutput::gains() const {
  QVariantList values;
  values.reserve(10);
  for (float gain : m_gains)
    values.append(gain);
  return values;
}

QVariantList AudioEngineOutput::autoGains() const {
  QVariantList values;
  values.reserve(10);
  for (float gain : m_autoGains)
    values.append(gain);
  return values;
}

QVariantList AudioEngineOutput::spectrum() const {
  QVariantList values;
  values.reserve(10);
  for (float level : m_spectrum)
    values.append(level);
  return values;
}

QString AudioEngineOutput::deviceIdFor(const QByteArray &id) const {
  return QString::fromLatin1(id.toHex());
}

QVariantList AudioEngineOutput::outputDevices() const {
  QVariantList values;
  values.append(QVariantMap{{QStringLiteral("id"), QStringLiteral("default")},
                            {QStringLiteral("label"), tr("System default")}});
  for (const auto &device : QMediaDevices::audioOutputs()) {
    values.append(QVariantMap{{QStringLiteral("id"), deviceIdFor(device.id())},
                              {QStringLiteral("label"), device.description()}});
  }
  return values;
}

void AudioEngineOutput::attachPlayer(QMediaPlayer *player) {
  if (!player || m_bufferOutputs.contains(player))
    return;
  auto *output = new QAudioBufferOutput(AudioPipeline::format(), this);
  m_bufferOutputs.insert(player, output);
  player->setAudioBufferOutput(output);
  // Qt emits from its decoder threads; the pipeline context queues each buffer
  // straight onto the audio thread.
  connect(output, &QAudioBufferOutput::audioBufferReceived, m_pipeline,
          [pipeline = m_pipeline, player](const QAudioBuffer &buffer) {
            pipeline->processBuffer(player, buffer);
          });
}

void AudioEngineOutput::setCurrentPlayer(QMediaPlayer *player) {
  attachPlayer(player);
  post([pipeline = m_pipeline, player] { pipeline->setCurrentPlayer(player); });
}

void AudioEngineOutput::setActiveTrackId(const QString &trackId) {
  if (m_activeTrackId == trackId)
    return;
  m_activeTrackId = trackId;
  m_trackGainDb = finiteClamp(m_trackGains.value(trackId, 0.0).toDouble(), -12.0, 12.0, 0.0);
  post([pipeline = m_pipeline, trackId] { pipeline->setActiveTrackId(trackId); });
  emit configurationChanged();
}

void AudioEngineOutput::setMasterVolume(double volume) {
  volume = finiteClamp(volume, 0.0, 1.0, 1.0);
  post([pipeline = m_pipeline, volume] { pipeline->setMasterVolume(volume); });
}

void AudioEngineOutput::setPlaying(bool playing) {
  if (playing)
    ensureAudioBackend();
  post([pipeline = m_pipeline, playing] { pipeline->setPlaying(playing); });
}

void AudioEngineOutput::flush() {
  m_mixSerial = 0;
  m_adaptive = false;
  post([pipeline = m_pipeline] { pipeline->flush(); });
}

bool AudioEngineOutput::beginCrossfade(QMediaPlayer *incomingPlayer,
                                       const QString &incomingTrackId,
                                       double durationSeconds) {
  attachPlayer(incomingPlayer);
  quint64 serial = 0;
  ask([&, pipeline = m_pipeline] {
    if (pipeline->beginCrossfade(incomingPlayer, incomingTrackId, durationSeconds))
      serial = pipeline->mixSerial();
  });
  m_mixSerial = serial;
  m_adaptive = false;
  return serial != 0;
}

double AudioEngineOutput::beginAdaptiveMix(QMediaPlayer *incomingPlayer,
                                           const QString &trackId,
                                           const QByteArray &pcm, double incomingCue,
                                           double outgoingStart) {
  attachPlayer(incomingPlayer);
  double cue = -1.0;
  quint64 serial = 0;
  ask([&, pipeline = m_pipeline] {
    cue = pipeline->beginAdaptiveMix(incomingPlayer, trackId, pcm, incomingCue, outgoingStart);
    if (cue >= 0.0)
      serial = pipeline->mixSerial();
  });
  m_mixSerial = serial;
  m_adaptive = serial != 0;
  return cue;
}

double AudioEngineOutput::outgoingEnd() const {
  double end = -1.0;
  ask([&, pipeline = m_pipeline] { end = pipeline->outgoingEnd(); });
  return end;
}

double AudioEngineOutput::audiblePosition() const {
  double position = -1.0;
  ask([&, pipeline = m_pipeline] { position = pipeline->audiblePosition(); });
  return position;
}

bool AudioEngineOutput::cancelCrossfade() {
  m_mixSerial = 0;
  m_adaptive = false;
  bool cancelled = true;
  ask([&, pipeline = m_pipeline] { cancelled = pipeline->cancelCrossfade(); });
  return cancelled;
}

void AudioEngineOutput::completeCrossfade() {
  post([pipeline = m_pipeline] { pipeline->completeCrossfade(); });
}

void AudioEngineOutput::refreshDevices() {
  const auto devices = QMediaDevices::audioOutputs();
  const bool selectedExists = m_outputDeviceId == QStringLiteral("default") ||
      std::any_of(devices.cbegin(), devices.cend(),
                  [this](const QAudioDevice &device) { return deviceIdFor(device.id()) == m_outputDeviceId; });
  if (!selectedExists) {
    m_outputDeviceId = QStringLiteral("default");
    QSettings().setValue(QStringLiteral("playback/audioEngine/outputDeviceId"), m_outputDeviceId);
    emit outputDeviceChanged();
  }
  emit outputDevicesChanged();
}

QAudioDevice AudioEngineOutput::selectedDevice() const {
  if (m_outputDeviceId != QStringLiteral("default")) {
    for (const auto &device : QMediaDevices::audioOutputs()) {
      if (deviceIdFor(device.id()) == m_outputDeviceId)
        return device;
    }
  }
  return QMediaDevices::defaultAudioOutput();
}

void AudioEngineOutput::pushDevice() {
  post([pipeline = m_pipeline, device = selectedDevice()] { pipeline->setDevice(device); });
}

void AudioEngineOutput::ensureAudioBackend() {
  if (m_mediaDevices)
    return;
  m_mediaDevices = std::make_unique<QMediaDevices>();
  connect(m_mediaDevices.get(), &QMediaDevices::audioOutputsChanged, this, [this] {
    refreshDevices();
    pushDevice();
  });
  refreshDevices();
  pushDevice();
}
