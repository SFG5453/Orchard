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

#include <QByteArray>
#include <QJsonValue>
#include <QString>
#include <QVariantList>
#include <QVariantMap>

#include <functional>

// Another device's player, mirrored while this desktop is its Connect
// controller. The target stays authoritative: reads come from its last state
// and every action becomes a command it may apply or refuse.
class PlaybackRemote {
public:
  virtual ~PlaybackRemote() = default;
  [[nodiscard]] virtual bool active() const = 0;
  [[nodiscard]] virtual QVariantMap track() const = 0;
  [[nodiscard]] virtual QVariantList queue() const = 0;
  [[nodiscard]] virtual bool playing() const = 0;
  [[nodiscard]] virtual bool loading() const = 0;
  // Projected from the last state with the local clock.
  [[nodiscard]] virtual double position() const = 0;
  [[nodiscard]] virtual double duration() const = 0;
  [[nodiscard]] virtual bool shuffle() const = 0;
  [[nodiscard]] virtual QString repeat() const = 0;
  // `args` carries desktop-shaped tracks; the remote converts them.
  virtual void command(const QString &action, const QVariantMap &args = {}) = 0;
};

// A provider session on another Connect device (its Provider Host). Credentials
// stay there; this side only sees opaque playback ids and decrypted bytes.
class RemoteProvider {
public:
  using Reply = std::function<void(const QJsonValue &result, const QString &error)>;
  using Bytes = std::function<void(const QByteArray &bytes, const QString &error)>;

  virtual ~RemoteProvider() = default;
  [[nodiscard]] virtual bool available(const QString &provider) const = 0;
  virtual void resolveTrack(const QString &provider, const QVariantMap &track, Reply done) = 0;
  virtual void readRange(const QString &provider, const QString &playbackId, qint64 start, qint64 end,
                         Bytes done) = 0;
  virtual void report(const QString &provider, const QString &playbackId, bool started, double position) = 0;
};
