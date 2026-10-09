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

#pragma once

#include "orchard_core.h"

#include <QHash>
#include <QMediaDevices>
#include <QObject>
#include <QThread>
#include <QVariantList>

#include <functional>
#include <memory>

class AudioPipeline;
class QAudioBuffer;
class QAudioBufferOutput;
class QAudioDevice;
class QMediaPlayer;

// Settings, devices and meters for QML, plus the playback controller's handle
// on the audio thread. Samples never pass through here: decoder threads feed
// AudioPipeline directly, so a stalled UI cannot starve the speakers.
class AudioEngineOutput final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool enabled READ enabled WRITE setEnabled NOTIFY configurationChanged)
  Q_PROPERTY(bool autoEqEnabled READ autoEqEnabled WRITE setAutoEqEnabled NOTIFY configurationChanged)
  Q_PROPERTY(bool eqEnabled READ eqEnabled WRITE setEqEnabled NOTIFY configurationChanged)
  Q_PROPERTY(bool normalizationEnabled READ normalizationEnabled WRITE setNormalizationEnabled NOTIFY configurationChanged)
  Q_PROPERTY(QVariantList gains READ gains NOTIFY configurationChanged)
  Q_PROPERTY(double preampDb READ preampDb WRITE setPreampDb NOTIFY configurationChanged)
  Q_PROPERTY(double outputGainDb READ outputGainDb WRITE setOutputGainDb NOTIFY configurationChanged)
  Q_PROPERTY(double q READ q WRITE setQ NOTIFY configurationChanged)
  Q_PROPERTY(double balance READ balance WRITE setBalance NOTIFY configurationChanged)
  Q_PROPERTY(double trackGainDb READ trackGainDb WRITE setTrackGainDb NOTIFY configurationChanged)
  Q_PROPERTY(QString activePreset READ activePreset NOTIFY configurationChanged)
  Q_PROPERTY(QString outputDeviceId READ outputDeviceId WRITE setOutputDeviceId NOTIFY outputDeviceChanged)
  Q_PROPERTY(QVariantList outputDevices READ outputDevices NOTIFY outputDevicesChanged)
  Q_PROPERTY(QVariantList autoGains READ autoGains NOTIFY metersChanged)
  Q_PROPERTY(QVariantList spectrum READ spectrum NOTIFY metersChanged)

public:
  explicit AudioEngineOutput(QObject *parent = nullptr);
  ~AudioEngineOutput() override;

  bool enabled() const { return m_enabled; }
  bool autoEqEnabled() const { return m_autoEqEnabled; }
  bool eqEnabled() const { return m_eqEnabled; }
  bool normalizationEnabled() const { return m_normalizationEnabled; }
  QVariantList gains() const;
  double preampDb() const { return m_preampDb; }
  double outputGainDb() const { return m_outputGainDb; }
  double q() const { return m_q; }
  double balance() const { return m_balance; }
  double trackGainDb() const { return m_trackGainDb; }
  QString activePreset() const;
  QString outputDeviceId() const { return m_outputDeviceId; }
  QVariantList outputDevices() const;
  QVariantList autoGains() const;
  QVariantList spectrum() const;

  void attachPlayer(QMediaPlayer *player);
  void setCurrentPlayer(QMediaPlayer *player);
  void setActiveTrackId(const QString &trackId);
  void setMasterVolume(double volume);
  void setPlaying(bool playing);
  void flush();
  bool beginCrossfade(QMediaPlayer *incomingPlayer, const QString &incomingTrackId,
                      double durationSeconds);
  // Returns where the incoming player starts so it lines up with the render,
  // or -1 when the mix cannot begin.
  double beginAdaptiveMix(QMediaPlayer *incomingPlayer, const QString &trackId,
                          const QByteArray &pcm, double incomingCue, double outgoingStart);
  // Track time just past the last outgoing frame queued for output; -1 if unknown.
  double outgoingEnd() const;
  // Track time of the audio leaving the sink now; -1 if unknown or mid-transition.
  double audiblePosition() const;
  // False when the audio thread finished the mix before the cancel reached it;
  // the caller then commits the track switch instead.
  bool cancelCrossfade();
  void completeCrossfade();
  // The transition this side started and has not yet heard finish.
  bool crossfadeActive() const { return m_mixSerial != 0; }
  bool adaptiveMixActive() const { return m_mixSerial != 0 && m_adaptive; }

  void setEnabled(bool enabled);
  void setAutoEqEnabled(bool enabled);
  void setEqEnabled(bool enabled);
  void setNormalizationEnabled(bool enabled);
  void setPreampDb(double value);
  void setOutputGainDb(double value);
  void setQ(double value);
  void setBalance(double value);
  void setTrackGainDb(double value);
  void setOutputDeviceId(const QString &deviceId);

  Q_INVOKABLE void setBandGain(int index, double gainDb);
  Q_INVOKABLE void applyPreset(const QString &name);
  Q_INVOKABLE void resetEngine();

signals:
  void configurationChanged();
  void outputDeviceChanged();
  void outputDevicesChanged();
  void metersChanged();
  void crossfadeProgressChanged(double progress);
  void crossfadeFinished();
  // Decoded audio before the engine touches it, tagged with the track it belongs to.
  void decodedAudio(const QString &trackId, const QAudioBuffer &buffer);

private:
  friend class AdaptiveMixOutputTest;
  void loadSettings();
  void persistConfiguration();
  void applyConfiguration();
  void ensureAudioBackend();
  void refreshDevices();
  void pushDevice();
  QAudioDevice selectedDevice() const;
  OrchardAudioEngineConfig engineConfiguration() const;
  QString deviceIdFor(const QByteArray &id) const;
  // Queues work on the audio thread; it runs in call order with everything else there.
  void post(std::function<void()> work) const;
  // Runs work on the audio thread and waits. That thread never waits on this
  // one, so the round trip cannot deadlock.
  void ask(const std::function<void()> &work) const;

  QThread m_audioThread;
  AudioPipeline *m_pipeline{nullptr};
  std::unique_ptr<QMediaDevices> m_mediaDevices;
  QHash<QMediaPlayer *, QAudioBufferOutput *> m_bufferOutputs;
  quint64 m_mixSerial{0};
  bool m_adaptive{false};

  bool m_enabled{true};
  bool m_autoEqEnabled{false};
  bool m_eqEnabled{false};
  bool m_normalizationEnabled{false};
  float m_gains[10]{};
  float m_autoGains[10]{};
  float m_spectrum[10]{};
  double m_preampDb{0.0};
  double m_outputGainDb{0.0};
  double m_q{1.1};
  double m_balance{0.0};
  double m_trackGainDb{0.0};
  QString m_outputDeviceId{QStringLiteral("default")};
  QString m_activeTrackId;
  QVariantMap m_trackGains;
};
