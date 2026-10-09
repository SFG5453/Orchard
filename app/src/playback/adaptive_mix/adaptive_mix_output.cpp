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

#include "../audio_pipeline.h"
#include "adaptive_mix_span.h"
#include <QAudioBuffer>
#include <QDebug>
#include <QMediaPlayer>
#include <algorithm>
#include <cmath>

double AudioPipeline::beginAdaptiveMix(const QMediaPlayer *incomingPlayer,
                                       const QString &trackId,
                                       const QByteArray &pcm,
                                       double incomingCue,
                                       double outgoingStart) {
  if (!std::isfinite(incomingCue) || incomingCue < 0 ||
      !std::isfinite(outgoingStart) || pcm.isEmpty() || pcm.size() % kFrameBytes)
    return -1.0;
  const qint64 total = pcm.size() / kFrameBytes;
  // The render opens on the outgoing song at unity, so the audio already
  // queued reappears in it. Resume right after that audio: nobody asked for a
  // 20 ms encore.
  qint64 skip = m_outgoingEndUs >= 0
                    ? std::max<qint64>(0, qRound64((m_outgoingEndUs / 1000000.0 -
                                                    outgoingStart) * kSampleRate))
                    : 0;
  const qint64 recent = m_outgoingRecent.size() / kFrameBytes;
  if (recent >= kAlignWindow)
    skip = adaptiveMatchingEnd(
        reinterpret_cast<const float *>(m_outgoingRecent.constData()) +
            (recent - kAlignWindow) * kChannels,
        reinterpret_cast<const float *>(pcm.constData()), total, kAlignWindow,
        skip, kAlignSearch);
  if (skip >= total || !beginCrossfade(incomingPlayer, trackId, 1.0))
    return -1.0;
  m_adaptivePcm = pcm.mid(skip * kFrameBytes);
  m_crossfadeFramesTotal = total - skip;
  m_adaptiveIncomingSeen = false;
  const double cue = incomingCue + double(skip) / kSampleRate;
  m_adaptiveIncomingStartUs = qRound64(cue * 1000000);
  // Start ahead by the learned startup latency so the incoming clock meets the
  // render on arrival.
  return cue + m_adaptiveStartLeadUs / 1000000.0;
}

bool AudioPipeline::emitAdaptiveRender(qint64 frames) {
  QByteArray mixed =
      m_adaptivePcm.mid(m_crossfadeFramesMixed * kFrameBytes, frames * kFrameBytes);
  if (frames > 0 &&
      !orchard_audio_engine_process(
          m_engine, reinterpret_cast<float *>(mixed.data()), frames))
    return false;
  // The render's last frames wait to be faded into the native continuation.
  const qint64 held = std::clamp(m_crossfadeFramesMixed + frames -
                                     (m_crossfadeFramesTotal - kHandoffFade),
                                 qint64(0), frames);
  m_pending.append(mixed.left((frames - held) * kFrameBytes));
  m_handoffRender.append(mixed.mid((frames - held) * kFrameBytes));
  m_crossfadeFramesMixed += frames;
  emit crossfadeProgressChanged(m_mixSerial, static_cast<double>(m_crossfadeFramesMixed) /
                                                 m_crossfadeFramesTotal);
  return true;
}

void AudioPipeline::rememberOutgoing(const QAudioBuffer &buffer) {
  m_outgoingRecent.append(buffer.constData<char>(), buffer.byteCount());
  const qsizetype keep = 2 * kAlignWindow * kFrameBytes;
  if (m_outgoingRecent.size() > keep)
    m_outgoingRecent.remove(0, m_outgoingRecent.size() - keep);
  m_outgoingEndUs =
      buffer.startTime() >= 0
          ? buffer.startTime() +
                qRound64(buffer.frameCount() * 1000000.0 / kSampleRate)
          : -1;
}

void AudioPipeline::forgetOutgoing() {
  m_outgoingRecent.clear();
  m_outgoingEndUs = -1;
}

void AudioPipeline::rememberHandoff(qint64 renderFrame, const QByteArray &raw,
                                        const QByteArray &processed) {
  const qint64 kept = m_handoffRaw.size() / kFrameBytes;
  if (kept == 0 || m_handoffOrigin + kept != renderFrame) {
    m_handoffRaw.clear();
    m_handoffProcessed.clear();
    m_handoffOrigin = renderFrame;
  }
  m_handoffRaw.append(raw);
  m_handoffProcessed.append(processed);
  // Enough for one aligned comparison either side of the render's end.
  const qint64 excess =
      m_handoffRaw.size() / kFrameBytes - (kAlignWindow + 2 * kAlignSearch + 8192);
  if (excess > 0) {
    m_handoffRaw.remove(0, excess * kFrameBytes);
    m_handoffProcessed.remove(0, excess * kFrameBytes);
    m_handoffOrigin += excess;
  }
}

bool AudioPipeline::handOffAdaptiveMix() {
  const qint64 kept = m_handoffRaw.size() / kFrameBytes;
  const qint64 before = std::clamp(m_crossfadeFramesTotal - m_handoffOrigin,
                                   qint64(0), kept);
  qint64 resume = before;
  if (before >= kAlignWindow + kAlignLead + kAlignSearch) {
    // Match a window ending kAlignLead frames before the render does, so the
    // incoming audio it needs has already arrived and the queue never waits.
    const qint64 end = m_crossfadeFramesTotal - kAlignLead;
    resume = kAlignLead + adaptiveMatchingEnd(
        reinterpret_cast<const float *>(m_adaptivePcm.constData()) +
            (end - kAlignWindow) * kChannels,
        reinterpret_cast<const float *>(m_handoffRaw.constData()), kept,
        kAlignWindow, before - kAlignLead, kAlignSearch);
  }
  // The continuation lies past this buffer; the next one brings it.
  if (resume > kept)
    return false;
  resume = std::max<qint64>(resume, 0);
  // Native frame `resume` carries the render's last content, so its decoder
  // stamp is off from content time by the match offset. Lyrics need to know.
  m_handoffCorrectionUs = qRound64((before - resume) * 1000000.0 / kSampleRate);
  if (m_timing)
    qInfo() << "Playback: adaptive handoff offset"
            << m_handoffCorrectionUs / 1000.0 << "ms, queued"
            << (m_pending.size() / kFrameBytes) * 1000.0 / kSampleRate << "ms";
  // Fade the held render frames into the native frames that carry the same
  // audio, so a sample or two of residual misalignment never clicks.
  const qint64 held = m_handoffRender.size() / kFrameBytes;
  const qint64 fade = std::min(held, resume);
  auto *render = reinterpret_cast<float *>(m_handoffRender.data()) +
                 (held - fade) * kChannels;
  const auto *native = reinterpret_cast<const float *>(m_handoffProcessed.constData()) +
                       (resume - fade) * kChannels;
  for (qint64 frame = 0; frame < fade; ++frame) {
    const float weight = float(frame + 1) / float(fade + 1);
    for (int channel = 0; channel < kChannels; ++channel) {
      float &sample = render[frame * kChannels + channel];
      sample += (native[frame * kChannels + channel] - sample) * weight;
    }
  }
  m_crossfadeIncomingPending.append(m_handoffRender);
  m_crossfadeIncomingPending.append(m_handoffProcessed.mid(resume * kFrameBytes));
  m_handoffRender.clear();
  return true;
}

void AudioPipeline::processAdaptiveBuffer(const QMediaPlayer *source,
                                          const QAudioBuffer &buffer) {
  if (source == m_currentPlayer && !m_adaptiveIncomingSeen) {
    // The outgoing clock paces the render until the incoming player speaks up,
    // so its startup latency never drains the output.
    const qint64 frames = std::min<qint64>(
        buffer.frameCount(),
        m_crossfadeFramesTotal - kHandoffFade - m_crossfadeFramesMixed);
    ensureSink();
    if (frames > 0 && emitAdaptiveRender(frames))
      flushPending();
    return;
  }
  if (source != m_crossfadePlayer)
    return;
  // Incoming decoder timestamps drive the overlap, so pause/resume and the
  // eventual handoff share one clock. No wall-clock DJ with a stopwatch.
  const auto span = adaptiveMixSpan(
      buffer.startTime(), m_adaptiveIncomingStartUs, buffer.frameCount(),
      m_crossfadeFramesTotal, m_crossfadeFramesMixed);
  if (!m_adaptiveIncomingSeen) {
    m_adaptiveIncomingSeen = true;
    // Positive: the incoming player arrived early and the render catches up.
    const qint64 lead = span.mixOffset - span.preroll - m_crossfadeFramesMixed;
    const qint64 leadUs = qRound64(lead * 1000000.0 / kSampleRate);
    m_adaptiveStartLeadUs =
        std::clamp<qint64>(m_adaptiveStartLeadUs - leadUs / 2, 0, 150000);
    if (m_timing)
      qInfo() << "Playback: incoming player lead" << leadUs / 1000.0
              << "ms, next start lead" << m_adaptiveStartLeadUs / 1000.0 << "ms";
  }
  if (span.preroll >= buffer.frameCount())
    return;
  ensureSink();
  // An early start or late seek leaves render frames before the incoming
  // audio. Play them; the deeper queue is inaudible.
  const qint64 gap = std::min(span.mixOffset, m_crossfadeFramesTotal - kHandoffFade) -
                     m_crossfadeFramesMixed;
  if (gap > 0 && !emitAdaptiveRender(gap))
    return;
  const auto *sourceSamples =
      buffer.constData<float>() + span.preroll * kChannels;
  const qint64 frames = span.mixFrames;
  m_crossfadeFramesMixed = span.mixOffset;
  // Keep the incoming EQ and level history warm while the rendered overlap
  // plays. Its untouched tail then continues through the same engine state.
  const qint64 incomingFrames = frames + span.tailFrames;
  const QByteArray raw(reinterpret_cast<const char *>(sourceSamples),
                       incomingFrames * kFrameBytes);
  QByteArray incoming = raw;
  if (incomingFrames > 0 &&
      !orchard_audio_engine_process(
          m_crossfadeEngine, reinterpret_cast<float *>(incoming.data()),
          incomingFrames))
    return;
  rememberHandoff(frames > 0 ? span.mixOffset
                             : m_handoffOrigin + m_handoffRaw.size() / kFrameBytes,
                  raw, incoming);
  if (!emitAdaptiveRender(frames))
    return;
  if (m_crossfadeFramesMixed == m_crossfadeFramesTotal && handOffAdaptiveMix())
    completeCrossfade();
  flushPending();
}
