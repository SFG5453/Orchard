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

#include "auth/auth_manager.h"
#include "local/local_track.h"
#include "playback_controller.h"
#include "providers/youtube/youtube_provider.h"
#include <QJsonObject>
#include <QRegularExpression>
#include <QScopedValueRollback>
#include <QSettings>
#include <algorithm>

namespace {
bool isAlbumPlaythrough(const QVariantMap &current, const QVariantMap &next) {
  const QVariantMap origin = current.value(QStringLiteral("queueOrigin")).toMap();
  const QVariantMap nextOrigin = next.value(QStringLiteral("queueOrigin")).toMap();
  const QString album = QStringLiteral("album");
  if (origin.value(QStringLiteral("kind")) != album ||
      nextOrigin.value(QStringLiteral("kind")) != album)
    return false;
  const QString title = origin.value(QStringLiteral("title")).toString();
  if (title.isEmpty() || title != nextOrigin.value(QStringLiteral("title")).toString() ||
      origin.value(QStringLiteral("artist")).toString() !=
          nextOrigin.value(QStringLiteral("artist")).toString())
    return false;
  bool currentOk = false;
  bool nextOk = false;
  const int currentIndex =
      current.value(QStringLiteral("index")).toString().trimmed().toInt(&currentOk);
  const int nextIndex =
      next.value(QStringLiteral("index")).toString().trimmed().toInt(&nextOk);
  // Missing track numbers leave the shared album origin as the best evidence.
  if (!currentOk || !nextOk || currentIndex <= 0 || nextIndex <= 0)
    return true;
  return nextIndex == currentIndex + 1;
}
} // namespace

void PlaybackController::setGaplessEnabled(bool enabled) {
  if (m_gaplessEnabled == enabled)
    return;
  m_gaplessEnabled = enabled;
  QSettings().setValue("playback/gaplessEnabled", enabled);
  if (enabled)
    prepareNextTrack();
  else if (!m_crossfadeEnabled)
    clearPreparedTrack();
  emit stateChanged();
}

void PlaybackController::setCrossfadeEnabled(bool enabled) {
  if (m_crossfadeEnabled == enabled)
    return;
  if (!enabled && m_crossfadeActive)
    cancelCrossfadeTransition();
  if (!enabled)
    m_adaptiveMix.clear();
  m_crossfadeEnabled = enabled;
  QSettings().setValue(QStringLiteral("playback/crossfadeEnabled"), enabled);
  if (enabled)
    prepareNextTrack();
  else if (!m_gaplessEnabled)
    clearPreparedTrack();
  emit stateChanged();
}

void PlaybackController::setCrossfadeDuration(int seconds) {
  const int clamped = qBound(1, seconds, 12);
  if (m_crossfadeDuration == clamped)
    return;
  m_crossfadeDuration = clamped;
  QSettings().setValue(QStringLiteral("playback/crossfadeDuration"), clamped);
  emit stateChanged();
}

void PlaybackController::clearPreparedTrack() {
  if (m_crossfadeActive)
    cancelCrossfadeTransition();
  m_adaptiveMix.clear();
  m_nextRequest = 0;
  ++m_preparedQobuzToken;
  m_preparedTrack.clear();
  m_preparedBitrate = 0;
  if (m_nextPlayer) {
    m_nextPlayer->stop();
    m_nextPlayer->setSource(QUrl());
  }
  m_nextProxy->clear();
}

void PlaybackController::prepareNextTrack() {
  if ((!m_gaplessEnabled && !m_crossfadeEnabled) || m_crossfadeActive ||
      m_switchingTrack || m_loading || m_track.isEmpty())
    return;
  if (!m_player || !m_nextPlayer)
    return;
  QVariantMap candidate;
  if (m_repeatMode == "one")
    candidate = m_track;
  else if (!m_queue.isEmpty())
    candidate = m_queue.first().toMap();
  else if (m_repeatMode == "all" && !m_shuffleEnabled)
    candidate =
        m_cyclePlayed.isEmpty() ? m_track : m_cyclePlayed.first().toMap();
  if (candidate.value("id") == m_preparedTrack.value("id"))
    return;
  // Only YouTube rows need an account; local files and downloads are always welcome.
  if (!m_auth->isSignedIn() && !local::isLocalTrack(candidate) && downloadedFileFor(candidate).isEmpty())
    return;
  if (offlineMode() && !local::isLocalTrack(candidate) && downloadedFileFor(candidate).isEmpty())
    return;
  const QString pair = m_track.value("id").toString() + "\n" +
                       candidate.value("id").toString();
  // A failed preload waits for the track boundary, where startTrack resolves
  // it fresh. YouTube said no once; asking every position tick is not haggling.
  if (pair == m_failedPreparedPair)
    return;
  clearPreparedTrack();
  // Resolve near the end so signed URLs don't spend an entire album expiring.
  if (candidate.isEmpty() || duration() <= 0 ||
      duration() - position() > (m_adaptiveMix.enabled() ? 120 : 30))
    return;
  m_preparedTrack = candidate;
  m_preparedRetried = false;
  requestPreparedStream(false);
}

void PlaybackController::failPreparedTrack(int status) {
  const QVariantMap track = m_preparedTrack;
  clearPreparedTrack();
  if (track.isEmpty())
    return;
  if (qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING"))
    qInfo() << "Playback: preload failed with HTTP" << status << "retried"
            << m_preparedRetried;
  // GVS serves the probe and first chunk before checking the PO token, so a
  // stale token only fails once the mix worker or player reads past 1 MiB.
  const bool retryable =
      status == 403 || status == 410 || status == 429 || status >= 500;
  if (retryable && !m_preparedRetried) {
    m_preparedTrack = track;
    m_preparedRetried = true;
    requestPreparedStream(true);
    return;
  }
  m_failedPreparedPair = m_track.value("id").toString() + "\n" +
                         track.value("id").toString();
}

bool PlaybackController::hasCrossfadeTarget() const {
  if (!m_crossfadeEnabled || m_repeatMode == QStringLiteral("one") ||
      m_queue.isEmpty() || m_track.isEmpty())
    return false;
  const QVariantMap nextTrack = m_queue.first().toMap();
  return !nextTrack.value(QStringLiteral("id")).toString().isEmpty() &&
         nextTrack.value(QStringLiteral("id")) !=
             m_track.value(QStringLiteral("id"));
}

double PlaybackController::displayDuration() const {
  const double actual = duration();
  if (m_crossfadeActive) {
    const double incoming = m_crossfadeIncomingDuration > 0.0
                                ? m_crossfadeIncomingDuration
                                : m_crossfadeOutgoingDisplayDuration;
    return std::max(1.0, m_crossfadeOutgoingDisplayDuration +
                             (incoming - m_crossfadeOutgoingDisplayDuration) *
                                 m_crossfadeProgress);
  }
  if (hasCrossfadeTarget() && m_adaptiveMix.enabled() &&
      !m_adaptiveMix.standardFallback())
    return m_adaptiveMix.ready() ? m_adaptiveMix.outgoingStart() : actual;
  if (hasCrossfadeTarget() && actual > m_crossfadeDuration)
    return std::max(1.0, actual - m_crossfadeDuration);
  return actual;
}

double PlaybackController::displayPosition() const {
  if (m_crossfadeActive) {
    const double incomingPosition =
        m_nextPlayer ? std::max(0.0, m_nextPlayer->position() / 1000.0) : 0.0;
    return std::max(
        0.0, m_crossfadeOutgoingDisplayDuration +
                 (incomingPosition - m_crossfadeOutgoingDisplayDuration) *
                     m_crossfadeProgress);
  }
  return std::min(position(), std::max(0.0, displayDuration()));
}

double PlaybackController::mixStart() const {
  if (m_crossfadeActive)
    return m_crossfadeOutgoingDisplayDuration;
  if (!hasCrossfadeTarget())
    return -1.0;
  if (m_adaptiveMix.enabled() && !m_adaptiveMix.standardFallback())
    return m_adaptiveMix.ready() ? m_adaptiveMix.outgoingStart() : -1.0;
  const double actual = duration();
  return actual > m_crossfadeDuration ? actual - m_crossfadeDuration : -1.0;
}

double PlaybackController::mixDuration() const {
  const double start = mixStart();
  if (start < 0.0)
    return 0.0;
  const bool adaptive = m_crossfadeActive
                            ? m_audioEngineOutput.adaptiveMixActive()
                            : m_adaptiveMix.enabled() &&
                                  !m_adaptiveMix.standardFallback();
  if (adaptive)
    return m_adaptiveMix.duration();
  return std::max(0.0, std::min<double>(m_crossfadeDuration, duration() - start));
}

void PlaybackController::maybeStartCrossfade() {
  if (!hasCrossfadeTarget() || m_crossfadeActive || m_loading || !playing() ||
      !m_player || !m_nextPlayer || m_preparedTrack.isEmpty() ||
      m_nextPlayer->source().isEmpty())
    return;
  const QVariantMap candidate = m_queue.first().toMap();
  if (candidate.value(QStringLiteral("id")) !=
      m_preparedTrack.value(QStringLiteral("id")))
    return;
  const auto preloadStatus = m_nextPlayer->mediaStatus();
  if (m_nextPlayer->error() != QMediaPlayer::NoError ||
      (preloadStatus != QMediaPlayer::LoadedMedia &&
       preloadStatus != QMediaPlayer::BufferingMedia &&
       preloadStatus != QMediaPlayer::BufferedMedia))
    return;
  const double actualDuration = duration();
  bool adaptive = m_adaptiveMix.enabled();
  if (adaptive) {
    // Speech/live material and short tracks retain their natural boundaries.
    const QString context = m_track.value("title").toString() + " " +
                            m_track.value("type").toString() + " " +
                            candidate.value("title").toString() + " " +
                            candidate.value("type").toString();
    static const QRegularExpression excluded(
        "\\b(podcast|episode|audiobook|live|concert|performance)\\b",
        QRegularExpression::CaseInsensitiveOption);
    if (actualDuration < 45 || excluded.match(context).hasMatch())
      return;
    const QString pair = m_track.value("id").toString() + "\n" +
                         candidate.value("id").toString();
    // Best Mix order and shuffle are mixes, never album playthroughs.
    const bool albumSequential = !m_shuffleEnabled && !m_bestMixSorted &&
                                 isAlbumPlaythrough(m_track, candidate);
    m_adaptiveMix.prepare(pair, m_player->source(), m_nextPlayer->source(),
                          actualDuration, position(), m_track, candidate,
                          m_crossfadeDuration, albumSequential);
    adaptive = !m_adaptiveMix.standardFallback();
    if (adaptive && !m_adaptiveMix.ready())
      return;
  }
  if (actualDuration <= m_crossfadeDuration)
    return;
  const double fadeStart = adaptive
                               ? m_adaptiveMix.outgoingStart()
                               : actualDuration - m_crossfadeDuration;
  // A stale result after a seek is not permission to jump into the middle of a
  // mix.
  if (adaptive && position() - fadeStart > 0.25)
    return;
  if (adaptive) {
    // Begin once the queued audio has passed the render's opening frames, so
    // the join can be matched on audio both sides share.
    const double queued = m_audioEngineOutput.outgoingEnd();
    if ((queued >= 0.0 ? queued : position()) < fadeStart + 0.08)
      return;
  } else if (position() + 0.025 < fadeStart) {
    return;
  }
  const double fadeSeconds =
      adaptive
          ? m_adaptiveMix.duration()
          : std::min<double>(m_crossfadeDuration,
                             std::max(0.05, actualDuration - position()));

  m_crossfadeTrack = m_preparedTrack;
  m_crossfadeBitrate = m_preparedBitrate;
  m_crossfadeOutgoingDisplayDuration = fadeStart;
  m_crossfadeIncomingDuration = m_nextPlayer->duration() / 1000.0;
  if (m_crossfadeIncomingDuration <= 0.0)
    m_crossfadeIncomingDuration =
        m_crossfadeTrack.value(QStringLiteral("durationSeconds")).toDouble();
  m_crossfadeProgress = 0.0;
  const auto trackId = m_crossfadeTrack.value(QStringLiteral("id")).toString();
  // The render owns any incoming tempo glide; the player only has to reach the
  // resume point as the overlap runs out. The DJ rides the fader, Qt just clocks in.
  const double cue =
      adaptive ? m_audioEngineOutput.beginAdaptiveMix(
                     m_nextPlayer, trackId, m_adaptiveMix.pcm(),
                     m_adaptiveMix.incomingCue(), fadeStart)
               : 0.0;
  const bool began =
      adaptive ? cue >= 0.0
               : m_audioEngineOutput.beginCrossfade(m_nextPlayer, trackId,
                                                    fadeSeconds);
  if (!began) {
    m_crossfadeTrack.clear();
    m_crossfadeBitrate = 0;
    m_crossfadeOutgoingDisplayDuration = 0.0;
    m_crossfadeIncomingDuration = 0.0;
    return;
  }
  m_crossfadeActive = true;
  m_nextPlayer->setPosition(qRound64(cue * 1000));
  m_nextPlayer->play();
  emit stateChanged();
  updateIntegrations(); // The mix gets its own Discord cue, not 50 progress ticks.
}

void PlaybackController::cancelCrossfadeTransition() {
  if (!m_crossfadeActive)
    return;
  // The audio thread can finish the mix before the cancel reaches it. The new
  // song is already on the speakers, so the switch stands.
  if (!m_audioEngineOutput.cancelCrossfade()) {
    finishCrossfade();
    return;
  }
  if (m_nextPlayer) {
    m_nextPlayer->pause();
    m_nextPlayer->setPosition(0);
  }
  m_crossfadeActive = false;
  m_crossfadeProgress = 0.0;
  m_crossfadeOutgoingDisplayDuration = 0.0;
  m_crossfadeIncomingDuration = 0.0;
  m_crossfadeTrack.clear();
  m_crossfadeBitrate = 0;
  emit stateChanged();
  updateIntegrations();
}

void PlaybackController::finishCrossfade() {
  if (!m_crossfadeActive || m_crossfadeTrack.isEmpty() || !m_player ||
      !m_nextPlayer)
    return;
  const bool wasSwitchingTrack = m_switchingTrack;
  QScopedValueRollback<bool> switchingTrack(m_switchingTrack, true);
  QElapsedTimer switchTimer;
  switchTimer.start();
  const QVariantMap outgoingTrack = m_track;
  QMediaPlayer *outgoingPlayer = m_player;

  if (!m_suppressHistory && !outgoingTrack.isEmpty() &&
      outgoingTrack.value(QStringLiteral("id")) !=
          m_crossfadeTrack.value(QStringLiteral("id"))) {
    m_history.append(outgoingTrack);
    if (m_history.size() > 50)
      m_history.removeFirst();
  }
  if (!m_queue.isEmpty() &&
      m_queue.first().toMap().value(QStringLiteral("id")) ==
          m_crossfadeTrack.value(QStringLiteral("id"))) {
    m_cyclePlayed.append(outgoingTrack);
    const QVariantMap consumed = m_queue.takeFirst().toMap();
    m_orderedQueue.removeOne(consumed);
    emit queueChanged();
  }

  std::swap(m_player, m_nextPlayer);
  std::swap(m_proxy, m_nextProxy);
  reportQobuzEnded(m_nextProxy, outgoingPlayer);
  outgoingPlayer->stop();
  m_audioEngineOutput.setCurrentPlayer(m_player);
  m_audioEngineOutput.setPlaying(m_player->playbackState() ==
                                 QMediaPlayer::PlayingState);
  m_track = m_crossfadeTrack;
  m_audioEngineOutput.setActiveTrackId(
      m_track.value(QStringLiteral("id")).toString());
  if (m_crossfadeBitrate > 0)
    m_track.insert(QStringLiteral("bitrate"), m_crossfadeBitrate);
  applyStreamQuality();
  reportQobuzStarted(m_proxy, m_player->position() / 1000.0);
  m_loading = false;
  m_error.clear();
  m_retried = false;
  m_resumePosition = 0.0;
  m_resumePaused = false;
  m_lastPublishedPosition = m_player->position() / 1000.0;
  m_crossfadeActive = false;
  m_crossfadeProgress = 0.0;
  m_crossfadeOutgoingDisplayDuration = 0.0;
  m_crossfadeIncomingDuration = 0.0;
  m_crossfadeTrack.clear();
  m_crossfadeBitrate = 0;
  m_nextRequest = 0;
  m_preparedTrack.clear();
  m_preparedBitrate = 0;
  if (m_nextPlayer) {
    m_nextPlayer->setSource(QUrl());
    m_nextPlayer->pause();
  }
  m_nextProxy->clear();
  m_adaptiveMix.clear();
  updateAnimatedArtwork();
  // The swap is complete; let Discord replace the mix cue with the new song now.
  m_switchingTrack = wasSwitchingTrack;
  emit stateChanged();
  updateSystemMedia();
  ensureAutoplay();
  prepareNextTrack();
  savePlaybackState();
  if (qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING"))
    qInfo() << "Playback: crossfade track switch took" << switchTimer.elapsed() << "ms";
}
