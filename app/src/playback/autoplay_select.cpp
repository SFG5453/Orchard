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

#include "autoplay_select.h"

#include <QRegularExpression>
#include <QStringList>
#include <cmath>

namespace autoplay {
namespace {

constexpr double kMaxVariantDriftSeconds = 45;

QString textKey(QString text) {
  static const QRegularExpression nonWord(QStringLiteral("[^\\p{L}\\p{N}]+"));
  return text.normalized(QString::NormalizationForm_KC).toLower().replace(nonWord, QStringLiteral(" ")).trimmed();
}

QString titleKey(QString title) {
  static const QRegularExpression collaborator(
      QStringLiteral("\\s*[\\[(]\\s*(?:feat(?:uring)?\\.?|ft\\.?|with)\\s+[^\\])]+[\\])]\\s*"),
      QRegularExpression::CaseInsensitiveOption);
  // One or more descriptor words, e.g. "Official Music Video", "Visualiser Video", "Lyric Video".
  static const QString descriptor = QStringLiteral(
      "(?:(?:official|music|video|audio|lyrics?|visuali[sz]er|hd|4k|radio\\s+edit|single\\s+version|"
      "album\\s+version|remaster(?:ed)?(?:\\s+\\d{4})?)\\s*)+");
  static const QRegularExpression version(QStringLiteral("\\s*[\\[(]\\s*") + descriptor +
                                              QStringLiteral("[\\])]\\s*"),
                                          QRegularExpression::CaseInsensitiveOption);
  static const QRegularExpression trailing(QStringLiteral("\\s*[-\\x{2013}\\x{2014}]\\s*") + descriptor +
                                               QStringLiteral("$"),
                                           QRegularExpression::CaseInsensitiveOption);
  title = title.normalized(QString::NormalizationForm_KC);
  title.replace(collaborator, QStringLiteral(" "));
  title.replace(version, QStringLiteral(" "));
  title.replace(trailing, QStringLiteral(" "));
  return textKey(title);
}

QString primaryArtistKey(const QString &artist) {
  static const QRegularExpression split(
      QStringLiteral("\\s+(?:feat(?:uring)?\\.?|ft\\.?|with|x)\\s+|\\s+&\\s+"),
      QRegularExpression::CaseInsensitiveOption);
  return textKey(artist.normalized(QString::NormalizationForm_KC).split(split).first());
}

QString artistOf(const QVariantMap &track) {
  const QString artist = track.value(QStringLiteral("artist")).toString();
  if (!artist.isEmpty())
    return artist;
  const QVariantList artists = track.value(QStringLiteral("artists")).toList();
  return artists.isEmpty() ? QString() : artists.first().toString();
}

bool isAudioOnly(const QVariantMap &track) { return track.value(QStringLiteral("isAudioOnly")).toBool(); }

bool isMusicVideo(const QVariantMap &track) {
  const QString type = track.value(QStringLiteral("musicVideoType")).toString();
  return type == QLatin1String("MUSIC_VIDEO_TYPE_OMV") || type == QLatin1String("MUSIC_VIDEO_TYPE_UGC");
}

int versionPreference(const QVariantMap &track) {
  if (track.value(QStringLiteral("isAudioOnly")).toBool())
    return 3;
  const QString type = track.value(QStringLiteral("musicVideoType")).toString();
  if (type.isEmpty())
    return 2;
  return type == QLatin1String("MUSIC_VIDEO_TYPE_UGC") ? 1 : 0;
}

} // namespace

bool isSameSong(const QVariantMap &a, const QVariantMap &b) {
  const QString id = a.value(QStringLiteral("id")).toString();
  if (!id.isEmpty() && id == b.value(QStringLiteral("id")).toString())
    return true;
  const QString title = titleKey(a.value(QStringLiteral("title")).toString());
  if (title.isEmpty() || title != titleKey(b.value(QStringLiteral("title")).toString()))
    return false;
  const QString artistA = artistOf(a);
  const QString artistB = artistOf(b);
  if (textKey(artistA) != textKey(artistB) && primaryArtistKey(artistA) != primaryArtistKey(artistB))
    return false;
  // Music videos pad intros and skits, so length only separates two cuts of the same kind.
  if (isMusicVideo(a) != isMusicVideo(b) && (isAudioOnly(a) || isAudioOnly(b)))
    return true;
  // A large length gap is more likely an extended, acoustic or live cut than an alternate upload.
  const double durA = a.value(QStringLiteral("durationSeconds")).toDouble();
  const double durB = b.value(QStringLiteral("durationSeconds")).toDouble();
  return durA <= 0 || durB <= 0 || std::abs(durA - durB) <= kMaxVariantDriftSeconds;
}

QList<QVariantMap> select(const QList<QVariantMap> &existing, const QList<QVariantMap> &candidates, int limit) {
  QList<QVariantMap> selected;
  if (limit <= 0)
    return selected;
  for (const QVariantMap &candidate : candidates) {
    if (candidate.value(QStringLiteral("id")).toString().isEmpty())
      continue;
    bool known = false;
    for (const QVariantMap &track : existing)
      if (isSameSong(track, candidate)) {
        known = true;
        break;
      }
    if (known)
      continue;
    int duplicate = -1;
    for (int i = 0; i < selected.size() && duplicate < 0; ++i)
      if (isSameSong(selected.at(i), candidate))
        duplicate = i;
    if (duplicate >= 0) {
      // Keep the recommendation position while swapping in the album cut.
      if (versionPreference(candidate) > versionPreference(selected.at(duplicate)))
        selected[duplicate] = candidate;
    } else if (selected.size() < limit) {
      selected.append(candidate);
    }
  }
  return selected;
}

bool isGeneratedMix(const QString &playlistId) {
  return (playlistId.startsWith(QLatin1String("VL")) ? playlistId.mid(2) : playlistId).startsWith(QLatin1String("RD"));
}

QVariantList collapseMix(const QVariantList &tracks, const QList<QVariantMap> &existing, int pinned) {
  QVariantList out = tracks.mid(0, pinned);
  QList<QVariantMap> known = existing;
  QList<QVariantMap> candidates;
  for (const auto &value : out)
    known.append(value.toMap());
  for (const auto &value : tracks.mid(pinned))
    candidates.append(value.toMap());
  for (const auto &track : select(known, candidates, int(candidates.size())))
    out.append(track);
  return out;
}

QList<int> duplicateGeneratedIndices(const QList<QVariantMap> &queue) {
  QList<int> result;
  QList<QVariantMap> kept;
  for (int i = 0; i < queue.size(); ++i) {
    bool duplicate = false;
    if (queue.at(i).value(QStringLiteral("autoplayGenerated")).toBool())
      for (const QVariantMap &track : kept)
        if (isSameSong(track, queue.at(i))) {
          duplicate = true;
          break;
        }
    if (duplicate)
      result.append(i);
    else
      kept.append(queue.at(i));
  }
  return result;
}

} // namespace autoplay
