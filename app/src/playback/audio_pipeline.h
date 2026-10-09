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

#include <QAudioDevice>
#include <QAudioFormat>
#include <QElapsedTimer>
#include <QList>
#include <QObject>
#include <QTimer>
#include <QVariantMap>

#include <memory>

class QAudioBuffer;
class QAudioSink;
class QIODevice;
class QMediaPlayer;

// Decoded audio in, speakers out. Lives on the audio thread so UI stalls never
// starve the sink; every member is touched only from that thread. Players are
// identity keys here and are never dereferenced.
// The UI may stop to admire its own blur. The bass does not wait for it.
class AudioPipeline final : public QObject {
  Q_OBJECT

public:
  explicit AudioPipeline(QObject *parent = nullptr);
  ~AudioPipeline() override;

  static QAudioFormat format();

  // Starts the flush and meter timers on the owning thread.
  void start();
  void processBuffer(const QMediaPlayer *source, const QAudioBuffer &buffer);
  void configure(const OrchardAudioEngineConfig &base, const QVariantMap &trackGains);
  void setCurrentPlayer(const QMediaPlayer *player);
  void setActiveTrackId(const QString &trackId);
  void setMasterVolume(double volume);
  void setPlaying(bool playing);
  // Takes effect immediately when a sink exists, else on first output.
  void setDevice(const QAudioDevice &device);
  void flush();
  bool beginCrossfade(const QMediaPlayer *incomingPlayer, const QString &incomingTrackId,
                      double durationSeconds);
  // Returns where the incoming player starts so it lines up with the render,
  // or -1 when the mix cannot begin.
  double beginAdaptiveMix(const QMediaPlayer *incomingPlayer, const QString &trackId,
                          const QByteArray &pcm, double incomingCue, double outgoingStart);
  // False when the mix already finished here, so the caller should commit the switch.
  bool cancelCrossfade();
  void completeCrossfade();
  // Track time just past the last outgoing frame queued for output; -1 if unknown.
  double outgoingEnd() const {
    return m_outgoingEndUs >= 0 ? m_outgoingEndUs / 1000000.0 : -1.0;
  }
  // Track time of the audio leaving the sink now; -1 if unknown or mid-transition.
  double audiblePosition() const;
  bool crossfadeActive() const { return m_crossfadeActive; }
  bool adaptiveMixActive() const { return m_crossfadeActive && !m_adaptivePcm.isEmpty(); }
  // Tags each transition so late signals from a cancelled one are ignored.
  quint64 mixSerial() const { return m_mixSerial; }

signals:
  void crossfadeProgressChanged(quint64 serial, double progress);
  void crossfadeFinished(quint64 serial);
  // Decoded audio before the engine touches it, tagged with the track it belongs to.
  void decodedAudio(const QString &trackId, const QAudioBuffer &buffer);
  void metersChanged(const QList<float> &autoGains, const QList<float> &spectrum);

private:
  friend class AdaptiveMixOutputTest;
  OrchardAudioEngineConfig trackConfiguration(const QString &trackId) const;
  void applyConfiguration();
  void ensureSink();
  void rebuildSink();
  void resetCrossfade();
  void mixCrossfadePending();
  void processAdaptiveBuffer(const QMediaPlayer *source, const QAudioBuffer &buffer);
  void rememberOutgoing(const QAudioBuffer &buffer);
  void forgetOutgoing();
  void rememberHandoff(qint64 renderFrame, const QByteArray &raw,
                       const QByteArray &processed);
  bool handOffAdaptiveMix();
  // Queues the next render frames through the engine, holding the handoff fade.
  bool emitAdaptiveRender(qint64 frames);
  void flushPending();

  static constexpr int kSampleRate = 48000;
  static constexpr int kChannels = 2;
  static constexpr qint64 kFrameBytes = sizeof(float) * kChannels;
  // Join alignment: 43 ms compared, searched 30 ms either way. The end join
  // compares audio 10 ms before the render's last frame.
  static constexpr qint64 kAlignWindow = 2048;
  static constexpr qint64 kAlignSearch = 1440;
  static constexpr qint64 kAlignLead = 480;
  // The render fades into native incoming playback over its last 5 ms.
  static constexpr qint64 kHandoffFade = 256;
  // Output starts once 150 ms is queued. Decoder threads feed this thread
  // directly, so the cushion only has to cover decoder jitter.
  static constexpr qint64 kPrimeFrames = 7200;
  static constexpr qint64 kSinkFrames = 12000;

  QAudioFormat m_format;
  QAudioDevice m_device;
  std::unique_ptr<QAudioSink> m_sink;
  QIODevice *m_sinkDevice{nullptr};
  QByteArray m_pending;
  bool m_primed{false};
  double m_masterVolume{1.0};
  QTimer m_flushTimer{this};
  QTimer m_meterTimer{this};
  OrchardAudioEngineHandle *m_engine{nullptr};
  OrchardAudioEngineHandle *m_crossfadeEngine{nullptr};
  OrchardAudioEngineConfig m_config{};
  QVariantMap m_trackGains;
  const QMediaPlayer *m_currentPlayer{nullptr};
  QString m_activeTrackId;
  const QMediaPlayer *m_crossfadePlayer{nullptr};
  QString m_crossfadeTrackId;
  QByteArray m_crossfadeOutgoingPending;
  QByteArray m_crossfadeIncomingPending;
  qint64 m_crossfadeFramesMixed{0};
  qint64 m_crossfadeFramesTotal{0};
  bool m_crossfadeActive{false};
  quint64 m_mixSerial{0};
  quint64 m_finishedSerial{0};
  QByteArray m_adaptivePcm;
  qint64 m_adaptiveIncomingStartUs{0};
  bool m_adaptiveIncomingSeen{false};
  // How far ahead the incoming player starts, learned from each mix's startup.
  qint64 m_adaptiveStartLeadUs{20000};
  // Raw frames last queued from the current player, for the adaptive start join.
  QByteArray m_outgoingRecent;
  qint64 m_outgoingEndUs{-1};
  // Incoming audio around the render's end, raw and engine-processed, from
  // render frame m_handoffOrigin on.
  QByteArray m_handoffRaw;
  QByteArray m_handoffProcessed;
  qint64 m_handoffOrigin{0};
  // Engine-processed render frames held for the handoff fade.
  QByteArray m_handoffRender;
  // Content time minus decoder timestamp for the current player. Seeked Opus
  // stamps drift from the audio, and the join follows the audio.
  qint64 m_clockCorrectionUs{0};
  qint64 m_handoffCorrectionUs{0};
  // ORCHARD_PLAYBACK_TIMING: buffer stalls and underrun reports around transitions.
  bool m_timing{false};
  QElapsedTimer m_timingClock;
  qint64 m_lastBufferMs{-1};
  qint64 m_transitionMs{-1};
  bool m_awaitingIncoming{false};
};
