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

#include "home_controller.h"

#include "auth/auth_manager.h"
#include "providers/youtube/catalog/youtube_catalog.h"

#include <QJsonArray>
#include <QDebug>
#include <QRandomGenerator>
#include <QSettings>
#include <QtGlobal>
#include <utility>

namespace {
QVariantMap savedMap(const QString &key) {
  QSettings settings;
  settings.beginGroup(QStringLiteral("playback"));
  return settings.value(key).toMap();
}
} // namespace

HomeController::HomeController(YouTubeCatalog *catalog, AuthManager *auth,
                               QObject *parent)
    : QObject(parent), m_catalog(catalog), m_auth(auth) {
  Q_ASSERT(m_catalog);
  Q_ASSERT(m_auth);

  QSettings settings;
  settings.beginGroup(QStringLiteral("playback"));
  m_volume = qBound(
      0.0, settings.value(QStringLiteral("volume"), 1.0).toDouble(), 1.0);

  connect(m_catalog, &YouTubeCatalog::playlistsReady, this,
          [this](quint64 requestId, const QJsonArray &playlists) {
    if (requestId != m_playlistsRequestId) return;
    m_playlistsRequestId = 0;
    m_playlists = playlists.toVariantList();
    m_playlistsError.clear();
    emit playlistsChanged();
  });
  connect(m_catalog, &YouTubeCatalog::homeReady, this,
          &HomeController::receiveHome);
  connect(m_catalog, &YouTubeCatalog::libraryReady, this,
          [this](quint64 requestId, const QJsonObject &library) {
    if (requestId != m_libraryRequestId) return;
    m_libraryRequestId = 0;
    m_libraryLoaded = true;
    m_libraryError.clear();
    m_librarySections = library.value(QStringLiteral("sections")).toArray().toVariantList();
    // The sidebar and the library should agree on where the playlists live.
    for (const auto &value : std::as_const(m_librarySections)) {
      const auto section = value.toMap();
      if (section.value(QStringLiteral("key")).toString() == QStringLiteral("playlists") &&
          section.value(QStringLiteral("error")).toString().isEmpty()) {
        m_playlistsRequestId = 0;
        m_playlists = section.value(QStringLiteral("items")).toList();
        m_playlistsError.clear();
        emit playlistsChanged();
      }
    }
    emit libraryChanged();
  });
  connect(m_catalog, &YouTubeCatalog::requestFailed, this,
          &HomeController::receiveFailure);
  connect(m_auth, &AuthManager::loginCompleted, this, &HomeController::refresh);
  connect(m_auth, &AuthManager::sessionChanged, this, [this] {
    // A request using the previous credentials must not win this race.
    m_requestId = 0;
    m_playlistsRequestId = 0;
    m_playlists.clear();
    m_playlistsError.clear();
    emit playlistsChanged();
    m_libraryRequestId = 0;
    m_libraryLoaded = false;
    m_librarySections.clear();
    m_libraryError.clear();
    emit libraryChanged();
    m_loading = false;
    m_sections.clear();
    m_artists.clear();
    m_hero.clear();
    m_errorMessage.clear();
    refresh();
    emit stateChanged();
  });
  connect(m_auth, &AuthManager::statusChanged, this, [this] {
    if (m_auth->isSignedIn() && m_sections.isEmpty())
      refresh();
  });
}

void HomeController::refreshPlaylists() {
  if (!m_auth->isSignedIn() || m_playlistsRequestId)
    return;
  m_playlistsError.clear();
  m_playlistsRequestId = m_catalog->fetchPlaylists(m_auth->sessionObject());
  emit playlistsChanged();
}

void HomeController::refresh() {
  if (!m_auth->isSignedIn() || m_loading)
    return;

  // Preflight cookie check. Stale cookies mean no music.
  m_auth->refreshSession();

  if (m_libraryRequested)
    loadLibrary();

  refreshPlaylists();
  m_loading = true;
  m_errorMessage.clear();
  m_requestId = m_catalog->fetchHome(m_auth->sessionObject());
  emit stateChanged();
}

void HomeController::loadLibrary() {
  m_libraryRequested = true;
  if (!m_libraryLoaded)
    refreshLibrary();
}

void HomeController::refreshLibrary() {
  m_libraryRequested = true;
  if (!m_auth->isSignedIn() || m_libraryRequestId)
    return;

  // Fetch on first visit; the home page doesn't need to carry every record crate.
  m_auth->refreshSession();
  // Refreshing cookies can emit sessionChanged and start the replacement request.
  if (!m_auth->isSignedIn() || m_libraryRequestId)
    return;
  m_libraryError.clear();
  m_libraryRequestId = m_catalog->fetchLibrary(m_auth->sessionObject());
  emit libraryChanged();
}

void HomeController::setVolume(double volume) {
  const double bounded = qBound(0.0, volume, 1.0);
  if (qFuzzyCompare(m_volume, bounded))
    return;

  m_volume = bounded;
  QSettings settings;
  settings.beginGroup(QStringLiteral("playback"));
  settings.setValue(QStringLiteral("volume"), m_volume);
  settings.endGroup();
  emit volumeChanged();
}

void HomeController::receiveHome(quint64 requestId, const QJsonObject &home) {
  if (requestId != m_requestId)
    return;

  m_loading = false;
  m_errorMessage.clear();
  m_sections = home.value(QStringLiteral("sections")).toArray().toVariantList();
  m_artists = home.value(QStringLiteral("artists")).toArray().toVariantList();
  if (qEnvironmentVariableIsSet("ORCHARD_HOME_DIAGNOSTICS")) {
    QStringList titles;
    for (const auto &section : std::as_const(m_sections))
      titles.append(section.toMap().value(QStringLiteral("title")).toString());
    qInfo() << "Home shelves:" << titles << "Subscribed artists:" << m_artists.size();
  }
  chooseHero();
  emit stateChanged();
}

void HomeController::receiveFailure(quint64 requestId, const QString &message) {
  if (requestId == m_libraryRequestId) {
    m_libraryRequestId = 0;
    m_libraryError = message;
    emit libraryChanged();
    return;
  }
  if (requestId == m_playlistsRequestId) {
    m_playlistsRequestId = 0;
    m_playlistsError = message;
    emit playlistsChanged();
    return;
  }
  if (requestId != m_requestId)
    return;

  m_loading = false;
  m_errorMessage = message;
  emit stateChanged();
}

void HomeController::chooseHero() {
  const QVariantMap lastSong = savedMap(QStringLiteral("lastSong"));
  const QVariantMap lastPlaylist = savedMap(QStringLiteral("lastPlaylist"));
  if (usefulItem(lastSong)) {
    m_hero = lastSong;
    m_hero.insert(QStringLiteral("contextLabel"),
                  QStringLiteral("Last played"));
    return;
  }
  if (usefulItem(lastPlaylist)) {
    m_hero = lastPlaylist;
    m_hero.insert(QStringLiteral("contextLabel"),
                  QStringLiteral("Last played playlist"));
    return;
  }

  if (!m_artists.isEmpty()) {
    m_hero = m_artists
                 .at(QRandomGenerator::global()->bounded(
                     static_cast<int>(m_artists.size())))
                 .toMap();
    m_hero.insert(QStringLiteral("contextLabel"),
                  QStringLiteral("Subscribed artist"));
    return;
  }

  m_hero.clear();
}

bool HomeController::usefulItem(const QVariantMap &item) {
  return !item.value(QStringLiteral("title")).toString().trimmed().isEmpty();
}
