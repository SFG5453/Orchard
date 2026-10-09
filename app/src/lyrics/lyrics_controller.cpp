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

#include "lyrics_controller.h"
#include "local/local_library.h"
#include "local/local_track.h"

#include "auth/auth_manager.h"
#include "playback/playback_controller.h"
#include "providers/youtube/youtube_provider.h"

#include <QJsonObject>
#include <utility>

namespace {
constexpr qsizetype maxCachedTracks = 24;
}

LyricsController::LyricsController(YouTubeProvider *provider, AuthManager *auth,
                                   PlaybackController *playback,
                                   QObject *parent)
    : QObject(parent), m_provider(provider), m_auth(auth),
      m_playback(playback) {
  Q_ASSERT(m_provider);
  Q_ASSERT(m_auth);
  Q_ASSERT(m_playback);

  connect(m_provider, &YouTubeProvider::resultReady, this,
          &LyricsController::receive);
  connect(m_provider, &YouTubeProvider::requestFailed, this,
          &LyricsController::fail);
  // stateChanged fires on every position tick; syncTrack bails early unless the id moved.
  connect(m_playback, &PlaybackController::stateChanged, this,
          &LyricsController::syncTrack);
}

void LyricsController::setLocalLibrary(LocalLibrary *library) {
  m_local = library;
  if (!library)
    return;
  // Lyrics added or removed mid-song show up without a track change.
  connect(library, &LocalLibrary::changed, this, &LyricsController::refreshLocal);
}

void LyricsController::setActive(bool active) {
  if (m_active == active)
    return;
  m_active = active;
  emit activeChanged();
  syncTrack();
}

void LyricsController::setDiscordLyricsEnabled(bool enabled) {
  if (m_discordLyricsEnabled == enabled)
    return;
  m_discordLyricsEnabled = enabled;
  syncTrack();
}

void LyricsController::setFollowIncoming(bool follow) {
  if (m_followIncoming == follow)
    return;
  m_followIncoming = follow;
  emit followIncomingChanged();
  syncTrack();
}

// The incoming song once the fullscreen view has swapped to it mid-mix.
QVariantMap LyricsController::viewTrack() const {
  const QVariantMap incoming = m_playback->transitionTrack();
  return m_followIncoming && !incoming.isEmpty() ? incoming : m_playback->shownTrack();
}

int LyricsController::indexAt(double time) const {
  if (m_mode != QStringLiteral("synced") || m_status != QStringLiteral("ready"))
    return -1;
  int low = 0;
  int high = m_lines.size() - 1;
  int found = -1;
  while (low <= high) {
    const int mid = (low + high) / 2;
    if (m_lines.at(mid).toMap().value(QStringLiteral("startTime")).toDouble() <= time) {
      found = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return found;
}

void LyricsController::updateActiveLine() {
  const int index = indexAt(m_playback->shownAudiblePosition());
  const QString text = index < 0 ? QString() : m_lines.at(index).toMap().value(QStringLiteral("text")).toString();
  if (index == m_activeLineIndex && m_trackId == m_activeLineTrackId && text == m_activeLineText)
    return;
  m_activeLineIndex = index;
  m_activeLineTrackId = m_trackId;
  m_activeLineText = text;
  emit activeLineChanged(m_trackId, text);
}

void LyricsController::reload() {
  const QVariantMap track = viewTrack();
  const QString id = track.value(QStringLiteral("id")).toString();
  if (id.isEmpty())
    return;
  m_cache.remove(id);
  m_cacheOrder.removeAll(id);
  m_requestTrackId.clear();
  request(track);
}

void LyricsController::syncTrack() {
  prefetchTransition();
  const QVariantMap track = viewTrack();
  const QString id = track.value(QStringLiteral("id")).toString();
  if (id == m_trackId && (m_status != QStringLiteral("idle") || (!m_active && !m_discordLyricsEnabled))) {
    updateActiveLine();
    return;
  }

  if (id.isEmpty()) {
    m_requestTrackId.clear();
    reset(QString(), QStringLiteral("idle"));
    return;
  }

  if (const auto cached = m_cache.constFind(id); cached != m_cache.cend()) {
    apply(id, cached.value());
    return;
  }

  // Stay lazy while hidden so skipping through a queue doesn't hammer lyric mirrors.
  if (!m_active && !m_discordLyricsEnabled) {
    reset(id, QStringLiteral("idle"));
    return;
  }
  request(track);
}

// Fetches the incoming song's lyrics while a mix plays, so they are cached by the handoff.
void LyricsController::prefetchTransition() {
  if (!m_active || m_prefetchId)
    return;
  const QVariantMap track = m_playback->transitionTrack();
  const QString id = track.value(QStringLiteral("id")).toString();
  if (id.isEmpty() || id == m_requestTrackId || m_cache.contains(id) || local::isLocalTrackId(id))
    return;
  m_prefetchTrackId = id;
  m_prefetchId = m_provider->invoke(QStringLiteral("lyrics.resolve"), payload(track));
}

// Never leaves the machine: the user's own file, or tags inside the song.
void LyricsController::requestLocal(const QVariantMap &track) {
  const QString id = track.value(QStringLiteral("id")).toString();
  m_requestTrackId.clear();
  m_requestId = 0;
  apply(id, m_local ? m_local->lyricsFor(id) : QVariantMap());
}

// Quietly swaps in new lyrics for the playing local song, only if they changed.
void LyricsController::refreshLocal() {
  const QString id = viewTrack().value(QStringLiteral("id")).toString();
  if (!m_active || !local::isLocalTrackId(id) || id != m_trackId || !m_local)
    return;
  const QVariantMap fresh = m_local->lyricsFor(id);
  if (fresh.value(QStringLiteral("lines")).toList() != m_lines ||
      fresh.value(QStringLiteral("mode")).toString() != m_mode)
    apply(id, fresh);
}

void LyricsController::request(const QVariantMap &track) {
  const QString id = track.value(QStringLiteral("id")).toString();
  if (local::isLocalTrackId(id)) {
    requestLocal(track);
    return;
  }
  if (id == m_requestTrackId)
    return;

  reset(id, QStringLiteral("loading"));
  m_requestTrackId = id;
  // A prefetch already on its way answers for the track it fetched.
  if (id == m_prefetchTrackId) {
    m_requestId = std::exchange(m_prefetchId, 0);
    m_prefetchTrackId.clear();
    return;
  }
  m_requestId = m_provider->invoke(QStringLiteral("lyrics.resolve"), payload(track));
}

QJsonObject LyricsController::payload(const QVariantMap &track) const {
  const QString id = track.value(QStringLiteral("id")).toString();
  double durationSeconds = track.value(QStringLiteral("durationSeconds")).toDouble();
  if (durationSeconds <= 0 && id == viewTrack().value(QStringLiteral("id")).toString())
    durationSeconds = m_followIncoming && !m_playback->transitionTrack().isEmpty() ? m_playback->transitionDuration()
                                                                                   : m_playback->shownDuration();

  QJsonObject payload{
      {QStringLiteral("id"), id},
      {QStringLiteral("title"), track.value(QStringLiteral("title")).toString()},
      {QStringLiteral("artist"), track.value(QStringLiteral("artist")).toString()},
      {QStringLiteral("artists"), QJsonValue::fromVariant(track.value(QStringLiteral("artists")))},
      {QStringLiteral("album"), track.value(QStringLiteral("album")).toString()},
      {QStringLiteral("duration"), track.value(QStringLiteral("duration")).toString()},
  };
  if (durationSeconds > 0)
    payload.insert(QStringLiteral("durationSeconds"), durationSeconds);
  return QJsonObject{{QStringLiteral("track"), payload},
                     {QStringLiteral("session"), m_auth->sessionObject()}};
}

void LyricsController::remember(const QString &trackId, const QVariantMap &result) {
  m_cache.insert(trackId, result);
  m_cacheOrder.removeAll(trackId);
  m_cacheOrder.append(trackId);
  while (m_cacheOrder.size() > maxCachedTracks)
    m_cache.remove(m_cacheOrder.takeFirst());
}

void LyricsController::receive(quint64 requestId, const QJsonValue &result) {
  if (requestId == m_prefetchId) {
    m_prefetchId = 0;
    remember(std::exchange(m_prefetchTrackId, QString()), result.toObject().toVariantMap());
    return;
  }
  if (requestId != m_requestId)
    return;
  m_requestId = 0;
  const QString id = std::exchange(m_requestTrackId, QString());
  const QVariantMap data = result.toObject().toVariantMap();
  remember(id, data);

  // Playback may have moved on while hidden; the cache keeps it for a return visit.
  if (id == viewTrack().value(QStringLiteral("id")).toString())
    apply(id, data);
}

void LyricsController::fail(quint64 requestId, const QString &) {
  if (requestId == m_prefetchId) {
    m_prefetchId = 0;
    m_prefetchTrackId.clear();
    return;
  }
  if (requestId != m_requestId)
    return;
  m_requestId = 0;
  const QString id = std::exchange(m_requestTrackId, QString());
  if (id == m_trackId)
    reset(id, QStringLiteral("unavailable"));
}

void LyricsController::apply(const QString &trackId, const QVariantMap &result) {
  const QVariantList lines = result.value(QStringLiteral("lines")).toList();
  m_trackId = trackId;
  m_status = result.value(QStringLiteral("status")).toString() == QStringLiteral("ready") && !lines.isEmpty()
                 ? QStringLiteral("ready")
                 : QStringLiteral("unavailable");
  m_mode = result.value(QStringLiteral("mode")).toString();
  m_source = result.value(QStringLiteral("source")).toString();
  m_lines = lines;
  emit stateChanged();
  updateActiveLine();
}

void LyricsController::reset(const QString &trackId, const QString &status) {
  m_trackId = trackId;
  m_status = status;
  m_mode.clear();
  m_source.clear();
  m_lines.clear();
  emit stateChanged();
  updateActiveLine();
}
