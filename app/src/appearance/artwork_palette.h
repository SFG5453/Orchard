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

#pragma once

#include <QHash>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QUrl>
#include <QVariantMap>
#include <QtQml/qqmlregistration.h>

class QNetworkReply;

// Samples a color-managed palette from an artwork URL for QML tinting.
class ArtworkPalette : public QObject {
  Q_OBJECT
  QML_ELEMENT
  Q_PROPERTY(QUrl source READ source WRITE setSource NOTIFY sourceChanged)
  Q_PROPERTY(QVariantMap palette READ palette NOTIFY paletteChanged)
  Q_PROPERTY(bool ready READ ready NOTIFY paletteChanged)
public:
  explicit ArtworkPalette(QObject *parent = nullptr);
  ~ArtworkPalette() override;
  QUrl source() const { return m_source; }
  QVariantMap palette() const { return m_palette; }
  bool ready() const { return m_ready; }
  void setSource(const QUrl &source);
signals:
  void sourceChanged();
  void paletteChanged();

private:
  void sample(QByteArray bytes, quint64 generation);
  void publish(const QVariantMap &palette, bool ready);
  void abortReply();

  QNetworkAccessManager m_network;
  QPointer<QNetworkReply> m_reply;
  QUrl m_source;
  QVariantMap m_palette;
  bool m_ready{false};
  quint64 m_generation{0};
};
