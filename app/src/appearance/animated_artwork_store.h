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

#include <QSqlDatabase>
#include <QString>
#include <optional>

// Persists resolved animated-artwork lookups so repeat plays skip the mirrors.
// Every row can be fetched again, so it lives in the cache directory.
class AnimatedArtworkStore final {
public:
  static constexpr qint64 kFoundTtlSecs = 30LL * 24 * 60 * 60;
  // Labels add motion art after release, so a "none" answer expires sooner.
  static constexpr qint64 kMissingTtlSecs = 7LL * 24 * 60 * 60;

  // Empty path means <CacheLocation>/animated-artwork.sqlite.
  explicit AnimatedArtworkStore(const QString &path = QString());
  ~AnimatedArtworkStore();
  AnimatedArtworkStore(const AnimatedArtworkStore &) = delete;
  AnimatedArtworkStore &operator=(const AnimatedArtworkStore &) = delete;

  // Unexpired result for key; an empty string means "no motion art".
  [[nodiscard]] std::optional<QString> lookup(const QString &key) const;
  void store(const QString &key, const QString &url, const QString &source,
             qint64 checkedAtSecs = -1);
  // Drops every row pointing at url. Returns how many went.
  int forgetUrl(const QString &url);
  // Drops every "no motion art" answer, e.g. after a new mirror becomes usable.
  int forgetMisses();

private:
  void prune();

  QString m_connection;
  mutable QSqlDatabase m_db;
};
