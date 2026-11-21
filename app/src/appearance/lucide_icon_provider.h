#pragma once

#include <QQuickImageProvider>

// Serves image://lucide/<name>?<rrggbb> as a pre-tinted raster so icons draw as
// plain textures that batch and share the pixmap cache.
class LucideIconProvider final : public QQuickImageProvider {
public:
  LucideIconProvider() : QQuickImageProvider(QQuickImageProvider::Image) {}

  QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
};
