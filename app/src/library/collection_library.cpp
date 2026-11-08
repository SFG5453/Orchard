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

#include "collection_library.h"

#include "auth/auth_manager.h"
#include "home/home_controller.h"
#include "integrations/apple_music.h"
#include "providers/youtube/youtube_provider.h"

#include <QJsonObject>
#include <QPointer>

namespace {
bool isApple(const QString &id) { return id.startsWith(QStringLiteral("am.")); }
} // namespace

CollectionLibrary::CollectionLibrary(YouTubeProvider *provider, AuthManager *auth, AppleMusic *appleMusic,
                                     HomeController *home, QObject *parent)
    : QObject(parent), m_provider(provider), m_auth(auth), m_appleMusic(appleMusic), m_home(home) {
  connect(m_provider, &YouTubeProvider::resultReady, this, &CollectionLibrary::receive);
  connect(m_provider, &YouTubeProvider::requestFailed, this, &CollectionLibrary::fail);
  // Another account has another library; the next page load reports its state.
  connect(m_auth, &AuthManager::accountChanged, this, &CollectionLibrary::forget);
  connect(m_auth, &AuthManager::statusChanged, this, [this] {
    if (!m_auth->isSignedIn()) forget();
  });
  connect(m_appleMusic, &AppleMusic::changed, this, [this] {
    if (!m_appleMusic->connected()) forget();
  });
}

QString CollectionLibrary::idOf(const QVariantMap &detail) {
  const QString id = detail.value(QStringLiteral("browseId")).toString();
  return id.isEmpty() ? detail.value(QStringLiteral("id")).toString() : id;
}

bool CollectionLibrary::saved(const QVariantMap &detail) const {
  return m_saved.value(idOf(detail), detail.value(QStringLiteral("saved")).toBool());
}

bool CollectionLibrary::busy(const QVariantMap &detail) const {
  return m_busy.contains(idOf(detail));
}

void CollectionLibrary::set(const QString &id, bool saved) {
  m_saved.insert(id, saved);
  ++m_revision;
  emit changed();
}

void CollectionLibrary::forget() {
  if (m_saved.isEmpty() && m_busy.isEmpty())
    return;
  m_saved.clear();
  m_busy.clear();
  m_requests.clear();
  ++m_revision;
  emit changed();
}

void CollectionLibrary::toggle(const QVariantMap &detail) {
  const QString id = idOf(detail);
  if (id.isEmpty() || m_busy.contains(id))
    return;
  const bool album = detail.value(QStringLiteral("kind")).toString() == QStringLiteral("album");
  const bool next = !saved(detail);

  if (isApple(id)) {
    if (!m_appleMusic->connected()) {
      emit noticeRequested(tr("Connect Apple Music in Settings to save to your library."));
      return;
    }
    m_busy.insert(id);
    set(id, next);
    m_appleMusic->libraryAction(
        QStringLiteral("library.collection.save"),
        QJsonObject{{QStringLiteral("browseId"), id}, {QStringLiteral("saved"), next}},
        [self = QPointer(this), id, next](const QJsonObject &, const QString &error) {
          QMetaObject::invokeMethod(self, [self, id, next, error] {
            if (self) self->finish(id, next, error);
          }, Qt::QueuedConnection);
        });
    return;
  }

  if (!m_auth->isSignedIn()) {
    emit noticeRequested(tr("Sign in to save to your library."));
    return;
  }
  // Albums save through their OLAK audio playlist; the MPREb browse id is not a playlist.
  QString target = album ? detail.value(QStringLiteral("audioPlaylistId")).toString()
                         : detail.value(QStringLiteral("playlistId")).toString();
  if (target.isEmpty() && !album)
    target = id;
  if (target.startsWith(QStringLiteral("VL")))
    target = target.mid(2);
  if (target.isEmpty() || target.startsWith(QStringLiteral("MPRE"))) {
    emit noticeRequested(tr("This album cannot be saved to your YouTube Music library."));
    return;
  }
  m_busy.insert(id);
  set(id, next);
  const quint64 request = m_provider->invoke(
      QStringLiteral("library.collection.save"),
      QJsonObject{{QStringLiteral("session"), m_auth->sessionObject()},
                  {QStringLiteral("playlistId"), target},
                  {QStringLiteral("saved"), next}});
  m_requests.insert(request, {id, next});
}

void CollectionLibrary::finish(const QString &id, bool saved, const QString &error) {
  if (!m_busy.remove(id))
    return;
  if (!error.isEmpty()) {
    set(id, !saved);
    emit noticeRequested(error);
    return;
  }
  ++m_revision;
  emit changed();
  emit noticeRequested(saved ? tr("Saved to your library") : tr("Removed from your library"));
  m_home->refreshLibrary();
}

void CollectionLibrary::receive(quint64 requestId, const QJsonValue &) {
  const auto pending = m_requests.take(requestId);
  if (!pending.id.isEmpty())
    finish(pending.id, pending.saved, {});
}

void CollectionLibrary::fail(quint64 requestId, const QString &message) {
  const auto pending = m_requests.take(requestId);
  if (!pending.id.isEmpty())
    finish(pending.id, pending.saved, message.isEmpty() ? tr("Could not update your library.") : message);
}
