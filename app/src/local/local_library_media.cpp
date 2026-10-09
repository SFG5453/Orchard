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

// Everything that reads a user's files: songs, pictures and lyrics.

#include "local_async.h"
#include "local_jobs.h"
#include "local_library.h"
#include "local_lyrics.h"
#include "local_track.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>

using namespace local;

void LocalLibrary::importFiles(const QVariantList &files, const QString &playlistId) {
  QStringList paths;
  for (const QVariant &file : files) {
    const QString path = pathFrom(file);
    if (!path.isEmpty())
      paths.append(path);
  }
  if (paths.isEmpty())
    return;
  const QSet<QString> known(m_store.tracks.keyBegin(), m_store.tracks.keyEnd());
  const QString covers = m_store.coversDir();
  QDir().mkpath(covers);
  beginJob();
  // The pool does the ffprobe waiting so the window keeps its manners.
  runAsync<ImportResult>(
      this, [paths, known, covers] { return importPaths(paths, known, covers); },
      [this, playlistId](ImportResult result) {
        finishImport(result, playlistId);
        endJob();
      });
}

void LocalLibrary::finishImport(const ImportResult &result, const QString &playlistId) {
  for (const TrackRecord &record : result.records) {
    if (m_store.tracks.contains(record.id))
      continue;
    m_store.tracks.insert(record.id, record);
    // Newest first, but a folder keeps its own order within the batch.
    m_store.trackOrder.prepend(record.id);
  }
  PlaylistRecord *playlist = m_store.playlist(playlistId);
  int added = 0;
  if (playlist) {
    for (const QString &id : result.ids) {
      if (!playlist->trackIds.contains(id)) {
        playlist->trackIds.append(id);
        ++added;
      }
    }
  }
  commit(playlistId);
  if (result.ids.isEmpty()) {
    emit noticeRequested(tr("No audio files found."));
  } else if (playlist) {
    emit noticeRequested(tr("Added %n song(s) to “%1”.", nullptr, added).arg(playlist->title));
  } else {
    emit noticeRequested(tr("Added %n song(s) to your library.", nullptr, static_cast<int>(result.records.size())));
  }
}

void LocalLibrary::setPlaylistCover(const QString &playlistId, const QVariant &file) {
  const QString source = pathFrom(file);
  if (!m_store.playlist(playlistId) || source.isEmpty())
    return;
  const QString base = QStringLiteral("pl-") + playlistId.mid(kPlaylistPrefix.size());
  const QString covers = m_store.coversDir();
  const QString animated = m_store.animatedDir();
  beginJob();
  runAsync<CoverResult>(
      this,
      [source, base, covers, animated] {
        CoverResult result = ingestCover(source, base, covers, animated);
        return result;
      },
      [this, playlistId](CoverResult result) {
        applyCover(playlistId, true, result);
        endJob();
      });
}

void LocalLibrary::setTrackCover(const QString &trackId, const QVariant &file) {
  const QString source = pathFrom(file);
  if (!m_store.tracks.contains(trackId) || source.isEmpty())
    return;
  const QString base = QStringLiteral("tr-") + trackId.mid(kTrackPrefix.size());
  const QString covers = m_store.coversDir();
  const QString animated = m_store.animatedDir();
  beginJob();
  runAsync<CoverResult>(
      this, [source, base, covers, animated] { return ingestCover(source, base, covers, animated); },
      [this, trackId](CoverResult result) {
        applyCover(trackId, false, result);
        endJob();
      });
}

// Runs on the GUI thread once a picture has been copied into the store.
void LocalLibrary::applyCover(const QString &ownerId, bool playlist, const CoverResult &result) {
  if (!result.ok) {
    emit noticeRequested(result.error.isEmpty() ? tr("That picture could not be used.") : result.error);
    return;
  }
  if (playlist) {
    PlaylistRecord *record = m_store.playlist(ownerId);
    if (!record) {
      dropCoverFiles(result.still, result.animated);
      return;
    }
    dropCoverFiles(record->coverPath, record->animatedPath);
    record->coverPath = result.still;
    record->animatedPath = result.animated;
    commit(ownerId);
    return;
  }
  const auto it = m_store.tracks.find(ownerId);
  if (it == m_store.tracks.end()) {
    dropCoverFiles(result.still, result.animated);
    return;
  }
  // Only the user's own earlier pick is ours to delete; embedded art is shared.
  if (it->customCover)
    dropCoverFiles(it->coverPath, it->animatedPath);
  it->coverPath = result.still;
  it->animatedPath = result.animated;
  it->customCover = true;
  commit();
}

void LocalLibrary::clearPlaylistCover(const QString &playlistId) {
  PlaylistRecord *playlist = m_store.playlist(playlistId);
  if (!playlist || (playlist->coverPath.isEmpty() && playlist->animatedPath.isEmpty()))
    return;
  dropCoverFiles(playlist->coverPath, playlist->animatedPath);
  playlist->coverPath.clear();
  playlist->animatedPath.clear();
  commit(playlistId);
}

// Back to whatever the file's own tags carried, found again by the next import.
void LocalLibrary::clearTrackCover(const QString &trackId) {
  const auto it = m_store.tracks.find(trackId);
  if (it == m_store.tracks.end() || !it->customCover)
    return;
  dropCoverFiles(it->coverPath, it->animatedPath);
  it->coverPath.clear();
  it->animatedPath.clear();
  it->customCover = false;
  const QString id = trackId;
  const QString path = it->path;
  const QString covers = m_store.coversDir();
  beginJob();
  runAsync<ImportResult>(
      this,
      [path, covers] { return importPaths({path}, {}, covers); },
      [this, id](ImportResult result) {
        const auto found = m_store.tracks.find(id);
        if (found != m_store.tracks.end() && !result.records.isEmpty())
          found->coverPath = result.records.first().coverPath;
        commit();
        endJob();
      });
}

void LocalLibrary::setTrackLyrics(const QString &trackId, const QVariant &file) {
  const auto it = m_store.tracks.find(trackId);
  const QString source = pathFrom(file);
  if (it == m_store.tracks.end() || source.isEmpty())
    return;
  if (parseLyricsFile(source).value(QStringLiteral("status")).toString() != QStringLiteral("ready")) {
    emit noticeRequested(tr("That file does not look like lyrics. Try an .lrc, .srt or .txt file."));
    return;
  }
  QDir().mkpath(m_store.lyricsDir());
  const QString target = m_store.lyricsDir() + QLatin1Char('/') + trackId.mid(kTrackPrefix.size()) + QLatin1Char('.') +
                         QFileInfo(source).suffix().toLower();
  if (!it->lyricsPath.isEmpty())
    QFile::remove(it->lyricsPath);
  QFile::remove(target);
  if (!QFile::copy(source, target)) {
    it->lyricsPath.clear();
    emit noticeRequested(tr("The lyrics could not be copied into the library."));
    commit();
    return;
  }
  it->lyricsPath = target;
  commit();
  emit noticeRequested(tr("Lyrics saved for “%1”.").arg(it->title));
}

void LocalLibrary::clearTrackLyrics(const QString &trackId) {
  const auto it = m_store.tracks.find(trackId);
  if (it == m_store.tracks.end() || it->lyricsPath.isEmpty())
    return;
  QFile::remove(it->lyricsPath);
  it->lyricsPath.clear();
  commit();
}

QVariantMap LocalLibrary::lyricsFor(const QString &trackId) const {
  const auto it = m_store.tracks.constFind(trackId);
  if (it == m_store.tracks.cend())
    return parseLyrics({});
  if (!it->lyricsPath.isEmpty()) {
    const QVariantMap custom = parseLyricsFile(it->lyricsPath);
    if (custom.value(QStringLiteral("status")).toString() == QStringLiteral("ready"))
      return custom;
  }
  return parseLyrics(it->embeddedLyrics);
}
