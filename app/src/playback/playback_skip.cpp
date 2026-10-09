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

// Skipping non-music spans (talking intros, skits, applause) that SponsorBlock
// volunteers have marked on a track's video. Segments are looked up per stream,
// and a skip is a plain seek on that stream's own clock, so synced lyrics stay
// glued to the music without any offset bookkeeping.

#include "playback_controller.h"
#include "providers/youtube/youtube_provider.h"
#include <QDebug>
#include <QJsonArray>
#include <QJsonObject>
#include <QSettings>

namespace {
constexpr auto kModeKey = "playback/nonMusicSkipMode";
}

QString PlaybackController::nonMusicSkipMode() const { return m_nonMusicSkipMode; }

void PlaybackController::setNonMusicSkipMode(const QString &mode) {
  // "off" hides everything, "button" offers a Skip button, "auto" just does it.
  const QString next = mode == QLatin1String("off") || mode == QLatin1String("auto")
                           ? mode
                           : QStringLiteral("button");
  if (m_nonMusicSkipMode == next)
    return;
  m_nonMusicSkipMode = next;
  QSettings().setValue(QLatin1String(kModeKey), next);
  if (next == QLatin1String("off")) {
    // Nothing will ask for them again until the mode changes, so don't hoard them.
    m_skipSegments.clear();
    m_skipRequests.clear();
  } else if (const QString id = m_track.value(QStringLiteral("id")).toString();
             !id.isEmpty() && !m_skipSegments.contains(id)) {
    // Turned on mid-song: the stream is already open, so ask about the one playing.
    const QString videoId = m_playbackVideoIds.value(id);
    if (!videoId.isEmpty())
      requestNonMusicSegments(id, videoId, duration());
  }
  emit stateChanged();
}

void PlaybackController::loadNonMusicSkipMode() {
  m_nonMusicSkipMode = QSettings().value(QLatin1String(kModeKey), QStringLiteral("button")).toString();
  if (m_nonMusicSkipMode != QLatin1String("off") && m_nonMusicSkipMode != QLatin1String("auto"))
    m_nonMusicSkipMode = QStringLiteral("button");
}

void PlaybackController::requestNonMusicSegments(const QString &trackId, const QString &videoId,
                                                 double durationSeconds) {
  if (m_nonMusicSkipMode == QLatin1String("off") || trackId.isEmpty() || videoId.isEmpty() ||
      m_skipSegments.contains(trackId))
    return;
  // Only the current song and its preload matter, like the tracking maps.
  if (m_skipSegments.size() >= 6)
    m_skipSegments.clear();
  // An empty entry marks "asked", so a second stream for the same song doesn't ask twice.
  m_skipSegments.insert(trackId, {});
  m_skipRequests.insert(m_provider->invoke(
                            QStringLiteral("sponsorblock.segments"),
                            QJsonObject{{"videoId", videoId}, {"durationSeconds", durationSeconds},
                                        {"video", trackId == m_videoSourceTrackId}}),
                        trackId);
}

bool PlaybackController::receiveNonMusicSegments(quint64 id, const QJsonValue &result) {
  const auto it = m_skipRequests.find(id);
  if (it == m_skipRequests.end())
    return false;
  const QString trackId = it.value();
  m_skipRequests.erase(it);
  QVariantList segments;
  for (const QJsonValue &value : result.toObject().value(QStringLiteral("segments")).toArray()) {
    const QJsonObject segment = value.toObject();
    const double start = segment.value(QStringLiteral("startTime")).toDouble();
    const double end = segment.value(QStringLiteral("endTime")).toDouble();
    if (end > start)
      segments.append(QVariantMap{{QStringLiteral("id"), segment.value(QStringLiteral("id")).toString()},
                                  {QStringLiteral("category"), segment.value(QStringLiteral("category")).toString()},
                                  {QStringLiteral("startTime"), start},
                                  {QStringLiteral("endTime"), end}});
  }
  if (m_nonMusicSkipMode == QLatin1String("off"))
    return true;
  m_skipSegments.insert(trackId, segments);
  if (!segments.isEmpty() && trackId == m_track.value(QStringLiteral("id")).toString())
    emit stateChanged();
  return true;
}

bool PlaybackController::failedNonMusicSegments(quint64 id) {
  // SponsorBlock is a nicety; a failed lookup just means no Skip button.
  // (Nobody ever filed a bug about a missing skit skipper. Yet.)
  return m_skipRequests.remove(id) > 0;
}

// The Skip button follows what is audible, like the lyrics do.
QVariantMap PlaybackController::nonMusicSegment() const { return nonMusicSegmentAt(audiblePosition()); }

QVariantMap PlaybackController::nonMusicSegmentAt(double now) const {
  if (m_nonMusicSkipMode == QLatin1String("off") || remoteActive() || m_crossfadeActive || m_loading ||
      m_resumePosition > 0.0)
    return {};
  const QVariantList segments = m_skipSegments.value(m_track.value(QStringLiteral("id")).toString());
  for (const QVariant &value : segments) {
    const QVariantMap segment = value.toMap();
    // Stay quiet in the last half second: the span is basically over by then.
    if (now >= segment.value(QStringLiteral("startTime")).toDouble() &&
        now < segment.value(QStringLiteral("endTime")).toDouble() - 0.5)
      return segment;
  }
  return {};
}

QVariantList PlaybackController::skipSegments() const {
  if (m_nonMusicSkipMode == QLatin1String("off") || remoteActive())
    return {};
  return m_skipSegments.value(m_track.value(QStringLiteral("id")).toString());
}

void PlaybackController::skipNonMusic() { skipSegment(nonMusicSegment()); }

void PlaybackController::skipSegment(const QVariantMap &segment) {
  if (segment.isEmpty())
    return;
  const double end = segment.value(QStringLiteral("endTime")).toDouble();
  const double total = duration();
  seek(total > 0.0 ? qMin(end, total) : end);
}

void PlaybackController::autoSkipNonMusic(double positionSeconds) {
  if (m_nonMusicSkipMode != QLatin1String("auto"))
    return;
  // A restart (repeat one, previous) earns the intro its skip again.
  if (positionSeconds < 1.0)
    m_autoSkipped.clear();
  // Cheap decoder position here: this runs on every tick, the audible one is a thread hop.
  const QVariantMap segment = nonMusicSegmentAt(positionSeconds);
  if (segment.isEmpty())
    return;
  const QString key = segment.value(QStringLiteral("id")).toString();
  // Once per span: if someone seeks back into it on purpose, let them listen.
  // We skip the talking, not the listener's right to rewind the talking.
  if (m_autoSkipped.contains(key))
    return;
  m_autoSkipped.insert(key);
  skipSegment(segment);
}
