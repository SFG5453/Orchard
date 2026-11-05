/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#pragma once

#include <QObject>
#include <QPointer>
#include <QSize>
#include <QUrl>
#include <QtQml/qqmlregistration.h>

class QNetworkReply;

// Reads image headers without allocating decoded pixels.
class ImageDimensions : public QObject {
  Q_OBJECT
  QML_ELEMENT
  Q_PROPERTY(QUrl source READ source WRITE setSource NOTIFY sourceChanged)
  Q_PROPERTY(QSize size READ size NOTIFY sizeChanged)
public:
  explicit ImageDimensions(QObject *parent = nullptr) : QObject(parent) {}
  ~ImageDimensions() override;
  QUrl source() const { return m_source; }
  QSize size() const { return m_size; }
  void setSource(const QUrl &source);
signals:
  void sourceChanged();
  void sizeChanged();
private:
  void cancel();
  void publish(QSize size);
  QUrl m_source;
  QSize m_size;
  QPointer<QNetworkReply> m_reply;
  QByteArray m_header;
};
