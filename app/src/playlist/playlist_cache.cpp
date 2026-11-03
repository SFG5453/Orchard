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

#include "playlist_controller.h"

#include "auth/auth_manager.h"
#include "integrations/apple_music.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>
#include <QStandardPaths>

namespace {
constexpr int kMaxCachedPlaylists = 40;
// Bump when the stored detail shape changes.
constexpr int kCacheVersion = 1;

QDir cacheDirectory() {
  return QDir(QDir(QStandardPaths::writableLocation(QStandardPaths::CacheLocation))
                  .filePath(QStringLiteral("playlists")));
}

QString cachePath(const QString &key) {
  const QByteArray name = QCryptographicHash::hash(key.toUtf8(), QCryptographicHash::Sha1).toHex();
  return cacheDirectory().filePath(QString::fromLatin1(name) + QStringLiteral(".json"));
}

bool sameRow(const QVariant &a, const QVariant &b) {
  const QVariantMap left = a.toMap();
  const QVariantMap right = b.toMap();
  return left.value(QStringLiteral("id")) == right.value(QStringLiteral("id")) &&
         left.value(QStringLiteral("setVideoId")) == right.value(QStringLiteral("setVideoId"));
}

bool startsWith(const QVariantList &whole, const QVariantList &prefix) {
  if (prefix.size() > whole.size())
    return false;
  for (qsizetype index = 0; index < prefix.size(); ++index)
    if (!sameRow(whole.at(index), prefix.at(index)))
      return false;
  return true;
}

void prune(const QDir &directory) {
  const QFileInfoList files = directory.entryInfoList({QStringLiteral("*.json")}, QDir::Files, QDir::Time);
  for (qsizetype index = kMaxCachedPlaylists; index < files.size(); ++index)
    QFile::remove(files.at(index).filePath());
}
} // namespace

bool PlaylistController::showCached(const QString &browseId) {
  // Private playlists must not show under another account.
  const QString account = AppleMusic::isAppleMusicId(browseId)
                              ? QStringLiteral("apple")
                              : m_auth->userHandle() + QLatin1Char('#') +
                                    m_auth->sessionObject().value(QStringLiteral("accountIndex")).toVariant().toString();
  m_cacheKey = account + QLatin1Char('|') + browseId;
  QFile file(cachePath(m_cacheKey));
  if (!file.open(QIODevice::ReadOnly))
    return false;
  const QJsonObject stored = QJsonDocument::fromJson(file.readAll()).object();
  if (stored.value(QStringLiteral("version")).toInt() != kCacheVersion ||
      stored.value(QStringLiteral("key")).toString() != m_cacheKey)
    return false;
  const QVariantMap detail = stored.value(QStringLiteral("detail")).toObject().toVariantMap();
  const QVariantList tracks = detail.value(QStringLiteral("tracks")).toList();
  if (tracks.isEmpty())
    return false;
  m_heldDetail = detail;
  m_heldTracks = tracks;
  applySort();
  requestArtwork(detail.value(QStringLiteral("thumbnail")).toString());
  return true;
}

PlaylistController::Settled PlaylistController::settleCached(bool complete) {
  if (m_heldDetail.isEmpty())
    return Settled::NotHeld;
  const bool matches = startsWith(m_heldTracks, m_sourceTracks);
  if (!complete && matches)
    return Settled::Holding;
  const bool same = matches && m_heldTracks.size() == m_sourceTracks.size();
  m_heldDetail.clear();
  m_heldTracks.clear();
  return same ? Settled::Unchanged : Settled::Replaced;
}

void PlaylistController::cacheComplete() {
  if (m_cacheKey.isEmpty() || m_sourceTracks.isEmpty())
    return;
  QVariantMap detail = m_detail;
  detail.insert(QStringLiteral("tracks"), m_sourceTracks);
  detail.remove(QStringLiteral("continuation"));
  detail.insert(QStringLiteral("hasMoreTracks"), false);
  const QDir directory = cacheDirectory();
  if (!QDir().mkpath(directory.path()))
    return;
  QSaveFile file(cachePath(m_cacheKey));
  if (!file.open(QIODevice::WriteOnly))
    return;
  file.write(QJsonDocument(QJsonObject{
                               {QStringLiteral("version"), kCacheVersion},
                               {QStringLiteral("key"), m_cacheKey},
                               {QStringLiteral("detail"), QJsonObject::fromVariantMap(detail)},
                           })
                 .toJson(QJsonDocument::Compact));
  if (file.commit())
    prune(directory);
}
