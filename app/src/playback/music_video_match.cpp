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

#include "music_video_match.h"

#include <QRegularExpression>
#include <QStringList>
#include <algorithm>
#include <cmath>

namespace musicvideo {
namespace {

const QString kOmv = QStringLiteral("MUSIC_VIDEO_TYPE_OMV");
const QString kUgc = QStringLiteral("MUSIC_VIDEO_TYPE_UGC");

bool isVideoType(const QString &type) { return type == kOmv || type == kUgc; }

QString normalizedText(const QString &text) {
  static const QRegularExpression nonWord(QStringLiteral("[^a-z0-9]+"));
  return text.toLower().replace(nonWord, QStringLiteral(" ")).trimmed();
}

// Accepts numeric seconds or "m:ss" text, as catalog rows carry either.
double seconds(const QVariant &value) {
  bool ok = false;
  const double direct = value.toDouble(&ok);
  if (ok && direct > 0)
    return direct;
  double total = 0;
  const QStringList parts = value.toString().trimmed().split(QLatin1Char(':'));
  for (const QString &part : parts) {
    const double n = part.toDouble(&ok);
    if (!ok)
      return 0;
    total = total * 60 + n;
  }
  return total;
}

double durationOf(const QVariantMap &item) {
  const double fromSeconds = seconds(item.value(QStringLiteral("durationSeconds")));
  return fromSeconds > 0 ? fromSeconds : seconds(item.value(QStringLiteral("duration")));
}

QString artistOf(const QVariantMap &item) {
  const QString artist = item.value(QStringLiteral("artist")).toString();
  if (!artist.isEmpty())
    return artist;
  const QVariantList artists = item.value(QStringLiteral("artists")).toList();
  return artists.isEmpty() ? QString() : artists.first().toString();
}

int score(const QVariantMap &candidate, const QVariantMap &target) {
  if (normalizedTitle(candidate.value(QStringLiteral("title")).toString()) !=
      normalizedTitle(target.value(QStringLiteral("title")).toString()))
    return 0;
  int total = 2;
  const QString artist = normalizedText(artistOf(candidate));
  const QString targetArtist = normalizedText(artistOf(target));
  if (!artist.isEmpty() && artist == targetArtist)
    total += 5;
  else if (!artist.isEmpty() && !targetArtist.isEmpty() &&
           (artist.contains(targetArtist) || targetArtist.contains(artist)))
    total += 2;
  if (candidate.value(QStringLiteral("musicVideoType")).toString() == kOmv)
    total += 2;
  const double duration = durationOf(candidate);
  const double targetDuration = durationOf(target);
  if (duration > 0 && targetDuration > 0) {
    const double drift = std::abs(duration - targetDuration);
    // Videos often add an intro or outro; a radically different runtime is another song.
    if (drift > std::max(45.0, targetDuration / 3))
      return 0;
    total += drift <= 8.0 ? 3 : 1;
  }
  return total;
}

} // namespace

QString normalizedTitle(const QString &title) {
  static const QRegularExpression decoration(
      QStringLiteral(R"(\s*[\[(][^)\]]*(?:official|music|video|audio|visualizer|lyrics?|4k|hd)[^)\]]*[)\]]\s*)"),
      QRegularExpression::CaseInsensitiveOption);
  QString stripped = title;
  return normalizedText(stripped.replace(decoration, QStringLiteral(" ")));
}

QString directVideoId(const QVariantMap &track) {
  const QString type = track.value(QStringLiteral("type")).toString();
  if (type == QStringLiteral("video") || isVideoType(track.value(QStringLiteral("musicVideoType")).toString()))
    return track.value(QStringLiteral("id")).toString();
  return {};
}

QString searchQuery(const QVariantMap &track) {
  return (track.value(QStringLiteral("title")).toString() + QLatin1Char(' ') + artistOf(track)).trimmed();
}

QJsonArray videoCandidates(const QJsonObject &searchResult) {
  for (const QJsonValue &section : searchResult.value(QStringLiteral("sections")).toArray()) {
    const QJsonObject object = section.toObject();
    if (object.value(QStringLiteral("key")).toString() == QStringLiteral("videos"))
      return object.value(QStringLiteral("items")).toArray();
  }
  return {};
}

QString bestVideoId(const QVariantMap &track, const QJsonArray &candidates) {
  const bool explicitTrack = track.value(QStringLiteral("explicit")).toBool();
  QString best;
  int bestScore = 0;
  for (const QJsonValue &value : candidates) {
    const QVariantMap candidate = value.toObject().toVariantMap();
    // Clean and explicit cuts are different recordings.
    if (!isVideoType(candidate.value(QStringLiteral("musicVideoType")).toString()) ||
        candidate.value(QStringLiteral("explicit")).toBool() != explicitTrack)
      continue;
    const int candidateScore = score(candidate, track);
    if (candidateScore > bestScore) {
      bestScore = candidateScore;
      best = candidate.value(QStringLiteral("id")).toString();
    }
  }
  return best;
}

} // namespace musicvideo
