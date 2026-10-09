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

#include "local_track.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFileInfo>

namespace local {

QString trackIdForPath(const QString &absolutePath) {
  const QString canonical = QDir::cleanPath(QFileInfo(absolutePath).absoluteFilePath());
  const QByteArray digest =
      QCryptographicHash::hash(canonical.toUtf8(), QCryptographicHash::Sha1).toHex();
  // Sixteen hex characters: plenty for a music folder, and short enough to read in a log.
  return kTrackPrefix + QString::fromLatin1(digest.left(16));
}

QString formatDuration(double seconds) {
  if (seconds <= 0)
    return QStringLiteral("0:00");
  const qint64 total = qRound64(seconds);
  const qint64 hours = total / 3600;
  const qint64 minutes = (total % 3600) / 60;
  const qint64 secs = total % 60;
  if (hours > 0)
    return QStringLiteral("%1:%2:%3").arg(hours).arg(minutes, 2, 10, QLatin1Char('0')).arg(secs, 2, 10, QLatin1Char('0'));
  return QStringLiteral("%1:%2").arg(minutes).arg(secs, 2, 10, QLatin1Char('0'));
}

} // namespace local
