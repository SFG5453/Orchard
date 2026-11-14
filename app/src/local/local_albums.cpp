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

#include "local_albums.h"

#include <QCryptographicHash>
#include <QHash>
#include <QObject>
#include <QRegularExpression>
#include <QUrl>

#include <algorithm>
#include <climits>

namespace local {
namespace {

QString folded(const QString &text) { return text.simplified().toCaseFolded(); }

// "Album/CD 2/track.flac" belongs with "Album/CD 1".
QString albumFolder(const QString &path) {
  static const QRegularExpression discFolder(QStringLiteral("^(cd|disc|disk)\\s*\\d+$"),
                                             QRegularExpression::CaseInsensitiveOption);
  QString folder = path.left(qMax<qsizetype>(0, path.lastIndexOf(QLatin1Char('/'))));
  const qsizetype slash = folder.lastIndexOf(QLatin1Char('/'));
  if (slash > 0 && discFolder.match(folder.mid(slash + 1).trimmed()).hasMatch())
    folder.truncate(slash);
  return folder;
}

struct Album {
  QString id;
  QList<const TrackRecord *> tracks;
};

bool albumOrder(const TrackRecord *left, const TrackRecord *right) {
  if (left->discNumber != right->discNumber)
    return left->discNumber < right->discNumber;
  // Untagged tracks go after numbered ones, then by file name.
  const int leftTrack = left->trackNumber > 0 ? left->trackNumber : INT_MAX;
  const int rightTrack = right->trackNumber > 0 ? right->trackNumber : INT_MAX;
  if (leftTrack != rightTrack)
    return leftTrack < rightTrack;
  return left->path.compare(right->path, Qt::CaseInsensitive) < 0;
}

QList<Album> groupAlbums(const LocalStore &store) {
  QList<Album> albums;
  QHash<QString, qsizetype> index;
  for (const QString &trackId : store.trackOrder) {
    const auto it = store.tracks.constFind(trackId);
    if (it == store.tracks.cend())
      continue;
    const QString id = albumIdFor(it.value());
    if (id.isEmpty())
      continue;
    auto slot = index.constFind(id);
    if (slot == index.cend()) {
      slot = index.insert(id, albums.size());
      albums.append(Album{id, {}});
    }
    albums[slot.value()].tracks.append(&it.value());
  }
  for (Album &album : albums)
    std::stable_sort(album.tracks.begin(), album.tracks.end(), albumOrder);
  return albums;
}

QString albumArtist(const Album &album) {
  QString common;
  for (const TrackRecord *track : album.tracks) {
    if (!track->albumArtist.isEmpty())
      return track->albumArtist;
    if (common.isEmpty())
      common = track->artist;
    else if (!track->artist.isEmpty() && folded(track->artist) != folded(common))
      return QObject::tr("Various Artists");
  }
  return common;
}

QVariantMap albumCard(const Album &album) {
  const TrackRecord *first = album.tracks.constFirst();
  QString cover;
  int year = 0;
  for (const TrackRecord *track : album.tracks) {
    if (cover.isEmpty())
      cover = track->coverPath;
    year = qMax(year, track->year);
  }
  const QString artist = albumArtist(album);
  const int count = static_cast<int>(album.tracks.size());
  QStringList subtitle{artist.isEmpty() ? QObject::tr("Local album") : artist};
  if (year > 0)
    subtitle.append(QString::number(year));
  return {
      {QStringLiteral("id"), album.id},
      // Navigation and history key detail pages by these.
      {QStringLiteral("browseId"), album.id},
      {QStringLiteral("playlistId"), album.id},
      {QStringLiteral("type"), QStringLiteral("album")},
      {QStringLiteral("source"), QStringLiteral("local")},
      {QStringLiteral("title"), first->album},
      {QStringLiteral("artist"), artist},
      {QStringLiteral("artists"), artist.isEmpty() ? QStringList() : QStringList{artist}},
      {QStringLiteral("year"), year > 0 ? QString::number(year) : QString()},
      {QStringLiteral("subtitle"), subtitle.join(QStringLiteral(" · "))},
      {QStringLiteral("thumbnail"), cover.isEmpty() ? QString() : QUrl::fromLocalFile(cover).toString()},
      {QStringLiteral("totalTrackCount"), count},
  };
}

} // namespace

QString albumIdFor(const TrackRecord &track) {
  const QString album = folded(track.album);
  if (album.isEmpty())
    return {};
  const QString owner = track.albumArtist.isEmpty() ? albumFolder(track.path) : folded(track.albumArtist);
  const QByteArray key = (album + QLatin1Char('\n') + owner).toUtf8();
  return kAlbumPrefix + QString::fromLatin1(QCryptographicHash::hash(key, QCryptographicHash::Sha1).toHex().left(16));
}

QVariantList albumsToVariant(const LocalStore &store) {
  QVariantList cards;
  for (const Album &album : groupAlbums(store))
    cards.append(albumCard(album));
  std::stable_sort(cards.begin(), cards.end(), [](const QVariant &left, const QVariant &right) {
    const QVariantMap a = left.toMap();
    const QVariantMap b = right.toMap();
    const int byArtist = QString::localeAwareCompare(a.value(QStringLiteral("artist")).toString(),
                                                     b.value(QStringLiteral("artist")).toString());
    if (byArtist != 0)
      return byArtist < 0;
    return QString::localeAwareCompare(a.value(QStringLiteral("title")).toString(),
                                       b.value(QStringLiteral("title")).toString()) < 0;
  });
  return cards;
}

QVariantMap albumDetail(const QString &albumId, const LocalStore &store) {
  if (!isLocalAlbumId(albumId))
    return {};
  for (const Album &album : groupAlbums(store)) {
    if (album.id != albumId)
      continue;
    QVariantMap detail = albumCard(album);
    QVariantList rows;
    double total = 0;
    for (const TrackRecord *track : album.tracks) {
      rows.append(trackToVariant(*track));
      total += track->durationSeconds;
    }
    detail.insert(QStringLiteral("kind"), QStringLiteral("album"));
    detail.insert(QStringLiteral("author"), detail.value(QStringLiteral("artist")));
    detail.insert(QStringLiteral("tracks"), rows);
    detail.insert(QStringLiteral("hasMoreTracks"), false);
    detail.insert(QStringLiteral("durationSeconds"), total);
    return detail;
  }
  return {};
}

} // namespace local
