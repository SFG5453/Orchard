/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#include "image_dimensions.h"

#include <QBuffer>
#include <QCache>
#include <QImageReader>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QQmlEngine>

namespace {
QCache<QUrl, QSize> &dimensions() {
  static QCache<QUrl, QSize> cache(128);
  return cache;
}
constexpr qsizetype kMaxHeaderBytes = 1024 * 1024;
}

ImageDimensions::~ImageDimensions() { cancel(); }

void ImageDimensions::cancel() {
  if (m_reply) {
    disconnect(m_reply, nullptr, this, nullptr);
    m_reply->abort();
    m_reply->deleteLater();
    m_reply = nullptr;
  }
  m_header = {};
}

void ImageDimensions::publish(QSize size) {
  if (size.isValid()) dimensions().insert(m_source, new QSize(size));
  if (m_size == size) return;
  m_size = size;
  emit sizeChanged();
}

void ImageDimensions::setSource(const QUrl &source) {
  if (source == m_source) return;
  cancel();
  m_source = source;
  publish({});
  emit sourceChanged();
  if (source.isEmpty()) return;
  if (const auto *cached = dimensions().object(source)) {
    publish(*cached);
    return;
  }
  if (source.isLocalFile() || source.scheme() == QStringLiteral("qrc")) {
    QImageReader reader(source.isLocalFile() ? source.toLocalFile() : ':' + source.path());
    publish(reader.size());
    return;
  }
  auto *engine = qmlEngine(this);
  if (!engine) return;
  QNetworkRequest request(source);
  request.setTransferTimeout(15000);
  auto *reply = engine->networkAccessManager()->get(request);
  m_reply = reply;
  reply->setReadBufferSize(kMaxHeaderBytes);
  connect(reply, &QNetworkReply::readyRead, this, [this, reply] {
    m_header += reply->read(kMaxHeaderBytes - m_header.size());
    QBuffer buffer(&m_header);
    buffer.open(QIODevice::ReadOnly);
    QImageReader reader(&buffer);
    const QSize size = reader.size();
    if (size.isValid()) {
      publish(size);
      cancel();
    } else if (m_header.size() >= kMaxHeaderBytes) {
      cancel();
    }
  });
  connect(reply, &QNetworkReply::finished, this, [this] { cancel(); });
}
