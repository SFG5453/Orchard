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

// Screenshots and diagnostics for bug reports. The snapshot is taken before
// the report popup opens, so it shows the screen the problem was on.

#include "support/support_center.h"

#include <QBuffer>
#include <QImageReader>
#include <QJsonDocument>
#include <QLocale>
#include <QQuickWindow>
#include <QScreen>
#include <QSGRendererInterface>
#include <QSysInfo>

#include <limits>

#ifndef ORCHARD_VERSION
#define ORCHARD_VERSION "dev"
#endif

namespace {
// Phone photos and 8K screens alike get scaled down before encoding.
constexpr int maxEdge = 3840;
constexpr qint64 maxSourcePixels = 50'000'000;

QString graphicsApiName(QQuickWindow *window) {
  if (!window || !window->rendererInterface())
    return QStringLiteral("unknown");
  switch (window->rendererInterface()->graphicsApi()) {
  case QSGRendererInterface::OpenGL: return QStringLiteral("OpenGL");
  case QSGRendererInterface::Vulkan: return QStringLiteral("Vulkan");
  case QSGRendererInterface::Direct3D11: return QStringLiteral("Direct3D 11");
  case QSGRendererInterface::Direct3D12: return QStringLiteral("Direct3D 12");
  case QSGRendererInterface::Metal: return QStringLiteral("Metal");
  case QSGRendererInterface::Software: return QStringLiteral("Software");
  default: return QStringLiteral("other");
  }
}

QByteArray encode(const QImage &image, const char *format, int quality) {
  QByteArray bytes;
  QBuffer buffer(&bytes);
  buffer.open(QIODevice::WriteOnly);
  image.save(&buffer, format, quality);
  return bytes;
}
} // namespace

QByteArray SupportCenter::encodeScreenshot(const QImage &source, QByteArray *mimeType) {
  if (source.isNull())
    return {};
  QImage image = source.width() > maxEdge || source.height() > maxEdge
      ? source.scaled(maxEdge, maxEdge, Qt::KeepAspectRatio, Qt::SmoothTransformation)
      : source;
  // Screens are mostly flat colour and text, where PNG is both smaller and sharper.
  QByteArray bytes = encode(image, "PNG", -1);
  if (!bytes.isEmpty() && bytes.size() <= maxScreenshotBytes) {
    *mimeType = "image/png";
    return bytes;
  }
  image = image.convertToFormat(QImage::Format_RGB32);
  for (int attempt = 0; attempt < 4; ++attempt) {
    bytes = encode(image, "JPEG", 88);
    if (!bytes.isEmpty() && bytes.size() <= maxScreenshotBytes) {
      *mimeType = "image/jpeg";
      return bytes;
    }
    image = image.scaled(image.size() * 0.75, Qt::KeepAspectRatio, Qt::SmoothTransformation);
  }
  return {};
}

QString SupportCenter::screenshotUrl() const {
  return m_screenshot.isNull() ? QString() : QStringLiteral("image://support/screenshot/%1").arg(m_imageSerial);
}

QString SupportCenter::snapshotUrl() const {
  return m_snapshot.isNull() ? QString() : QStringLiteral("image://support/snapshot/%1").arg(m_imageSerial);
}

QString SupportCenter::screenshotInfo() const {
  if (m_screenshot.isNull())
    return {};
  return tr("%1 × %2").arg(m_screenshot.width()).arg(m_screenshot.height());
}

void SupportCenter::setScreenshot(const QImage &image) {
  m_screenshot = image;
  ++m_imageSerial;
  emit screenshotChanged();
}

void SupportCenter::takeSnapshot(QQuickWindow *window, const QString &page) {
  if (!window)
    return;
  updateDiagnostics(window, page);
  // A draft that already has a picture keeps it; the suggestion is for fresh reports.
  if (!m_screenshot.isNull())
    return;
  m_snapshot = window->grabWindow();
  ++m_imageSerial;
  emit screenshotChanged();
}

void SupportCenter::captureWindow(QQuickWindow *window, const QString &page) {
  if (!window)
    return;
  updateDiagnostics(window, page);
  m_snapshot = QImage();
  setScreenshot(window->grabWindow());
}

void SupportCenter::attachSnapshot() {
  if (m_snapshot.isNull())
    return;
  const QImage snapshot = std::exchange(m_snapshot, QImage());
  setScreenshot(snapshot);
}

bool SupportCenter::attachFile(const QUrl &url) {
  QImageReader reader(url.toLocalFile());
  reader.setAutoTransform(true);
  const QSize size = reader.size();
  if (!size.isValid() || qint64(size.width()) * size.height() > maxSourcePixels) {
    setError(tr("That file is not an image Orchard can read."));
    return false;
  }
  // Decoding and re-encoding also drops EXIF data such as GPS tags.
  const QImage image = reader.read();
  if (image.isNull()) {
    setError(tr("That file is not an image Orchard can read."));
    return false;
  }
  setError(QString());
  m_snapshot = QImage();
  setScreenshot(image);
  return true;
}

void SupportCenter::clearScreenshot() {
  setScreenshot(QImage());
}

void SupportCenter::updateDiagnostics(QQuickWindow *window, const QString &page) {
  // Public on GitHub: hardware and versions only, nothing about the listener.
  QJsonObject info{
      {QStringLiteral("app"), QStringLiteral("Orchard %1").arg(QStringLiteral(ORCHARD_VERSION))},
      {QStringLiteral("os"), QSysInfo::prettyProductName()},
      {QStringLiteral("kernel"), QSysInfo::kernelType() + QLatin1Char(' ') + QSysInfo::kernelVersion()},
      {QStringLiteral("arch"), QSysInfo::currentCpuArchitecture()},
      {QStringLiteral("qt"), QString::fromLatin1(qVersion())},
      {QStringLiteral("graphics"), graphicsApiName(window)},
      {QStringLiteral("locale"), QLocale::system().name()},
  };
  if (window) {
    info.insert(QStringLiteral("window"), QStringLiteral("%1x%2 @%3x").arg(window->width()).arg(window->height())
                                              .arg(window->effectiveDevicePixelRatio()));
    if (window->screen())
      info.insert(QStringLiteral("screen"), QStringLiteral("%1x%2").arg(window->screen()->size().width())
                                                .arg(window->screen()->size().height()));
  }
  if (!page.isEmpty())
    info.insert(QStringLiteral("page"), page);
  m_diagnostics = QString::fromUtf8(QJsonDocument(info).toJson(QJsonDocument::Indented)).trimmed();
  emit diagnosticsChanged();
}

QImage SupportImageProvider::fitWithin(const QImage &image, const QSize &requestedSize) {
  // QML sends 0 for an unset sourceSize dimension; 0 means no limit, not zero pixels.
  const int width = requestedSize.width() > 0 ? requestedSize.width() : std::numeric_limits<int>::max();
  const int height = requestedSize.height() > 0 ? requestedSize.height() : std::numeric_limits<int>::max();
  if (image.isNull() || (image.width() <= width && image.height() <= height))
    return image;
  return image.scaled(width, height, Qt::KeepAspectRatio, Qt::SmoothTransformation);
}

QImage SupportImageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize) {
  const QImage image = id.startsWith(QStringLiteral("snapshot/")) ? m_support->snapshot() : m_support->screenshot();
  if (size)
    *size = image.size();
  return fitWithin(image, requestedSize);
}
