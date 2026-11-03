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
#pragma once

#include "audio_stream_proxy.h"
#include "fmp4_index.h"

#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QUrl>

class QNetworkReply;

// Serves an Apple motion MP4 with a synthesized sidx so playback starts after one request.
// Falls back to the plain file URL whenever the layout is not one it can index.
class AppleMotionStream final : public QObject {
  Q_OBJECT
  // File URL the current source belongs to.
  Q_PROPERTY(QString file READ file NOTIFY changed)
  // Empty while the head is fetched.
  Q_PROPERTY(QUrl source READ source NOTIFY changed)
public:
  explicit AppleMotionStream(QObject *parent = nullptr);

  [[nodiscard]] QString file() const { return m_file; }
  [[nodiscard]] QUrl source() const { return m_source; }

  // `playlist` is the media playlist that addresses `file` by byte range.
  Q_INVOKABLE void open(const QString &file, const QString &playlist);
  Q_INVOKABLE void clear();

signals:
  void changed();

private:
  void receive(QNetworkReply *reply, const fmp4index::Layout &layout);
  void settle(const QUrl &source);

  QNetworkAccessManager m_network;
  AudioStreamProxy m_proxy;
  QPointer<QNetworkReply> m_reply;
  QString m_file;
  QUrl m_source;
};
