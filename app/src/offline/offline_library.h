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
#include <QVariantList>
#include <QVariantMap>

class ConnectivityMonitor;
class DownloadManager;
class LocalLibrary;

// What the app can play with no connection: downloaded songs, saved playlists
// and albums, and the local files on this computer.
class OfflineLibrary final : public QObject {
  Q_OBJECT
  Q_PROPERTY(QVariantList songs READ songs NOTIFY changed)
  Q_PROPERTY(QVariantList playlists READ playlists NOTIFY changed)
  // Shelves for the offline home page: { key, title, items }.
  Q_PROPERTY(QVariantList homeSections READ homeSections NOTIFY changed)

public:
  OfflineLibrary(DownloadManager *downloads, LocalLibrary *local, ConnectivityMonitor *connectivity,
                 QObject *parent = nullptr);

  // Downloaded songs, newest first, then local files.
  [[nodiscard]] QVariantList songs() const;
  // Local playlists, then saved YouTube playlists and albums with a downloaded song.
  [[nodiscard]] QVariantList playlists() const;
  [[nodiscard]] QVariantList homeSections() const;
  [[nodiscard]] bool offline() const;

  // Sections in the shape of online search results: songs and playlists only.
  Q_INVOKABLE QVariantList search(const QString &query, const QString &filter = QStringLiteral("all")) const;
  // The playlist page for an offline collection id; empty when unknown.
  Q_INVOKABLE QVariantMap collectionDetail(const QString &id) const;
  // The offline collection a YouTube playlist or album row was saved as, or empty.
  Q_INVOKABLE QString collectionIdFor(const QVariantMap &item) const;

signals:
  void changed();

private:
  DownloadManager *m_downloads;
  LocalLibrary *m_local;
  ConnectivityMonitor *m_connectivity;
};
