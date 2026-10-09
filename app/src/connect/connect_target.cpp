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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// This desktop as a Connect target: controller intent lands on the local player,
// which then publishes the authoritative result. Arguments arrive validated by
// the core (normalizeCommand), so only the mapping to desktop calls lives here.

#include "connect_service.h"

#include "connect_tracks.h"
#include "home/home_controller.h"
#include "playback/playback_controller.h"

#include <QJsonArray>

void ConnectService::applyCommand(const QJsonObject &event) {
  PlaybackController *playback = m_playback.data();
  if (!playback || !m_home)
    return;
  const QString action = event.value(QStringLiteral("action")).toString();
  const QJsonObject args = event.value(QStringLiteral("args")).toObject();
  const auto index = [&](const char *key) { return args.value(QLatin1String(key)).toInt(); };

  if (action == QStringLiteral("play")) {
    playback->play();
  } else if (action == QStringLiteral("pause")) {
    playback->pause();
  } else if (action == QStringLiteral("toggle")) {
    playback->toggle();
  } else if (action == QStringLiteral("seek")) {
    playback->seek(args.value(QStringLiteral("position")).toDouble());
  } else if (action == QStringLiteral("next")) {
    playback->next();
  } else if (action == QStringLiteral("previous")) {
    playback->previous();
  } else if (action == QStringLiteral("set_volume")) {
    m_home->setVolume(args.value(QStringLiteral("volume")).toDouble());
  } else if (action == QStringLiteral("set_shuffle")) {
    playback->setShuffleEnabled(args.value(QStringLiteral("enabled")).toBool());
  } else if (action == QStringLiteral("set_repeat")) {
    playback->setRepeatMode(args.value(QStringLiteral("mode")).toString());
  } else if (action == QStringLiteral("play_track") || action == QStringLiteral("replace_queue")) {
    QVariantList tracks = connect_tracks::fromWire(args.value(QStringLiteral("tracks")).toArray());
    int start = index("index");
    if (action == QStringLiteral("play_track")) {
      tracks.prepend(connect_tracks::fromWire(args.value(QStringLiteral("track")).toObject()));
      start = 0;
    }
    playback->playFrom(tracks, start, args.value(QStringLiteral("position")).toDouble(),
                       args.value(QStringLiteral("play")).toBool(true));
  } else if (action == QStringLiteral("enqueue")) {
    playback->enqueue(connect_tracks::fromWire(args.value(QStringLiteral("track")).toObject()),
                      args.value(QStringLiteral("next")).toBool());
  } else if (action == QStringLiteral("remove_queue_item")) {
    playback->removeFromQueue(index("index"));
  } else if (action == QStringLiteral("move_queue_item")) {
    playback->moveQueueItem(index("from"), index("to"));
  } else if (action == QStringLiteral("clear_queue")) {
    playback->clearQueue();
  } else if (action == QStringLiteral("play_queue_index")) {
    playback->playQueueIndex(index("index"));
  }
  // Commands that changed nothing still deserve a fresh state for the controller.
  schedulePublish();
}

void ConnectService::applyTransfer(const QJsonObject &snapshot) {
  PlaybackController *playback = m_playback.data();
  if (!playback)
    return;
  QVariantList tracks = connect_tracks::fromWire(snapshot.value(QStringLiteral("queue")).toArray());
  const QVariantMap current = connect_tracks::fromWire(snapshot.value(QStringLiteral("track")).toObject());
  if (current.isEmpty())
    return;
  tracks.prepend(current);
  // Set before the queue arrives: turning shuffle on reshuffles whatever is queued.
  playback->setShuffleEnabled(snapshot.value(QStringLiteral("shuffle")).toBool());
  playback->setRepeatMode(snapshot.value(QStringLiteral("repeat")).toString(QStringLiteral("off")));
  playback->playFrom(tracks, 0, snapshot.value(QStringLiteral("position")).toDouble(),
                     snapshot.value(QStringLiteral("playing")).toBool(true));
}
