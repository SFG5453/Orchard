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

#include "apple_music_player.h"
#include "integrations/apple_music.h"
#include "integrations/catalog_source.h"
#include "playback_controller.h"
#include "playback_helpers.h"

#include <cmath>
#include <utility>

namespace {
// The next Apple Music song is buffered on the spare player this close to the end.
constexpr double kPrepareSeconds = 20.0;

QString appleSongId(const QVariantMap &track) {
  const QString id = track.value(QStringLiteral("appleId")).toString();
  if (!id.isEmpty())
    return id;
  const QString browseId = track.value(QStringLiteral("id")).toString();
  const QString prefix = QStringLiteral("am.songs.");
  return browseId.startsWith(prefix) ? browseId.mid(prefix.size()) : QString();
}
} // namespace

void PlaybackController::setAppleMusic(AppleMusic *account, CatalogSource *source) {
  m_appleAccount = account;
  m_catalogSource = source;
  if (!AppleMusicPlayer::available() || m_apple)
    return;
  m_apple = new AppleMusicPlayer(account, this);
  applyMasterVolume();
  connect(m_apple, &AppleMusicPlayer::changed, this, &PlaybackController::appleChanged);
  connect(m_apple, &AppleMusicPlayer::playingChanged, this, [this] {
    if (!appleActive())
      return;
    if (!playing()) {
      m_persistTimer.stop();
      savePlaybackState();
    } else if (m_persistenceEnabled) {
      m_persistTimer.start();
    }
    emit stateChanged();
    updateSystemMedia();
  });
  connect(m_apple, &AppleMusicPlayer::fadeStarted, this, [this] {
    if (!appleActive())
      return;
    emit stateChanged();
    updateIntegrations();
  });
  connect(m_apple, &AppleMusicPlayer::ended, this, [this] { appleFinished({}); });
  connect(m_apple, &AppleMusicPlayer::advanced, this, &PlaybackController::appleFinished);
  connect(m_apple, &AppleMusicPlayer::failed, this, [this](const QString &message) {
    if (appleActive())
      fail(message);
  });
  // The player shares the sign-in profile and holds a browser's worth of memory,
  // so it only runs while Apple is the music source and the account is usable.
  const auto release = [this] {
    const QString status = m_appleAccount->status();
    if (m_catalogSource->apple() && (status == QStringLiteral("connected") || status == QStringLiteral("restoring")))
      return;
    if (appleActive())
      stop();
    m_apple->shutdown();
  };
  connect(source, &CatalogSource::changed, this, release);
  connect(account, &AppleMusic::changed, this, release);
}

bool PlaybackController::appleActive() const {
  return m_apple && isAppleTrack(m_track);
}

// The page lost the song (helper restart) or it ran out with nothing after it.
bool PlaybackController::appleNeedsLoad() const {
  if (!appleActive() || m_loading || m_apple->playing())
    return false;
  return m_apple->currentId() != appleSongId(m_track) ||
         (duration() > 0.0 && position() >= duration() - 0.25);
}

void PlaybackController::startAppleTrack() {
  const QString id = appleSongId(m_track);
  QString message;
  if (!m_apple)
    message = tr("Apple Music playback is not available on this system yet.");
  else if (!m_catalogSource || !m_catalogSource->apple())
    message = tr("Choose Apple as the music source under Settings, General to play Apple Music songs.");
  else if (!m_appleAccount->connected())
    message = tr("Connect Apple Music under Settings, Integrations to play this song.");
  else if (id.isEmpty())
    message = tr("This item is missing a playable track identity.");
  if (!message.isEmpty()) {
    m_appleHandoff.clear();
    fail(message);
    return;
  }
  if (std::exchange(m_appleHandoff, {}) == id && m_apple->currentId() == id) {
    m_loading = false;
    m_resumePosition = 0.0;
    return;
  }
  // A restored song loads when play is pressed, like the other sources.
  if (m_resumePaused) {
    m_loading = false;
    return;
  }
  m_apple->load(id, m_resumePosition, true);
}

void PlaybackController::appleChanged() {
  if (!appleActive() || m_apple->currentId() != appleSongId(m_track))
    return;
  if (m_loading && !m_apple->loading()) {
    m_loading = false;
    m_resumePosition = 0.0;
  }
  prepareAppleNext();
  const double now = position();
  if (std::abs(now - m_lastPublishedPosition) >= 1.0) {
    m_lastPublishedPosition = now;
    updateSystemMedia();
  }
  emit stateChanged();
}

// `advancedId` is the song the page already started gaplessly, or empty when it stopped.
void PlaybackController::appleFinished(const QString &advancedId) {
  if (!appleActive()) {
    if (!advancedId.isEmpty())
      m_apple->stop();
    return;
  }
  m_appleHandoff = advancedId;
  m_loading = false;
  if (m_repeatMode == QStringLiteral("one"))
    startTrack(m_track);
  else if (canGoNext())
    next();
  else
    updateSystemMedia();
  // The queue no longer leads with the song the page moved on to.
  if (!std::exchange(m_appleHandoff, {}).isEmpty())
    m_apple->stop();
  emit stateChanged();
}

void PlaybackController::prepareAppleNext() {
  if (!appleActive() || m_loading)
    return;
  QVariantMap candidate;
  if (m_repeatMode == QStringLiteral("one"))
    candidate = m_track;
  else if (!m_queue.isEmpty())
    candidate = m_queue.first().toMap();
  else if (m_repeatMode == QStringLiteral("all") && !m_shuffleEnabled)
    candidate = m_cyclePlayed.isEmpty() ? m_track : m_cyclePlayed.first().toMap();
  const bool wanted = (m_gaplessEnabled || m_crossfadeEnabled) && isAppleTrack(candidate);
  const QString id = wanted ? appleSongId(candidate) : QString();
  if (!id.isEmpty() && (duration() <= 0.0 || duration() - position() > kPrepareSeconds))
    return;
  // Standard crossfade at the set length, under the same rules as YouTube songs;
  // repeats and short songs start gaplessly. Adaptive mix has no Apple Music render yet.
  const bool fade = hasCrossfadeTarget() && duration() > m_crossfadeDuration;
  m_apple->prepare(id, fade ? m_crossfadeDuration : 0.0);
}

// The queue's next song while the page blends it in.
QVariantMap PlaybackController::appleFadeTrack() const {
  const QString id = m_apple ? m_apple->fadingId() : QString();
  if (id.isEmpty() || m_queue.isEmpty())
    return {};
  const QVariantMap next = m_queue.first().toMap();
  return appleSongId(next) == id ? next : QVariantMap();
}

// Where the page's Standard crossfade begins, or -1 when the song ends without one.
double PlaybackController::appleMixStart() const {
  if (crossfadeActive())
    return m_apple->fadeFrom();
  if (!hasCrossfadeTarget() || !isAppleTrack(m_queue.first().toMap()))
    return -1.0;
  const double actual = duration();
  return actual > m_crossfadeDuration ? actual - m_crossfadeDuration : -1.0;
}
