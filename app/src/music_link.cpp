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

#include "music_link.h"

#include <QRegularExpression>
#include <QUrl>
#include <QUrlQuery>

std::optional<MusicLink> parseMusicLink(const QString &input) {
  if (input.size() > 8192)
    return std::nullopt;
  const QUrl url(input.trimmed(), QUrl::StrictMode);
  if (!url.isValid() || !url.userInfo().isEmpty() || url.port() != -1)
    return std::nullopt;
  const QString scheme = url.scheme().toLower();
  QString host = url.host().toLower();
  QString path = url.path();
  if (scheme == QLatin1String("orchard")) {
    path = QLatin1Char('/') + host + path;
    host = QStringLiteral("music.youtube.com");
  } else if (scheme != QLatin1String("https") && scheme != QLatin1String("http")) {
    return std::nullopt;
  }
  const QUrlQuery query(url);
  const QStringList parts = path.split(QLatin1Char('/'), Qt::SkipEmptyParts);
  MusicLink link{MusicLink::Kind::Song, {}};
  if (host == QLatin1String("youtu.be") || host == QLatin1String("www.youtu.be")) {
    if (parts.size() != 1)
      return std::nullopt;
    link.id = parts.first();
  } else if (host == QLatin1String("youtube.com") || host == QLatin1String("www.youtube.com") ||
             host == QLatin1String("music.youtube.com") || host == QLatin1String("m.youtube.com")) {
    if (path == QLatin1String("/watch")) {
      link.id = query.queryItemValue(QStringLiteral("v"));
    } else if (path == QLatin1String("/playlist")) {
      link.kind = MusicLink::Kind::Playlist;
      link.id = query.queryItemValue(QStringLiteral("list"));
      if (link.id.startsWith(QLatin1String("VL")))
        link.id.remove(0, 2);
    } else if (parts.size() == 2 && parts.first() == QLatin1String("browse") &&
               parts.last().startsWith(QLatin1String("MPRE"))) {
      link.kind = MusicLink::Kind::Album;
      link.id = parts.last();
    } else if (parts.size() == 2 &&
               (parts.first() == QLatin1String("shorts") || parts.first() == QLatin1String("embed") ||
                parts.first() == QLatin1String("live"))) {
      link.id = parts.last();
    } else {
      return std::nullopt;
    }
  } else {
    return std::nullopt;
  }
  static const QRegularExpression songId(QStringLiteral("^[A-Za-z0-9_-]{11}$"));
  static const QRegularExpression collectionId(QStringLiteral("^[A-Za-z0-9_-]{1,256}$"));
  if (!(link.kind == MusicLink::Kind::Song ? songId : collectionId).match(link.id).hasMatch())
    return std::nullopt;
  return link;
}
