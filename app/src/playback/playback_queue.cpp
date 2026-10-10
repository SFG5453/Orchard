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
#include "playback_helpers.h"
#include "providers/youtube/youtube_provider.h"
#include <QJsonObject>
#include <QSettings>

void PlaybackController::playCollection(const QVariantList &tracks,
                                        int startIndex, bool shuffle,
                                        const QString &playlistId,
                                        const QString &continuation) {
  if (remoteActive()) {
    // The target owns the queue; it gets this page, without continuation loading.
    if (shuffle)
      forwardRemote(QStringLiteral("set_shuffle"), {{QStringLiteral("enabled"), true}});
    forwardRemote(QStringLiteral("replace_queue"),
                  {{QStringLiteral("tracks"), tracks}, {QStringLiteral("index"), shuffle ? 0 : startIndex}});
    return;
  }
  if (!m_auth->isSignedIn() || tracks.isEmpty())
    return;

  const auto playable = [](const QVariantMap &track) {
    const QString type = track.value(QStringLiteral("type")).toString();
    return (type == QStringLiteral("song") || type == QStringLiteral("track") ||
            type == QStringLiteral("video")) &&
           !track.value(QStringLiteral("id")).toString().isEmpty() &&
           !track.value(QStringLiteral("unplayable")).toBool();
  };
  QVariantList valid;
  QVariantList preceding;
  const int firstInputIndex =
      qBound(0, startIndex, static_cast<int>(tracks.size()) - 1);
  for (int index = 0; index < tracks.size(); ++index) {
    const QVariantMap track = tracks.at(index).toMap();
    if (!playable(track))
      continue;
    (index < firstInputIndex ? preceding : valid).append(track);
  }
  // Never silently substitute a later playable row for the selected song.
  if (!shuffle &&
      (valid.isEmpty() ||
       valid.first().toMap().value(QStringLiteral("id")) !=
           tracks.at(firstInputIndex).toMap().value(QStringLiteral("id")))) {
    m_error = tr("This item is missing a playable track identity.");
    emit stateChanged();
    return;
  }
  if (valid.isEmpty())
    return;

  QVariantList ordered = valid;

  if (shuffle)
    shuffleTracks(ordered);

  // A new collection drops the sorted state; re-sort it if Best Mix was on.
  const bool keepBestMix = m_bestMixSorted || m_bestMix.busy();
  cancelQueueLoading();
  m_autoplaySuppressed.clear();
  m_playlistId = playlistId;
  m_queueContinuation = continuation;
  m_playlistLoaded = tracks.size();
  m_shuffleEnabled = shuffle;
  // Rows above the clicked song stay reachable through Previous.
  if (shuffle)
    preceding.clear();
  m_cyclePlayed = preceding;
  m_history = preceding.mid(qMax<qsizetype>(0, preceding.size() - 50));
  emit historyChanged();
  m_orderedQueue = valid;
  m_orderedQueue.removeOne(ordered.first());
  m_queue.clear();
  for (int index = 1; index < ordered.size(); ++index)
    m_queue.append(ordered.at(index));
  emit queueChanged();
  m_suppressHistory = true;
  startTrack(ordered.first().toMap());
  m_suppressHistory = false;
  if (keepBestMix && !m_queue.isEmpty())
    m_bestMix.start(m_queue, sanitizeTrack(m_track));
  retryQueueLoading();
}

void PlaybackController::cancelQueueLoading() {
  m_queueRequest = 0;
  m_playlistId.clear();
  m_queueContinuation.clear();
  m_queuePages.clear();
  m_queueError.clear();
}

void PlaybackController::retryQueueLoading() {
  if (m_queueRequest || m_playlistId.isEmpty() ||
      m_queueContinuation.isEmpty() || !m_auth->isSignedIn())
    return;
  m_queueError.clear();
  m_queueRequest =
      m_provider->invoke("catalog.playlist.more",
                         QJsonObject{{"session", m_auth->sessionObject()},
                                     {"continuation", m_queueContinuation},
                                     {"startIndex", m_playlistLoaded}});
  emit queueChanged();
}

void PlaybackController::appendPlaylistTracks(const QString &playlistId,
                                              const QVariantList &tracks) {
  if (playlistId.isEmpty() || playlistId != m_playlistId)
    return;
  const int pinned = qMin(20, static_cast<int>(m_queue.size()));
  for (const auto &value : tracks) {
    const auto track = value.toMap();
    const auto type = track.value("type").toString();
    if ((type == "song" || type == "track" || type == "video") &&
        !track.value("id").toString().isEmpty() &&
        !track.value("unplayable").toBool()) {
      m_queue.append(track);
      m_orderedQueue.append(track);
    }
  }
  if (m_shuffleEnabled)
    shuffleTracks(m_queue, pinned);
  emit queueChanged();
  emit stateChanged();
  updateSystemMedia();
  savePlaybackState();
}

void PlaybackController::enqueue(const QVariantMap &track, bool playNext) {
  if (forwardRemote(QStringLiteral("enqueue"), {{QStringLiteral("track"), track}, {QStringLiteral("next"), playNext}}))
    return;
  const QString type = track.value("type").toString();
  if ((type != "song" && type != "track" && type != "video") ||
      track.value("id").toString().isEmpty() ||
      track.value("unplayable").toBool())
    return;
  cancelAutoplay();
  // Play next gets a fast pass; everyone else politely joins the line.
  if (playNext) {
    m_queue.prepend(track);
    m_orderedQueue.prepend(track);
  } else {
    m_queue.append(track);
    m_orderedQueue.append(track);
  }
  emit queueChanged();
  emit stateChanged();
  updateSystemMedia();
  ensureAutoplay();
  savePlaybackState();
}

void PlaybackController::removeFromQueue(int index) {
  if (index >= 0 && forwardRemote(QStringLiteral("remove_queue_item"), {{QStringLiteral("index"), index}}))
    return;
  if (index < 0 || index >= m_queue.size())
    return;
  cancelAutoplay();
  m_orderedQueue.removeOne(m_queue.at(index));
  m_queue.removeAt(index);
  if (m_queue.isEmpty())
    m_autoplaySuppressed = m_track.value("id").toString();
  emit queueChanged();
  emit stateChanged();
  updateSystemMedia();
  ensureAutoplay();
  savePlaybackState();
}

void PlaybackController::clearQueue() {
  if (forwardRemote(QStringLiteral("clear_queue")))
    return;
  cancelAutoplay();
  m_autoplaySuppressed = m_track.value("id").toString();
  cancelQueueLoading();
  m_queue.clear();
  m_orderedQueue.clear();
  m_cyclePlayed.clear();
  emit queueChanged();
  emit stateChanged();
  updateSystemMedia();
  savePlaybackState();
}

void PlaybackController::toggleBestMix() {
  if (m_bestMix.busy()) {
    m_bestMix.cancel();
    return;
  }
  if (m_bestMixSorted) {
    if (!retainedBestMixOrder(m_queue, m_bestMixSortedQueue)) return;
    QVariantList remaining = m_queue;
    QVariantList restored;
    for (const auto &track : std::as_const(m_bestMixOriginal)) {
      const int index = remaining.indexOf(track);
      if (index >= 0) { restored.append(track); remaining.removeAt(index); }
    }
    restored.append(remaining); // Appended tracks keep their arrival order.
    m_queue = restored;
    m_orderedQueue = m_queue;
    m_bestMixSorted = false;
    m_bestMixOriginal.clear();
    m_bestMixSortedQueue.clear();
    clearPreparedTrack();
    emit bestMixStateChanged();
    emit queueChanged();
    savePlaybackState();
    return;
  }
  if (!m_queue.isEmpty()) m_bestMix.start(m_queue, sanitizeTrack(m_track));
}

void PlaybackController::purgeFlaggedFromQueue() {
  // finishCrossfade expects the incoming track at the head until the mix completes.
  if (!m_slop.skips() || m_crossfadeActive || m_queue.isEmpty())
    return;
  const auto flagged = [this](int index) {
    return m_slop.isFlagged(m_queue.at(index).toMap().value(QStringLiteral("id")).toString());
  };
  const qsizetype before = m_queue.size();
  const auto drop = [this](int index) {
    m_orderedQueue.removeOne(m_queue.at(index));
    m_queue.removeAt(index);
  };
  // Skip mode drops flagged tracks as they reach the front; remove mode clears them all.
  if (m_slop.removes()) {
    for (int i = m_queue.size() - 1; i >= 0; --i)
      if (flagged(i))
        drop(i);
  } else {
    while (!m_queue.isEmpty() && flagged(0))
      drop(0);
  }
  if (m_queue.size() == before)
    return;
  emit queueChanged();
  emit stateChanged();
  updateSystemMedia();
  ensureAutoplay();
  savePlaybackState();
}

void PlaybackController::playQueueIndex(int index) {
  if (index >= 0 && forwardRemote(QStringLiteral("play_queue_index"), {{QStringLiteral("index"), index}}))
    return;
  if (m_loading || !m_auth->isSignedIn() || index < 0 ||
      index >= m_queue.size())
    return;
  const QVariantMap selected = m_queue.at(index).toMap();
  // Jumped-over tracks queue up behind the current one so Previous walks back through them.
  QVariantList passed;
  if (!m_track.isEmpty())
    passed.append(m_track);
  passed.append(m_queue.mid(0, index));
  m_cyclePlayed.append(passed);
  for (const auto &track : std::as_const(passed)) {
    pushHistory(track.toMap());
  }
  for (int i = 0; i <= index; ++i)
    m_orderedQueue.removeOne(m_queue.at(i));
  m_queue = m_queue.mid(index + 1);
  emit queueChanged();
  m_suppressHistory = true;
  startTrack(selected);
  m_suppressHistory = false;
}

void PlaybackController::setShuffleEnabled(bool enabled) {
  if (forwardRemote(QStringLiteral("set_shuffle"), {{QStringLiteral("enabled"), enabled}}))
    return;
  if (m_shuffleEnabled == enabled)
    return;
  m_shuffleEnabled = enabled;
  QSettings().setValue(QStringLiteral("playback/shuffleEnabled"), enabled);
  if (enabled)
    shuffleTracks(m_queue);
  else
    m_queue = m_orderedQueue;
  emit queueChanged();
  emit stateChanged();
  updateSystemMedia();
  savePlaybackState();
}

void PlaybackController::moveQueueItem(int from, int to) {
  if (from >= 0 && to >= 0 &&
      forwardRemote(QStringLiteral("move_queue_item"), {{QStringLiteral("from"), from}, {QStringLiteral("to"), to}}))
    return;
  if (from < 0 || to < 0 || from >= m_queue.size() || to >= m_queue.size() ||
      from == to)
    return;
  cancelAutoplay();
  m_queue.move(from, to);
  // The listener is the DJ now; shuffle does not get a veto.
  m_orderedQueue = m_queue;
  emit queueChanged();
  emit stateChanged();
  ensureAutoplay();
  savePlaybackState();
}

// Some home shelves omit the album link; the watch panel has the exact one.
void PlaybackController::resolveTrackAlbum() {
  m_albumRequest = 0;
  const QString id = m_track.value(QStringLiteral("id")).toString();
  const QString type = m_track.value(QStringLiteral("type")).toString();
  if (id.isEmpty() || !m_track.value(QStringLiteral("albumId")).toString().isEmpty() ||
      (type != QStringLiteral("song") && type != QStringLiteral("track")) ||
      !m_auth->isSignedIn() || local::isLocalTrackId(id))
    return;
  m_albumRequest = m_provider->invoke(
      "catalog.track",
      QJsonObject{{"videoId", id}, {"session", m_auth->sessionObject()}});
}

void PlaybackController::cancelAutoplay() {
  m_autoplayRequest = 0;
  m_autoplaySeed.clear();
  m_autoplayError.clear();
  m_waitingForAutoplay = false;
}

void PlaybackController::setAutoplayEnabled(bool enabled) {
  if (m_autoplayEnabled == enabled)
    return;
  m_autoplayEnabled = enabled;
  QSettings().setValue("playback/autoplayEnabled", enabled);
  cancelAutoplay();
  m_autoplaySuppressed.clear();
  if (!enabled) {
    for (int i = m_queue.size() - 1; i >= 0; --i) {
      if (!m_queue.at(i).toMap().value("autoplayGenerated").toBool())
        continue;
      m_orderedQueue.removeOne(m_queue.at(i));
      m_queue.removeAt(i);
    }
    emit queueChanged();
  } else
    ensureAutoplay();
  emit stateChanged();
  updateSystemMedia();
}

void PlaybackController::retryAutoplay() {
  cancelAutoplay();
  m_autoplaySuppressed.clear();
  ensureAutoplay();
}

void PlaybackController::ensureAutoplay() {
  // Local songs have no "up next" on YouTube; the playlist ends when it ends.
  if (!m_autoplayEnabled || m_autoplayRequest || m_track.isEmpty() ||
      !m_auth->isSignedIn() || local::isLocalTrack(m_track) || offlineMode() || m_repeatMode != "off" ||
      m_queue.size() > 3 ||
      m_queueRequest || !m_queueContinuation.isEmpty() ||
      m_autoplaySuppressed == m_track.value("id").toString())
    return;
  m_autoplaySuppressed.clear();
  const auto seed = (m_queue.isEmpty() ? m_track : m_queue.last().toMap())
                        .value("id")
                        .toString();
  if (seed.isEmpty() || seed == m_autoplaySeed)
    return;
  m_autoplaySeed = seed;
  m_autoplayError.clear();
  m_autoplayRequest = m_provider->invoke(
      "catalog.upNext",
      QJsonObject{{"videoId", seed}, {"session", m_auth->sessionObject()}});
  emit stateChanged();
}
