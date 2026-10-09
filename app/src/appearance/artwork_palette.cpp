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

#include "artwork_palette.h"
#include "appearance/artwork_sampler.h"

#include <QFile>
#include <QFutureWatcher>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QtConcurrent/QtConcurrentRun>

namespace {

// Shared across instances so skipping back to a track recolors instantly.
QHash<QString, QVariantMap> &paletteCache() {
  static QHash<QString, QVariantMap> cache;
  return cache;
}

constexpr qsizetype kPaletteCacheLimit = 64;

} // namespace

ArtworkPalette::ArtworkPalette(QObject *parent) : QObject(parent) {}

ArtworkPalette::~ArtworkPalette() { abortReply(); }

void ArtworkPalette::setSource(const QUrl &source) {
  if (source == m_source)
    return;
  m_source = source;
  emit sourceChanged();

  abortReply();
  const quint64 generation = ++m_generation;
  if (source.isEmpty()) {
    publish({}, false);
    return;
  }

  const QString key = source.toString();
  const auto cached = paletteCache().constFind(key);
  if (cached != paletteCache().constEnd()) {
    publish(*cached, true);
    return;
  }

  // Keep the previous palette until the new one lands so colors glide instead
  // of flashing back to the fallback.
  if (source.isLocalFile()) {
    QFile file(source.toLocalFile());
    if (file.open(QIODevice::ReadOnly))
      sample(file.readAll(), generation);
    return;
  }

  QNetworkRequest request(source);
  request.setRawHeader(
      "Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8");
  request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                       QNetworkRequest::NoLessSafeRedirectPolicy);
  QNetworkReply *reply = m_network.get(request);
  m_reply = reply;
  connect(reply, &QNetworkReply::finished, this, [this, reply, generation] {
    reply->deleteLater();
    if (m_reply != reply || generation != m_generation)
      return;
    m_reply = nullptr;
    if (reply->error() != QNetworkReply::NoError)
      return;
    sample(reply->readAll(), generation);
  });
}

void ArtworkPalette::sample(QByteArray bytes, quint64 generation) {
  if (bytes.isEmpty())
    return;
  const QString key = m_source.toString();
  auto *watcher = new QFutureWatcher<QVariantMap>(this);
  connect(watcher, &QFutureWatcher<QVariantMap>::finished, this,
          [this, watcher, generation, key] {
            const QVariantMap palette = watcher->result();
            watcher->deleteLater();
            if (palette.isEmpty())
              return;
            auto &cache = paletteCache();
            if (cache.size() >= kPaletteCacheLimit)
              cache.clear();
            cache.insert(key, palette);
            if (generation == m_generation)
              publish(palette, true);
          });
  watcher->setFuture(
      QtConcurrent::run([bytes = std::move(bytes)]() -> QVariantMap {
        return sampleArtwork(bytes).value(QStringLiteral("palette")).toObject().toVariantMap();
      }));
}

void ArtworkPalette::publish(const QVariantMap &palette, bool ready) {
  if (palette == m_palette && ready == m_ready)
    return;
  m_palette = palette;
  m_ready = ready;
  emit paletteChanged();
}

void ArtworkPalette::abortReply() {
  QNetworkReply *reply = m_reply;
  m_reply = nullptr;
  if (reply)
    reply->abort();
}
