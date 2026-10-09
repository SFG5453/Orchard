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

#include "local_library.h"
#include "local_collage.h"
#include "local_metadata.h"
#include "local_track.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QStandardPaths>
#include <QUrl>
#include <QUuid>

using namespace local;

LocalLibrary::LocalLibrary(QString rootDir, QObject *parent)
    : QObject(parent), m_store(std::move(rootDir)) {
  m_store.load();
  // Saving on every drag step would hammer the disk; batch them instead.
  m_saveTimer.setSingleShot(true);
  m_saveTimer.setInterval(400);
  connect(&m_saveTimer, &QTimer::timeout, this, [this] { m_store.save(); });
}

// Pending edits are flushed here, because "I swear I saved it" is not a backup strategy.
LocalLibrary::~LocalLibrary() {
  if (m_saveTimer.isActive()) {
    m_saveTimer.stop();
    m_store.save();
  }
}

QString LocalLibrary::defaultRoot() {
  return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/local");
}

QStringList LocalLibrary::audioNameFilters() const {
  QStringList patterns;
  for (const QString &extension : audioExtensions())
    patterns.append(QStringLiteral("*.") + extension);
  return {tr("Audio files (%1)").arg(patterns.join(QLatin1Char(' '))), tr("All files (*)")};
}

QStringList LocalLibrary::imageNameFilters() const {
  return {tr("Pictures and loops (*.png *.jpg *.jpeg *.webp *.gif *.mp4 *.m4v *.mov *.webm)"), tr("All files (*)")};
}

QStringList LocalLibrary::lyricsNameFilters() const {
  return {tr("Lyrics (*.lrc *.srt *.txt)"), tr("All files (*)")};
}

QString LocalLibrary::pathFrom(const QVariant &file) {
  const QUrl url = file.userType() == QMetaType::QUrl ? file.toUrl() : QUrl(file.toString());
  if (url.isLocalFile())
    return url.toLocalFile();
  // QML hands over file:// urls; scripts and tests pass plain paths.
  return url.scheme().isEmpty() ? file.toString() : QString();
}

QVariantList LocalLibrary::playlists() const {
  QVariantList list;
  for (const PlaylistRecord &playlist : m_store.playlists)
    list.append(playlistToVariant(playlist, m_store));
  return list;
}

QVariantList LocalLibrary::songs() const {
  QVariantList list;
  for (const QString &id : m_store.trackOrder) {
    const auto it = m_store.tracks.constFind(id);
    if (it != m_store.tracks.cend())
      list.append(trackToVariant(it.value()));
  }
  return list;
}

QVariantMap LocalLibrary::track(const QString &trackId) const {
  const auto it = m_store.tracks.constFind(trackId);
  return it == m_store.tracks.cend() ? QVariantMap() : trackToVariant(it.value());
}

QVariantList LocalLibrary::targetsFor(const QString &trackId) const {
  QVariantList targets;
  for (const PlaylistRecord &playlist : m_store.playlists) {
    targets.append(QVariantMap{{QStringLiteral("id"), playlist.id},
                               {QStringLiteral("title"), playlist.title},
                               {QStringLiteral("containsTrack"), playlist.trackIds.contains(trackId)}});
  }
  return targets;
}

void LocalLibrary::beginJob() {
  if (m_jobs++ == 0)
    emit busyChanged();
}

void LocalLibrary::endJob() {
  if (--m_jobs == 0)
    emit busyChanged();
}

void LocalLibrary::scheduleSave() { m_saveTimer.start(); }

// The playlist's collage follows its first four distinct covers, and a cover
// the user picked always wins over generated art.
void LocalLibrary::refreshCollage(PlaylistRecord &playlist) {
  const auto discard = [&playlist] {
    if (!playlist.collagePath.isEmpty())
      QFile::remove(playlist.collagePath);
    playlist.collagePath.clear();
  };
  if (!playlist.coverPath.isEmpty()) {
    discard();
    return;
  }
  QStringList covers;
  for (const QString &id : playlist.trackIds) {
    const auto it = m_store.tracks.constFind(id);
    if (it != m_store.tracks.cend() && !it->coverPath.isEmpty())
      covers.append(it->coverPath);
  }
  const QStringList sources = collageSources(covers);
  if (sources.isEmpty()) {
    discard();
    return;
  }
  const QString name = playlist.id.mid(kPlaylistPrefix.size()) + QLatin1Char('-') + collageKey(sources) + QStringLiteral(".jpg");
  const QString target = m_store.collagesDir() + QLatin1Char('/') + name;
  if (playlist.collagePath == target && QFileInfo::exists(target))
    return;
  if (!writeCollage(sources, target)) {
    discard();
    return;
  }
  if (!playlist.collagePath.isEmpty() && playlist.collagePath != target)
    QFile::remove(playlist.collagePath);
  playlist.collagePath = target;
}

void LocalLibrary::refreshDetail() {
  QVariantMap next;
  if (const PlaylistRecord *playlist = m_store.playlist(m_openId))
    next = playlistDetail(*playlist, m_store);
  if (next == m_detail)
    return;
  m_detail = next;
  emit detailChanged();
}

void LocalLibrary::commit(const QString &playlistId) {
  for (PlaylistRecord &playlist : m_store.playlists) {
    if (playlistId.isEmpty() || playlist.id == playlistId)
      refreshCollage(playlist);
  }
  scheduleSave();
  emit changed();
  refreshDetail();
}

QString LocalLibrary::createPlaylist(const QString &title, const QVariantList &files) {
  PlaylistRecord playlist;
  playlist.id = kPlaylistPrefix + QUuid::createUuid().toString(QUuid::WithoutBraces);
  playlist.title = title.trimmed().isEmpty() ? tr("New playlist") : title.trimmed();
  playlist.createdAt = QDateTime::currentMSecsSinceEpoch();
  m_store.playlists.append(playlist);
  commit(playlist.id);
  emit playlistCreated(playlist.id);
  emit noticeRequested(tr("Created “%1”.").arg(playlist.title));
  if (!files.isEmpty())
    importFiles(files, playlist.id);
  return playlist.id;
}

void LocalLibrary::renamePlaylist(const QString &id, const QString &title) {
  PlaylistRecord *playlist = m_store.playlist(id);
  const QString trimmed = title.trimmed();
  if (!playlist || trimmed.isEmpty() || playlist->title == trimmed)
    return;
  playlist->title = trimmed;
  commit(id);
}

void LocalLibrary::setPlaylistDescription(const QString &id, const QString &description) {
  PlaylistRecord *playlist = m_store.playlist(id);
  if (!playlist || playlist->description == description)
    return;
  playlist->description = description;
  commit(id);
}

void LocalLibrary::deletePlaylist(const QString &id) {
  for (qsizetype i = 0; i < m_store.playlists.size(); ++i) {
    if (m_store.playlists.at(i).id != id)
      continue;
    const PlaylistRecord removed = m_store.playlists.takeAt(i);
    dropCoverFiles(removed.coverPath, removed.animatedPath);
    if (!removed.collagePath.isEmpty())
      QFile::remove(removed.collagePath);
    if (m_openId == id)
      m_openId.clear();
    commit();
    return;
  }
}

void LocalLibrary::openPlaylist(const QString &id) {
  m_openId = id;
  m_detail.clear();
  refreshDetail();
  // An unknown id still has to tell QML the old page is gone.
  emit detailChanged();
}

void LocalLibrary::closePlaylist() {
  m_openId.clear();
  m_detail.clear();
  emit detailChanged();
}

void LocalLibrary::addTrackToPlaylist(const QString &playlistId, const QString &trackId) {
  PlaylistRecord *playlist = m_store.playlist(playlistId);
  if (!playlist || !m_store.tracks.contains(trackId))
    return;
  if (playlist->trackIds.contains(trackId)) {
    emit noticeRequested(tr("Already in “%1”.").arg(playlist->title));
    return;
  }
  playlist->trackIds.append(trackId);
  commit(playlistId);
  emit noticeRequested(tr("Added to “%1”.").arg(playlist->title));
}

void LocalLibrary::removeTrack(const QString &playlistId, int index) {
  PlaylistRecord *playlist = m_store.playlist(playlistId);
  if (!playlist || index < 0 || index >= playlist->trackIds.size())
    return;
  playlist->trackIds.removeAt(index);
  commit(playlistId);
}

void LocalLibrary::removeTrackById(const QString &playlistId, const QString &trackId) {
  const PlaylistRecord *playlist = m_store.playlist(playlistId);
  if (playlist)
    removeTrack(playlistId, static_cast<int>(playlist->trackIds.indexOf(trackId)));
}

// `to` is where the song ends up, which is what a finished drag knows.
void LocalLibrary::moveTrack(const QString &playlistId, int from, int to) {
  PlaylistRecord *playlist = m_store.playlist(playlistId);
  const int count = playlist ? static_cast<int>(playlist->trackIds.size()) : 0;
  if (!playlist || from == to || from < 0 || to < 0 || from >= count || to >= count)
    return;
  playlist->trackIds.move(from, to);
  commit(playlistId);
}

void LocalLibrary::removeSong(const QString &trackId) {
  const auto it = m_store.tracks.find(trackId);
  if (it == m_store.tracks.end())
    return;
  if (it->customCover)
    dropCoverFiles(it->coverPath, it->animatedPath);
  if (!it->lyricsPath.isEmpty())
    QFile::remove(it->lyricsPath);
  m_store.tracks.erase(it);
  m_store.trackOrder.removeAll(trackId);
  for (PlaylistRecord &playlist : m_store.playlists)
    playlist.trackIds.removeAll(trackId);
  commit();
}

void LocalLibrary::dropCoverFiles(const QString &still, const QString &animated) {
  for (const QString &path : {still, animated}) {
    // Only files inside the store are ours to delete. Never the user's music folder.
    if (!path.isEmpty() && path.startsWith(m_store.root()))
      QFile::remove(path);
  }
}
