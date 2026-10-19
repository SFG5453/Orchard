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

#include "apple_youtube_match.h"

#include <QRegularExpression>
#include <QSet>
#include <QStringList>
#include <algorithm>
#include <cmath>
#include <vector>

namespace appleyt {
namespace {

// Uploads often differ by a few seconds of silence; past this it is another edit. The page
// checks the audio itself before mixing, so the limit only weeds out obvious mismatches.
constexpr double kMaxDrift = 12.0;
constexpr double kMinTitleSimilarity = 0.85;

const QStringList &versionWords() {
  static const QStringList words{
      QStringLiteral("remix"),   QStringLiteral("live"),       QStringLiteral("acoustic"),
      QStringLiteral("instrumental"), QStringLiteral("sped up"), QStringLiteral("slowed"),
      QStringLiteral("karaoke"), QStringLiteral("cover"),      QStringLiteral("radio edit"),
      QStringLiteral("clean"),   QStringLiteral("demo"),       QStringLiteral("remaster"),
      QStringLiteral("version"), QStringLiteral("edit"),       QStringLiteral("mix")};
  return words;
}

QString norm(const QString &text) {
  QString folded;
  for (const QChar c : text.normalized(QString::NormalizationForm_KD)) {
    if (c.unicode() < 128)
      folded += c.toLower();
  }
  static const QRegularExpression nonWord(QStringLiteral("[^a-z0-9 ]+"));
  return folded.replace(nonWord, QStringLiteral(" ")).simplified();
}

// The song name without featured credits, which services format differently.
QString baseTitle(const QString &title) {
  static const QRegularExpression credit(QStringLiteral(R"([(\[][^)\]]*\b(feat|ft|with)\b\.?[^)\]]*[)\]])"),
                                         QRegularExpression::CaseInsensitiveOption);
  static const QRegularExpression trailing(QStringLiteral(R"(\s(feat|ft)\.?\s.*$)"),
                                           QRegularExpression::CaseInsensitiveOption);
  QString stripped = title;
  stripped.remove(credit);
  stripped.remove(trailing);
  return norm(stripped);
}

QSet<QString> versionTags(const QString &title) {
  const QString text = norm(title);
  QSet<QString> tags;
  for (const QString &word : versionWords()) {
    if (QRegularExpression(QStringLiteral("\\b%1\\b").arg(word)).match(text).hasMatch())
      tags.insert(word);
  }
  return tags;
}

QSet<QString> artistNames(const QString &text) {
  static const QRegularExpression separators(
      QStringLiteral(R"(\s*(?:,|&|\band\b|\bx\b|\bfeat\.?|\bft\.?|\bwith\b)\s*)"),
      QRegularExpression::CaseInsensitiveOption);
  QSet<QString> names;
  for (const QString &part : text.split(separators, Qt::SkipEmptyParts)) {
    if (const QString name = norm(part); !name.isEmpty())
      names.insert(name);
  }
  return names;
}

const QRegularExpression &featuredCredit() {
  static const QRegularExpression featured(QStringLiteral(R"(\b(?:feat|ft|with)\b\.?\s([^)\]]+))"),
                                           QRegularExpression::CaseInsensitiveOption);
  return featured;
}

// Credited artists, including guests named only in the title.
QSet<QString> creditedArtists(const QVariantMap &item) {
  QStringList parts{item.value(QStringLiteral("artist")).toString()};
  for (const QVariant &artist : item.value(QStringLiteral("artists")).toList())
    parts << artist.toString();
  QSet<QString> names = artistNames(parts.join(QStringLiteral(", ")));
  for (auto it = featuredCredit().globalMatch(item.value(QStringLiteral("title")).toString()); it.hasNext();)
    names.unite(artistNames(it.next().captured(1)));
  return names;
}

// Edit-distance similarity in 0..1.
double similarity(const QString &a, const QString &b) {
  if (a.isEmpty() && b.isEmpty())
    return 1.0;
  std::vector<int> row(b.size() + 1);
  for (int j = 0; j <= b.size(); ++j)
    row[j] = j;
  for (int i = 1; i <= a.size(); ++i) {
    int diagonal = row[0];
    row[0] = i;
    for (int j = 1; j <= b.size(); ++j) {
      const int above = row[j];
      row[j] = std::min({row[j] + 1, row[j - 1] + 1, diagonal + (a[i - 1] == b[j - 1] ? 0 : 1)});
      diagonal = above;
    }
  }
  return 1.0 - double(row[b.size()]) / std::max(a.size(), b.size());
}

double seconds(const QVariantMap &item) {
  const double direct = item.value(QStringLiteral("durationSeconds")).toDouble();
  if (direct > 0)
    return direct;
  double total = 0;
  for (const QString &part : item.value(QStringLiteral("duration")).toString().split(QLatin1Char(':'))) {
    bool ok = false;
    const double value = part.trimmed().toDouble(&ok);
    if (!ok)
      return 0;
    total = total * 60 + value;
  }
  return total;
}

} // namespace

QString searchQuery(const QVariantMap &appleSong) {
  const QString title = appleSong.value(QStringLiteral("title")).toString();
  return (baseTitle(title).isEmpty() ? title : baseTitle(title)) + QLatin1Char(' ') +
         appleSong.value(QStringLiteral("artist")).toString();
}

QJsonArray songCandidates(const QJsonObject &searchResult) {
  for (const QJsonValue &section : searchResult.value(QStringLiteral("sections")).toArray()) {
    const QJsonObject object = section.toObject();
    if (object.value(QStringLiteral("key")).toString() == QStringLiteral("songs"))
      return object.value(QStringLiteral("items")).toArray();
  }
  return {};
}

double playCount(const QString &text) {
  static const QRegularExpression count(QStringLiteral(R"(([\d.,]+)\s*([KMB])?)"),
                                        QRegularExpression::CaseInsensitiveOption);
  const auto match = count.match(text);
  if (!match.hasMatch())
    return 0;
  const double value = QString(match.captured(1)).remove(QLatin1Char(',')).toDouble();
  const QString unit = match.captured(2).toUpper();
  return value * (unit == QStringLiteral("K") ? 1e3 : unit == QStringLiteral("M") ? 1e6 : unit == QStringLiteral("B") ? 1e9 : 1);
}

double score(const QVariantMap &appleSong, const QVariantMap &candidate) {
  const double drift = std::abs(seconds(candidate) - seconds(appleSong));
  if (seconds(candidate) <= 0 || drift > kMaxDrift)
    return -1;
  const QString title = appleSong.value(QStringLiteral("title")).toString();
  const QString candidateTitle = candidate.value(QStringLiteral("title")).toString();
  if (versionTags(candidateTitle) != versionTags(title))
    return -1;
  // Clean and explicit cuts differ in the audio itself.
  if (candidate.contains(QStringLiteral("explicit")) &&
      candidate.value(QStringLiteral("explicit")).toBool() != appleSong.value(QStringLiteral("explicit")).toBool())
    return -1;
  const QSet<QString> appleArtists = creditedArtists(appleSong);
  // Remixers and guests Apple does not credit point at another release.
  for (auto it = featuredCredit().globalMatch(candidateTitle); it.hasNext();) {
    if (!(artistNames(it.next().captured(1)) - appleArtists).isEmpty())
      return -1;
  }
  const double titleSimilarity = similarity(baseTitle(title), baseTitle(candidateTitle));
  if (titleSimilarity < kMinTitleSimilarity)
    return -1;
  const QSet<QString> shared = appleArtists & creditedArtists(candidate);
  if (shared.isEmpty())
    return -1;
  const QString album = norm(appleSong.value(QStringLiteral("album")).toString());
  const double albumSimilarity =
      album.isEmpty() ? 0 : similarity(album, norm(candidate.value(QStringLiteral("album")).toString()));
  // Duplicate uploads tie on metadata; the main release carries the plays.
  const double plays = playCount(candidate.value(QStringLiteral("views")).toString());
  return titleSimilarity + double(shared.size()) / std::max<qsizetype>(1, appleArtists.size()) +
         0.5 * albumSimilarity - drift / 3 + std::log10(1 + plays) / 100;
}

QString bestVideoId(const QVariantMap &appleSong, const QJsonArray &candidates) {
  QString best;
  double bestScore = -1;
  for (const QJsonValue &value : candidates) {
    const QVariantMap candidate = value.toObject().toVariantMap();
    const QString id = candidate.value(QStringLiteral("id")).toString();
    const double candidateScore = score(appleSong, candidate);
    if (!id.isEmpty() && candidateScore > bestScore) {
      bestScore = candidateScore;
      best = id;
    }
  }
  return best;
}

} // namespace appleyt
