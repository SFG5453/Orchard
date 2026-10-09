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

class AuthManager;
class YouTubeCatalog;

class HomeController final : public QObject {
  Q_OBJECT
  Q_PROPERTY(bool loading READ loading NOTIFY stateChanged)
  Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY stateChanged)
  Q_PROPERTY(QVariantList sections READ sections NOTIFY stateChanged)
  Q_PROPERTY(QVariantMap hero READ hero NOTIFY stateChanged)
  Q_PROPERTY(QVariantList playlists READ playlists NOTIFY playlistsChanged)
  Q_PROPERTY(bool playlistsLoading READ playlistsLoading NOTIFY playlistsChanged)
  Q_PROPERTY(QString playlistsError READ playlistsError NOTIFY playlistsChanged)
  Q_PROPERTY(QVariantList librarySections READ librarySections NOTIFY libraryChanged)
  Q_PROPERTY(bool libraryLoading READ libraryLoading NOTIFY libraryChanged)
  Q_PROPERTY(QString libraryError READ libraryError NOTIFY libraryChanged)
  Q_PROPERTY(double volume READ volume WRITE setVolume NOTIFY volumeChanged)

public:
  explicit HomeController(YouTubeCatalog *catalog, AuthManager *auth,
                          QObject *parent = nullptr);

  [[nodiscard]] bool loading() const { return m_loading; }
  [[nodiscard]] QString errorMessage() const { return m_errorMessage; }
  [[nodiscard]] QVariantList sections() const { return m_sections; }
  [[nodiscard]] QVariantMap hero() const { return m_hero; }
  [[nodiscard]] double volume() const { return m_volume; }

  [[nodiscard]] QVariantList playlists() const { return m_playlists; }
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
  void libraryChanged();
  void volumeChanged();

private:
  void receiveHome(quint64 requestId, const QJsonObject &home);
  void receiveFailure(quint64 requestId, const QString &message);
  void chooseHero();
  [[nodiscard]] static bool usefulItem(const QVariantMap &item);

  YouTubeCatalog *m_catalog;
  AuthManager *m_auth;
  QVariantList m_playlists;
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
