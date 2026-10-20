/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

#include "adaptive_mix_controller.h"
#include <QDebug>
#include <QJsonObject>
#include <QRegularExpression>
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
  m_technique.clear();
  m_bpm = 0;
  m_pcm.clear();
  m_references.clear();
  m_referenceInfo = {};
  m_status.clear();
  m_outgoingStart = m_incomingCue = m_duration = 0;
  emit changed();
}
QString AdaptiveMixController::mixStyle() const {
  if (!ready())
    return {};
  // Techniques come from the shared planner; the rest are earmark strategy names.
  if (m_technique == QStringLiteral("beatless_bridge"))
    return tr("Bridge");
  if (m_technique == QStringLiteral("drop_in"))
    return tr("Drop-in");
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
// The worker reports "(code: detail)" after the boundary prefix; codes come from
// transitionExplain.js in the shared planner.
static QString boundaryReason(const QString &message) {
  const int open = message.indexOf(QLatin1Char('('));
  if (open < 0)
    return {};
  QString code = message.mid(open + 1);
  code = code.left(code.indexOf(QRegularExpression(QStringLiteral("[:)]")))).trimmed();
  if (code == QLatin1String("tempo-distance") || code == QLatin1String("outgoing-tempo") ||
      code == QLatin1String("incoming-tempo"))
    return AdaptiveMixController::tr("tempos too far apart");
  if (code == QLatin1String("moving-tempo"))
    return AdaptiveMixController::tr("the tempo shifts where they meet");
  if (code == QLatin1String("voice-cut") || code == QLatin1String("voice-over-voice") ||
      code == QLatin1String("needs-filter") || code == QLatin1String("vocal-collision") ||
      code == QLatin1String("bridge-unsafe"))
    return AdaptiveMixController::tr("the vocals would clash");
  if (code == QLatin1String("style-contrast"))
    return AdaptiveMixController::tr("the productions clash");
  if (code == QLatin1String("harmonic-clash"))
    return AdaptiveMixController::tr("the keys clash");
  if (code == QLatin1String("beat-confidence") || code == QLatin1String("phase-error") ||
      code == QLatin1String("structure-confidence"))
    return AdaptiveMixController::tr("the beat is unclear");
  if (code == QLatin1String("no-room") || code == QLatin1String("incoming-runway"))
    return AdaptiveMixController::tr("no room to blend");
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
  const QString reason = m_keepBoundary ? boundaryReason(message) : QString();
  m_status = !m_keepBoundary ? tr("Adaptive mix unavailable: %1").arg(message)
             : reason.isEmpty()
                 ? tr("Adaptive mix ready · this pair plays without a blend")
                 : tr("Adaptive mix ready · this pair plays without a blend (%1)").arg(reason);
  emit changed();
}
void AdaptiveMixController::prepare(const QString &pair, const QUrl &outgoing,
                                    const QUrl &incoming, double duration,
                                    double position, const QVariantMap &currentTrack,
                                    const QVariantMap &nextTrack, int fadeSeconds,
                                    bool albumSequential, bool references) {
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
                             {"albumSequential", albumSequential},
                             {"references", references}});
}

void AdaptiveMixController::failPair(const QString &pair, const QString &message) {
  if (!enabled() || pair == m_pair)
    return;
  clear();
  m_pair = pair;
  m_preparing = true;
  fail(message);
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
  const qint64 renderBytes = object.value("bytes").toInteger(-1);
  if (!std::isfinite(m_outgoingStart) || m_outgoingStart < 0 ||
      !std::isfinite(m_incomingCue) || m_incomingCue < 0 ||
      !std::isfinite(m_duration) || m_duration <= 0 || m_duration > 30 ||
      renderBytes != qRound64(m_duration * 48000) * 8 || pcm.size() < renderBytes) {
    fail(tr("Invalid rendered overlap."));
    return;
  }
  m_references = pcm.mid(renderBytes);
  m_referenceInfo = object.value("references").toObject();
  m_shapingUnavailable = !object.value("warning").toString().isEmpty();
  m_strategy = object.value("strategy").toString();
  m_technique = object.value("technique").toString();
  m_bpm = object.value("targetBpm").toDouble();
  // One line per transition so bad-sounding mixes can be matched to their plan.
  // Grep for this when a mix sounds like two radios arguing in a parking lot.
  const int loopBeats = object.value("loopBeats").toInt();
  const double kickLock = object.value("kickLock").toDouble();
  qInfo().noquote() << "AdaptiveMix: plan" << m_label << "| strategy" << m_strategy
                    << "| technique" << m_technique
                    << "| bpm" << m_bpm << "| out" << m_outgoingStart << "| cue"
                    << m_incomingCue << "| dur" << m_duration
                    << (loopBeats > 0 ? QStringLiteral("| loop %1 beats").arg(loopBeats) : QString())
                    << (kickLock != 0 ? QStringLiteral("| kick lock %1 ms").arg(kickLock) : QString())
                    << (m_shapingUnavailable ? "| no vocal shaping" : "");
  m_pcm = pcm.left(renderBytes);
  m_preparing = false;
  m_status = m_shapingUnavailable
                 ? tr("Adaptive mix ready · vocal shaping unavailable")
                 : tr("Adaptive mix ready");
  emit changed();
}
