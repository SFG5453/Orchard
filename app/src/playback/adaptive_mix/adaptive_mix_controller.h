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
#include "adaptive_mix_worker.h"
#include <QObject>
#include <QUrl>
#include <QVariantMap>

// Owns preparation only. AudioPipeline owns the audible sample clock.
class AdaptiveMixController final : public QObject {
  Q_OBJECT
  // Reads "standard" while unavailable; the saved choice returns afterwards.
  Q_PROPERTY(QString mode READ mode WRITE setMode NOTIFY modeChanged)
  Q_PROPERTY(bool available READ available NOTIFY modeChanged)
  Q_PROPERTY(QString status READ status NOTIFY changed)
  Q_PROPERTY(bool ready READ ready NOTIFY changed)
  Q_PROPERTY(QString mixStyle READ mixStyle NOTIFY changed)
  Q_PROPERTY(double mixBpm READ mixBpm NOTIFY changed)
public:
  explicit AdaptiveMixController(QObject *parent = nullptr);
  ~AdaptiveMixController() override;
  QString mode() const { return m_available ? m_mode : QStringLiteral("standard"); }
  void setMode(const QString &mode);
  bool available() const { return m_available; }
  // Qobuz streams aren't analysed, so playback turns adaptive mix off while it's on.
  void setAvailable(bool available);
  bool enabled() const { return mode() == QStringLiteral("adaptive"); }
  QString status() const { return m_status; }
  bool ready() const { return !m_pcm.isEmpty(); }
  // Listener-facing name of the rendered strategy; empty until a render is ready.
  QString mixStyle() const;
  double mixBpm() const { return ready() ? m_bpm : 0.0; }
  double outgoingStart() const { return m_outgoingStart; }
  // Incoming media time where the overlap starts; the handoff lands on the planned resume.
  double incomingCue() const { return m_incomingCue; }
  double duration() const { return m_duration; }
  const QByteArray &pcm() const { return m_pcm; }
  bool standardFallback() const { return m_failed && !m_keepBoundary; }
  void prepare(const QString &pair, const QUrl &outgoing, const QUrl &incoming,
               double duration, double position, const QVariantMap &currentTrack,
               const QVariantMap &nextTrack, int fadeSeconds,
               bool albumSequential);
  void clear();
signals:
  void changed();
  void modeChanged();

private:
  void fail(const QString &message);
  void receive(const QJsonObject &header, const QByteArray &pcm);
  QString m_mode;
  QString m_status;
  QString m_pair;
  QString m_label; // "Outgoing -> Incoming" titles for logs.
  QString m_strategy;
  double m_bpm{0};
  QByteArray m_pcm;
  AdaptiveMixWorker m_worker;
  bool m_available{true};
  bool m_preparing{false};
  bool m_failed{false};
  bool m_keepBoundary{false};
  bool m_shapingUnavailable{false};
  double m_outgoingStart{0};
  double m_incomingCue{0};
  double m_duration{0};
};
