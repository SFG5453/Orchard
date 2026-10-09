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

#include "animated_artwork_service.h"

#include <QRegularExpression>

QString AnimatedArtworkService::normalizeText(const QString &value) {
  // Normalization: making song titles comparable since 2026, or whenever the
  // hell Unicode was invented.
  return value.toLower()
      .replace(QRegularExpression(QStringLiteral("[^\\p{L}\\p{N}\\s]")),
               QStringLiteral(" "))
      .simplified();
}

bool AnimatedArtworkService::looseMatches(const QString &left,
                                          const QString &right) {
  const QString l = normalizeText(left);
  const QString r = normalizeText(right);
  if (l.isEmpty() || r.isEmpty())
    return false;
  return l == r || l.contains(r) || r.contains(l);
}

QString AnimatedArtworkService::stripEdition(const QString &value) {
  static const QRegularExpression brackets(
      QStringLiteral("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"));
  static const QRegularExpression dashSuffix(
      QStringLiteral("\\s+-\\s+[^-]*\\b(single|ep|deluxe|edition|version|"
                     "remaster(ed)?|expanded|anniversary|bonus)\\b.*$"),
      QRegularExpression::CaseInsensitiveOption);
  QString copy = value;
  const QString stripped =
      normalizeText(copy.remove(brackets).remove(dashSuffix));
  // A title that is entirely brackets still needs something to compare.
  return stripped.isEmpty() ? normalizeText(value) : stripped;
}

bool AnimatedArtworkService::editionlessMatches(const QString &left,
                                                const QString &right) {
  const QString l = stripEdition(left);
  const QString r = stripEdition(right);
  return !l.isEmpty() && l == r;
}

QString AnimatedArtworkService::cacheKey(const QString &title,
                                         const QString &artist,
                                         const QString &album) const {
  const QString t = normalizeText(title);
  const QString a = normalizeText(artist);
  const QString al = normalizeText(album);
  return QStringLiteral("%1::%2::%3").arg(t, a, al);
}
