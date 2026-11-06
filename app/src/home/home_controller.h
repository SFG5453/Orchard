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

#include <QJsonObject>
#include <QObject>
#include <QVariantList>
#include <QVariantMap>

#include "integrations/catalog_source.h"

class AuthManager;
class AppleMusic;
class YouTubeCatalog;

class HomeController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool loading READ loading NOTIFY stateChanged)
  Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY stateChanged)
  Q_PROPERTY(QVariantList sections READ sections NOTIFY stateChanged)
  Q_PROPERTY(QVariantMap hero READ hero NOTIFY stateChanged)
  Q_PROPERTY(QVariantList playlists READ playlists NOTIFY playlistsChanged)
  Q_PROPERTY(QStringList sidebarPlaylistOrder READ sidebarPlaylistOrder WRITE setSidebarPlaylistOrder NOTIFY sidebarPlaylistOrderChanged)
  Q_PROPERTY(bool playlistsLoading READ playlistsLoading NOTIFY playlistsChanged)
  Q_PROPERTY(QString playlistsError READ playlistsError NOTIFY playlistsChanged)
  Q_PROPERTY(QVariantList librarySections READ librarySections NOTIFY libraryChanged)
  Q_PROPERTY(bool libraryLoading READ libraryLoading NOTIFY libraryChanged)
  Q_PROPERTY(QString libraryError READ libraryError NOTIFY libraryChanged)
  Q_PROPERTY(double volume READ volume WRITE setVolume NOTIFY volumeChanged)
  Q_PROPERTY(QString homeSource READ homeSource WRITE setHomeSource NOTIFY homeSourceChanged)

public:
  explicit HomeController(YouTubeCatalog *catalog, AuthManager *auth,
                          QObject *parent = nullptr);

  [[nodiscard]] bool loading() const { return m_loading; }
  [[nodiscard]] QString errorMessage() const { return m_errorMessage; }
  [[nodiscard]] QVariantList sections() const { return m_sections; }
  [[nodiscard]] QVariantMap hero() const { return m_hero; }
  [[nodiscard]] double volume() const { return m_volume; }
  // App-wide catalog source; Home keeps the property QML already binds to.
  QString homeSource() const;
  void setHomeSource(const QString &source);
  void setAppleMusic(AppleMusic *appleMusic, CatalogSource *source);

  [[nodiscard]] QVariantList playlists() const { return m_playlists; }
  [[nodiscard]] QStringList sidebarPlaylistOrder() const { return m_sidebarPlaylistOrder; }
  void setSidebarPlaylistOrder(const QStringList &order);
  [[nodiscard]] bool playlistsLoading() const { return m_playlistsRequestId != 0; }
  [[nodiscard]] QString playlistsError() const { return m_playlistsError; }
  [[nodiscard]] QVariantList librarySections() const { return m_librarySections; }
  [[nodiscard]] bool libraryLoading() const { return m_libraryRequestId != 0; }
  [[nodiscard]] QString libraryError() const { return m_libraryError; }
  Q_INVOKABLE void refresh();
  Q_INVOKABLE void loadLibrary();
  Q_INVOKABLE void refreshLibrary();
  void refreshPlaylists();
  void setVolume(double volume);

signals:
  void stateChanged();
  void playlistsChanged();
  void sidebarPlaylistOrderChanged();
  void libraryChanged();
  void volumeChanged();
  void homeSourceChanged();

private:
  void receiveHome(quint64 requestId, const QJsonObject &home);
  void receiveFailure(quint64 requestId, const QString &message);
  void chooseHero();
  void fetchNormalHome();
  [[nodiscard]] bool appleSource() const { return m_source && m_source->apple(); }
  [[nodiscard]] quint64 nextAppleRequest() { return (quint64{1} << 63) | ++m_appleRequests; }
  void refreshApplePlaylists();
  void refreshAppleLibrary();
  void receiveLibrary(const QJsonObject &library);
  // Drops playlists and library from another source or account.
  void resetLibrary();
  [[nodiscard]] static bool usefulItem(const QVariantMap &item);

  YouTubeCatalog *m_catalog;
  AuthManager *m_auth;
  AppleMusic *m_appleMusic{nullptr};
  CatalogSource *m_source{nullptr};
  quint64 m_appleRequests{0};
  bool m_appleConnected{false};
  QVariantList m_playlists;
  QStringList m_sidebarPlaylistOrder;
  QString m_playlistsError;
  quint64 m_playlistsRequestId{0};
  QVariantList m_librarySections;
  QString m_libraryError;
  quint64 m_libraryRequestId{0};
  bool m_libraryLoaded{false};
  bool m_libraryRequested{false};
  QVariantList m_sections;
  QVariantList m_artists;
  QVariantMap m_hero;
  QString m_errorMessage;
  quint64 m_requestId{0};
  double m_volume{1.0};
  bool m_loading{false};
};
