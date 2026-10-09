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

#include <QObject>

class BackendInfo final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool ready READ ready CONSTANT)
  Q_PROPERTY(quint32 abiVersion READ abiVersion CONSTANT)
  Q_PROPERTY(
      double smartCrossfadeMaxSeconds READ smartCrossfadeMaxSeconds CONSTANT)
  Q_PROPERTY(bool systemMediaLinked READ systemMediaLinked CONSTANT)

public:
  explicit BackendInfo(QObject *parent = nullptr);

  [[nodiscard]] bool ready() const;
  [[nodiscard]] quint32 abiVersion() const;
  [[nodiscard]] double smartCrossfadeMaxSeconds() const;
  [[nodiscard]] bool systemMediaLinked() const;

  // Returns bundled license text so lawyers don't show up at our door.
  Q_INVOKABLE QString licenseText(const QString &key) const;

  // Generates a song.link URL from a YouTube video ID, or album.link URL for
  // album/playlist IDs. Why write our own link aggregator when song.link
  // already solved world peace for streaming services?
  Q_INVOKABLE QString songLink(const QString &idOrUrl,
                               const QString &kind = QString()) const;

  // Generates an album.link URL specifically for an album or playlist ID.
  // Because MPREb_ is YouTube's internal browse pass and album.link expects the
  // OLAK playlist ID.
  Q_INVOKABLE QString songlinkAlbumUrl(const QString &audioPlaylistId) const;
  Q_INVOKABLE QString songLinkAlbumUrl(const QString &audioPlaylistId) const;

  // Copies text to the OS clipboard, optionally notifying the UI if a notice
  // message is provided.
  Q_INVOKABLE void copyToClipboard(const QString &text,
                                   const QString &notice = QString());

  // Generates the song.link or album.link and copies it straight to the
  // clipboard with a user-facing toast notice.
  Q_INVOKABLE void copySongLink(const QString &idOrUrl,
                                const QString &label = QString());

  // Launches the song.link or album.link directly in the user's default
  // browser.
  Q_INVOKABLE void openSongLink(const QString &idOrUrl,
                                const QString &kind = QString()) const;

signals:
  void noticeRequested(const QString &message);
};
