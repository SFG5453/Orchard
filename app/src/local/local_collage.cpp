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

#include "local_collage.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFileInfo>
#include <QImageReader>
#include <QPainter>
#include <QSet>

namespace local {
namespace {

// Center-crops to a square first, so a wide cover is not squashed into a
// tile and does not make the singer look like a funhouse mirror.
QImage squareTile(const QImage &source, int side) {
  const int edge = qMin(source.width(), source.height());
  const QImage cropped = source.copy((source.width() - edge) / 2, (source.height() - edge) / 2, edge, edge);
  return cropped.scaled(side, side, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
}

} // namespace

QImage buildCollage(const QList<QImage> &covers, int side) {
  QList<QImage> usable;
  for (const QImage &cover : covers) {
    if (!cover.isNull() && usable.size() < 4)
      usable.append(cover);
  }
  if (usable.isEmpty() || side < 2)
    return {};

  QImage canvas(side, side, QImage::Format_RGB32);
  canvas.fill(QColor(0x18, 0x1b, 0x1e));
  QPainter painter(&canvas);
  painter.setRenderHint(QPainter::SmoothPixmapTransform);
  if (usable.size() == 1) {
    painter.drawImage(0, 0, squareTile(usable.first(), side));
    return canvas;
  }
  const int half = side / 2;
  for (int cell = 0; cell < 4; ++cell) {
    // Cells past the end of the list repeat from the start; three covers
    // leave one cell, and an empty square would look like a bug.
    const QImage &cover = usable.at(cell < usable.size() ? cell : cell % usable.size());
    painter.drawImage((cell % 2) * half, (cell / 2) * half, squareTile(cover, half));
  }
  return canvas;
}

QStringList collageSources(const QStringList &coverPaths) {
  QStringList sources;
  QSet<QString> seen;
  for (const QString &path : coverPaths) {
    if (path.isEmpty() || seen.contains(path) || !QFileInfo::exists(path))
      continue;
    seen.insert(path);
    sources.append(path);
    if (sources.size() == 4)
      break;
  }
  return sources;
}

QString collageKey(const QStringList &sources) {
  QCryptographicHash hash(QCryptographicHash::Sha1);
  for (const QString &path : sources) {
    hash.addData(path.toUtf8());
    // Replacing a cover in place must also change the key.
    hash.addData(QByteArray::number(QFileInfo(path).lastModified().toMSecsSinceEpoch()));
  }
  return QString::fromLatin1(hash.result().toHex().left(10));
}

bool writeCollage(const QStringList &coverPaths, const QString &outFile, int side) {
  QList<QImage> images;
  for (const QString &path : collageSources(coverPaths)) {
    QImageReader reader(path);
    reader.setAutoTransform(true);
    const QImage image = reader.read();
    if (!image.isNull())
      images.append(image);
  }
  const QImage collage = buildCollage(images, side);
  if (collage.isNull())
    return false;
  QDir().mkpath(QFileInfo(outFile).absolutePath());
  return collage.save(outFile, "JPG", 90);
}

} // namespace local
