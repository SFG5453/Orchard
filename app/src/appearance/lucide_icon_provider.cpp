#include "appearance/lucide_icon_provider.h"

#include <QColor>
#include <QImageReader>
#include <QPainter>
#include <QRegularExpression>

QImage LucideIconProvider::requestImage(const QString &id, QSize *size,
                                        const QSize &requestedSize) {
  const qsizetype split = id.indexOf(QLatin1Char('?'));
  const QString name = split < 0 ? id : id.left(split);
  static const QRegularExpression validName(QStringLiteral("^[a-z0-9-]+$"));
  if (!validName.match(name).hasMatch())
    return {};

  QColor color(Qt::white);
  if (split >= 0) {
    const QColor parsed(QLatin1Char('#') + id.mid(split + 1));
    if (parsed.isValid())
      color = parsed;
  }

  QImageReader reader(QStringLiteral(":/qt/qml/Orchard/app/qml/assets/lucide/%1.svg").arg(name));
  if (requestedSize.isValid() && !requestedSize.isEmpty())
    reader.setScaledSize(reader.size().scaled(requestedSize, Qt::KeepAspectRatio));
  QImage image = reader.read();
  if (image.isNull())
    return {};

  // The SVGs are stroked white; SourceIn keeps their coverage and swaps the colour.
  image = image.convertToFormat(QImage::Format_ARGB32_Premultiplied);
  QPainter painter(&image);
  painter.setCompositionMode(QPainter::CompositionMode_SourceIn);
  painter.fillRect(image.rect(), color);
  painter.end();

  if (size)
    *size = image.size();
  return image;
}
