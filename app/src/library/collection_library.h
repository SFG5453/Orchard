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

#include <QHash>
#include <QJsonValue>
#include <QObject>
#include <QSet>
#include <QVariantMap>

class AppleMusic;
class AuthManager;
class HomeController;
class YouTubeProvider;

// Saves albums and playlists to the account library: YouTube Music for YouTube ids,
// Apple Music for am. ids. Pages report their state as detail.saved; local toggles override it.
class CollectionLibrary final : public QObject {
  Q_OBJECT
  // Bumps on every change so QML bindings re-read saved() and busy().
  Q_PROPERTY(int revision READ revision NOTIFY changed)

public:
  CollectionLibrary(YouTubeProvider *provider, AuthManager *auth, AppleMusic *appleMusic,
                    HomeController *home, QObject *parent = nullptr);

  [[nodiscard]] int revision() const { return m_revision; }
  Q_INVOKABLE bool saved(const QVariantMap &detail) const;
  Q_INVOKABLE bool busy(const QVariantMap &detail) const;
  Q_INVOKABLE void toggle(const QVariantMap &detail);

signals:
  void changed();
  void noticeRequested(const QString &message);

private:
  struct Pending {
    QString id;
    bool saved{false};
  };
  static QString idOf(const QVariantMap &detail);
  void set(const QString &id, bool saved);
  void finish(const QString &id, bool saved, const QString &error);
  void forget();
  // Sidebar playlists and the library page.
  void refreshLists();
  void receive(quint64 requestId, const QJsonValue &result);
  void fail(quint64 requestId, const QString &message);

  YouTubeProvider *m_provider;
  AuthManager *m_auth;
  AppleMusic *m_appleMusic;
  HomeController *m_home;
  QHash<QString, bool> m_saved;
  QSet<QString> m_busy;
  QHash<quint64, Pending> m_requests;
  int m_revision{0};
};
