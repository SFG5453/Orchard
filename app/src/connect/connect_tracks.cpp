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

#include "connect_tracks.h"

#include "home/home_controller.h"
#include "playback/playback_controller.h"

namespace connect_tracks {
namespace {

QString clock(double seconds) {
  const int total = static_cast<int>(seconds);
  return QStringLiteral("%1:%2").arg(total / 60).arg(total % 60, 2, 10, QLatin1Char('0'));
}

double durationOf(const QVariantMap &track) {
  const double seconds = track.value(QStringLiteral("durationSeconds")).toDouble();
  if (seconds > 0.0)
    return seconds;
  // Some rows only carry "3:20".
  double total = 0.0;
  for (const QString &part : track.value(QStringLiteral("duration")).toString().split(QLatin1Char(':')))
    total = total * 60.0 + part.toDouble();
  return total;
}

} // namespace

QJsonObject toWire(const QVariantMap &track) {
  QJsonObject wire;
  const QString id = track.value(QStringLiteral("id")).toString();
  if (id.isEmpty())
    return wire;
  const QStringList artists = track.value(QStringLiteral("artists")).toStringList();
  QString artist = track.value(QStringLiteral("artist")).toString();
  if (artist.isEmpty())
    artist = artists.isEmpty() ? track.value(QStringLiteral("subtitle")).toString() : artists.first();
  wire.insert(QStringLiteral("id"), id);
  wire.insert(QStringLiteral("title"), track.value(QStringLiteral("title")).toString());
  wire.insert(QStringLiteral("artist"), artist);
  wire.insert(QStringLiteral("album"), track.value(QStringLiteral("album")).toString());
  wire.insert(QStringLiteral("artwork"), track.value(QStringLiteral("thumbnail")).toString());
  wire.insert(QStringLiteral("provider"), QStringLiteral("youtube"));
  if (const double seconds = durationOf(track); seconds > 0.0)
    wire.insert(QStringLiteral("duration"), seconds);
  if (const QString albumId = track.value(QStringLiteral("albumId")).toString(); !albumId.isEmpty())
    wire.insert(QStringLiteral("album_id"), albumId);
  const QStringList artistIds = track.value(QStringLiteral("artistBrowseIds")).toStringList();
  if (!artistIds.isEmpty())
    wire.insert(QStringLiteral("artist_id"), artistIds.first());
  wire.insert(QStringLiteral("explicit"), track.value(QStringLiteral("explicit")).toBool());
  // Desktop-only hints that make a round trip back here lossless.
  QJsonObject extra;
  extra.insert(QStringLiteral("type"), track.value(QStringLiteral("type")).toString());
  extra.insert(QStringLiteral("artists"), QJsonArray::fromStringList(artists));
  extra.insert(QStringLiteral("artistBrowseIds"), QJsonArray::fromStringList(artistIds));
  extra.insert(QStringLiteral("musicVideoType"), track.value(QStringLiteral("musicVideoType")).toString());
  extra.insert(QStringLiteral("isAudioOnly"), track.value(QStringLiteral("isAudioOnly")).toBool());
  wire.insert(QStringLiteral("extra"), extra);
  return wire;
}

QVariantMap fromWire(const QJsonObject &wire) {
  QVariantMap track;
  const QString id = wire.value(QStringLiteral("id")).toString();
  if (id.isEmpty())
    return track;
  const QJsonObject extra = wire.value(QStringLiteral("extra")).toObject();
  const QString type = extra.value(QStringLiteral("type")).toString();
  const QString artist = wire.value(QStringLiteral("artist")).toString();
  QStringList artists;
  for (const QJsonValue &name : extra.value(QStringLiteral("artists")).toArray())
    artists.append(name.toString());
  if (artists.isEmpty() && !artist.isEmpty())
    artists.append(artist);
  const double seconds = wire.value(QStringLiteral("duration")).toDouble();
  track.insert(QStringLiteral("id"), id);
  track.insert(QStringLiteral("type"),
               type == QStringLiteral("song") || type == QStringLiteral("video") ? type : QStringLiteral("track"));
  track.insert(QStringLiteral("title"), wire.value(QStringLiteral("title")).toString());
  track.insert(QStringLiteral("artist"), artist);
  track.insert(QStringLiteral("subtitle"), artist);
  track.insert(QStringLiteral("artists"), artists);
  track.insert(QStringLiteral("album"), wire.value(QStringLiteral("album")).toString());
  track.insert(QStringLiteral("albumId"), wire.value(QStringLiteral("album_id")).toString());
  track.insert(QStringLiteral("thumbnail"), wire.value(QStringLiteral("artwork")).toString());
  track.insert(QStringLiteral("durationSeconds"), seconds);
  track.insert(QStringLiteral("duration"), seconds > 0.0 ? clock(seconds) : QString());
  track.insert(QStringLiteral("explicit"), wire.value(QStringLiteral("explicit")).toBool());
  QStringList artistIds;
  for (const QJsonValue &value : extra.value(QStringLiteral("artistBrowseIds")).toArray())
    artistIds.append(value.toString());
  if (artistIds.isEmpty() && wire.contains(QStringLiteral("artist_id")))
    artistIds.append(wire.value(QStringLiteral("artist_id")).toString());
  track.insert(QStringLiteral("artistBrowseIds"), artistIds);
  track.insert(QStringLiteral("musicVideoType"), extra.value(QStringLiteral("musicVideoType")).toString());
  track.insert(QStringLiteral("isAudioOnly"), extra.value(QStringLiteral("isAudioOnly")).toBool());
  return track;
}

QJsonArray toWire(const QVariantList &tracks, int limit) {
  QJsonArray out;
  for (const QVariant &item : tracks) {
    if (out.size() >= limit)
      break;
    const QJsonObject wire = toWire(item.toMap());
    if (!wire.isEmpty())
      out.append(wire);
  }
  return out;
}

QVariantList fromWire(const QJsonArray &tracks) {
  QVariantList out;
  for (const QJsonValue &item : tracks) {
    const QVariantMap track = fromWire(item.toObject());
    if (!track.isEmpty())
      out.append(track);
  }
  return out;
}

QJsonObject localSnapshot(const PlaybackController &playback, const HomeController &home) {
  QJsonObject snapshot;
  const QJsonObject current = toWire(playback.track());
  snapshot.insert(QStringLiteral("track"), current.isEmpty() ? QJsonValue() : QJsonValue(current));
  snapshot.insert(QStringLiteral("position"), playback.position());
  snapshot.insert(QStringLiteral("duration"), playback.duration());
  snapshot.insert(QStringLiteral("playing"), playback.playing());
  snapshot.insert(QStringLiteral("buffering"), playback.loading());
  snapshot.insert(QStringLiteral("queue"), toWire(playback.queue()));
  snapshot.insert(QStringLiteral("volume"), home.volume());
  snapshot.insert(QStringLiteral("repeat"), playback.repeatMode());
  snapshot.insert(QStringLiteral("shuffle"), playback.shuffleEnabled());
  snapshot.insert(QStringLiteral("autoplay"), playback.autoplayEnabled());
  if (playback.crossfadeActive()) {
    snapshot.insert(QStringLiteral("transition"),
                    QJsonObject{{QStringLiteral("active"), true},
                                {QStringLiteral("progress"), playback.crossfadeProgress()},
                                {QStringLiteral("track"), toWire(playback.transitionTrack())}});
  }
  return snapshot;
}

} // namespace connect_tracks
