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

// Apple's own album motion artwork, used in place of the unofficial mirrors while the
// listener is signed in to Apple Music.
#include "animated_artwork_lookup.h"
#include "appearance_settings.h"
#include "integrations/apple_music.h"
#include "integrations/catalog_source.h"

#include <QJsonObject>

void AnimatedArtworkService::setAppleMusic(AppleMusic *appleMusic, CatalogSource *source) {
  m_appleMusic = appleMusic;
  m_source = source;
}

bool AnimatedArtworkService::appleActive() const {
  return m_appleMusic && m_source && m_source->apple() && m_appleMusic->connected();
}

void AnimatedArtworkService::applyAppleSource(QStringList &mirrors, bool album) const {
  if (!appleActive())
    return;
  // Everything but Spotify Canvas is an unofficial Apple mirror.
  QStringList replaced;
  bool added = false;
  for (const QString &mirror : mirrors) {
    if (mirror == QStringLiteral("spotify")) {
      replaced.append(mirror);
    } else if (!added) {
      replaced.append(album ? QStringLiteral("apple_music_album") : QStringLiteral("apple_music"));
      added = true;
    }
  }
  mirrors = replaced;
}

void AnimatedArtworkService::fetchApple(std::shared_ptr<LookupState> state, bool album) {
  if (!appleActive()) {
    queryNextMirror(state);
    return;
  }
  QJsonObject query{
      {QStringLiteral("title"), state->title},
      {QStringLiteral("artist"), state->artist},
      {QStringLiteral("album"), state->album},
  };
  if (album)
    query.insert(QStringLiteral("kind"), QStringLiteral("album"));
  m_appleMusic->artwork(query, [this, state](const QJsonObject &result, const QString &error) {
    if (!error.isEmpty())
      state->mirrorFailed = true;
    // The square loop suits Orchard's square cover frames.
    QString motion = result.value(QStringLiteral("animated")).toString().trimmed();
    if (motion.isEmpty())
      motion = result.value(QStringLiteral("animatedVertical")).toString().trimmed();
    if (motion.isEmpty())
      queryNextMirror(state);
    else
      acceptMotionUrl(motion, state);
  });
}

void AnimatedArtworkService::resolveTallArtwork(const QString &title, const QString &artist, const QString &album,
                                                std::function<void(const QString &)> done) {
  if (!appleActive() || title.isEmpty()) {
    done({});
    return;
  }
  const QJsonObject query{
      {QStringLiteral("title"), title},
      {QStringLiteral("artist"), artist},
      {QStringLiteral("album"), album},
  };
  m_appleMusic->artwork(query, [done = std::move(done)](const QJsonObject &result, const QString &) {
    done(result.value(QStringLiteral("animatedVertical")).toString().trimmed());
  });
}

void AnimatedArtworkService::requestTallVideo(const QString &title, const QString &artist, const QString &album) {
  const auto answer = [this, title, artist](const QString &url) { emit tallVideoResolved(title, artist, url); };
  if (!m_settings || !m_settings->animatedArtworkEnabled() || !m_settings->fullscreenCanvasStage()) {
    answer({});
    return;
  }
  const QString key = QStringLiteral("tall:") + cacheKey(title, artist, album);
  if (const auto hit = m_cache.constFind(key); hit != m_cache.constEnd()) {
    answer(*hit);
    return;
  }
  resolveTallArtwork(title, artist, album, [this, key, answer](const QString &hls) {
    if (hls.isEmpty()) {
      answer({});
      return;
    }
    resolveHlsVariant(hls, nullptr, [this, key, answer](const QString &mp4) {
      m_cache.insert(key, mp4);
      answer(mp4);
    });
  });
}
