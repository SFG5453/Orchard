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

#include "adaptive_mix_controller.h"
#include <QDebug>
#include <QJsonObject>
#include <QSettings>
#include <cmath>
#include <utility>

AdaptiveMixController::AdaptiveMixController(QObject *parent)
    : QObject(parent) {
  m_mode = QSettings().value("playback/crossfadeMode", "standard").toString();
  if (m_mode != "adaptive")
    m_mode = "standard";
  connect(&m_worker, &AdaptiveMixWorker::finished, this, &AdaptiveMixController::receive);
  connect(&m_worker, &AdaptiveMixWorker::failed, this, [this](const QString &message) {
    if (m_preparing)
      fail(message);
  });
}
AdaptiveMixController::~AdaptiveMixController() = default;
void AdaptiveMixController::setMode(const QString &mode) {
  const QString normalized =
      mode == "adaptive" ? mode : QStringLiteral("standard");
  if (normalized == m_mode || (!m_available && normalized == "adaptive"))
    return;
  clear();
  m_mode = normalized;
  QSettings().setValue("playback/crossfadeMode", m_mode);
  emit modeChanged();
}
void AdaptiveMixController::setAvailable(bool available) {
  if (m_available == available)
    return;
  clear();
  m_available = available;
  emit modeChanged();
}
void AdaptiveMixController::clear() {
  if (m_preparing)
    m_worker.cancel();
  m_preparing = false;
  m_failed = false;
  m_keepBoundary = false;
  m_shapingUnavailable = false;
  m_pair.clear();
  m_strategy.clear();
  m_bpm = 0;
  m_pcm.clear();
  m_status.clear();
  m_outgoingStart = m_incomingCue = m_duration = 0;
  emit changed();
}
QString AdaptiveMixController::mixStyle() const {
  if (!ready())
    return {};
  // Keys are earmark TransitionStrategy::describe() names.
  if (m_strategy == QStringLiteral("beatmatched crossfade"))
    return tr("Beatmatched");
  if (m_strategy == QStringLiteral("bass swap"))
    return tr("Bass swap");
  if (m_strategy == QStringLiteral("filtered blend"))
    return tr("Filtered blend");
  if (m_strategy == QStringLiteral("short fade"))
    return tr("Short fade");
  if (m_strategy == QStringLiteral("equal-power crossfade"))
    return tr("Crossfade");
  return {};
}
void AdaptiveMixController::fail(const QString &message) {
  m_preparing = false;
  m_failed = true;
  // Matches planner::NATURAL_BOUNDARY in the analysis worker.
  m_keepBoundary =
      message.startsWith(QStringLiteral("Shared planner keeps the natural boundary"));
  m_pcm.clear();
  qInfo().noquote() << (m_keepBoundary ? "AdaptiveMix: no mix" : "AdaptiveMix: plan failed")
                    << m_label << "|" << message;
  m_status = m_keepBoundary ? tr("Adaptive mix ready · this pair plays without a blend")
                            : tr("Adaptive mix unavailable: %1").arg(message);
  emit changed();
}
void AdaptiveMixController::prepare(const QString &pair, const QUrl &outgoing,
                                    const QUrl &incoming, double duration,
                                    double position, const QVariantMap &currentTrack,
                                    const QVariantMap &nextTrack, int fadeSeconds,
                                    bool albumSequential) {
  if (!enabled() || pair == m_pair)
    return;
  clear();
  m_pair = pair;
  m_label = currentTrack.value("title").toString() + " -> " +
            nextTrack.value("title").toString();
  m_preparing = true;
  m_status = tr("Preparing adaptive mix…");
  emit changed();
  m_worker.start(QJsonObject{{"outgoingUrl", outgoing.toString()},
                             {"incomingUrl", incoming.toString()},
                             {"outgoingDuration", duration},
                             {"position", position},
                             {"incomingDuration", nextTrack.value("durationSeconds").toDouble()},
                             {"currentTrack", QJsonObject::fromVariantMap(currentTrack)},
                             {"nextTrack", QJsonObject::fromVariantMap(nextTrack)},
                             {"fadeSeconds", fadeSeconds},
                             {"albumSequential", albumSequential}});
}
void AdaptiveMixController::receive(const QJsonObject &object, const QByteArray &pcm) {
  if (!m_preparing)
    return;
  if (object.contains("error")) {
    fail(object.value("error").toString());
    return;
  }
  m_outgoingStart = object.value("outgoingStart").toDouble(-1);
  m_incomingCue = object.value("incomingCue").toDouble(-1);
  m_duration = object.value("duration").toDouble(-1);
  if (!std::isfinite(m_outgoingStart) || m_outgoingStart < 0 ||
      !std::isfinite(m_incomingCue) || m_incomingCue < 0 ||
      !std::isfinite(m_duration) || m_duration <= 0 || m_duration > 30 ||
      pcm.size() != qRound64(m_duration * 48000) * 8) {
    fail(tr("Invalid rendered overlap."));
    return;
  }
  m_shapingUnavailable = !object.value("warning").toString().isEmpty();
  m_strategy = object.value("strategy").toString();
  m_bpm = object.value("targetBpm").toDouble();
  // One line per transition so bad-sounding mixes can be matched to their plan.
  // Grep for this when a mix sounds like two radios arguing in a parking lot.
  const int loopBeats = object.value("loopBeats").toInt();
  const double kickLock = object.value("kickLock").toDouble();
  qInfo().noquote() << "AdaptiveMix: plan" << m_label << "| strategy" << m_strategy
                    << "| bpm" << m_bpm << "| out" << m_outgoingStart << "| cue"
                    << m_incomingCue << "| dur" << m_duration
                    << (loopBeats > 0 ? QStringLiteral("| loop %1 beats").arg(loopBeats) : QString())
                    << (kickLock != 0 ? QStringLiteral("| kick lock %1 ms").arg(kickLock) : QString())
                    << (m_shapingUnavailable ? "| no vocal shaping" : "");
  m_pcm = pcm;
  m_preparing = false;
  m_status = m_shapingUnavailable
                 ? tr("Adaptive mix ready · vocal shaping unavailable")
                 : tr("Adaptive mix ready");
  emit changed();
}
