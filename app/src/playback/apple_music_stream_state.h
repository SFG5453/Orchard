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

#include "apple_music_playlist.h"
#include "apple_music_stream.h"

#include <QSet>

struct AppleMusicStreams::Stream {
  quint32 id{0};
  QString playbackId;
  apple_music_playlist::Playlist playlist;
  QUrl licenseUrl;
  QString appleId;
  // Host generation the session was licensed under; another one means no session.
  quint64 generation{~quint64(0)};
  bool licensing{false};
  QList<Done> readyWaiters;
  QByteArray rawInit;
  QHash<int, QByteArray> clear;
  // Clear media fragments, least recently used first.
  QList<int> recent;
  QHash<int, QList<Done>> loading;
  // Apple's encrypted fragments by index while the stream prefetches.
  QHash<int, QByteArray> encrypted;
  // Fragments with a range request open.
  QSet<int> fetching;
  // The prefetch fills from here first; reads move it to the playhead.
  int prefetchFrom{1};
  bool prefetch{false};
  // Keeps every clear fragment; set for the newest songs only.
  bool whole{false};
  int prefetchFailures{0};

  // Leaves the init segment and the few clear fragments used last.
  void trimClear() {
    while (recent.size() > 4)
      clear.remove(recent.takeFirst());
  }
};
