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

#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QNetworkAccessManager>
#include <QObject>
#include <functional>

class AuthSessionServer;
class OrchardAccount;
class ProviderRuntime;
class QProcess;

// Apple Music sign-in and catalog. The Music User Token lives in the OS keychain;
// the developer token comes from the Orchard account service and is cached there too.
// Catalog calls run in the shared Apple Music provider on its own QuickJS thread.
class AppleMusic final : public QObject {
  Q_OBJECT
  // restoring, disconnected, connecting, connected
  Q_PROPERTY(QString status READ status NOTIFY changed)
  Q_PROPERTY(bool connected READ connected NOTIFY changed)
  // Sign-in needs the helper's browser: WebKitGTK on Linux, WebView2 on Windows.
  Q_PROPERTY(bool loginAvailable READ loginAvailable CONSTANT)
  Q_PROPERTY(QString message READ message NOTIFY changed)
  Q_PROPERTY(bool messageIsError READ messageIsError NOTIFY changed)

public:
  // An empty page with an empty error is a plain miss.
  using PageReply = std::function<void(const QJsonObject &page, const QString &error)>;

  explicit AppleMusic(OrchardAccount *account, QObject *parent = nullptr, bool restore = true);
  ~AppleMusic() override;

  QString status() const { return m_status; }
  bool connected() const { return m_status == QStringLiteral("connected"); }
  bool loginAvailable() const;
  QString message() const { return m_message; }
  bool messageIsError() const { return m_messageIsError; }

  Q_INVOKABLE void connectAccount();
  Q_INVOKABLE void cancelConnection();
  Q_INVOKABLE void disconnectAccount();

  // Developer token for the MusicKit player; empty with an error when it cannot be had.
  void playbackToken(std::function<void(const QString &token, const QString &error)> done) {
    withDeveloperToken(std::move(done));
  }

  // The listener's Music User Token; empty while disconnected.
  QString userToken() const { return m_userToken; }
  // The developer token's origin claim names the sign-in page's origin; Apple checks requests against it.
  static QString tokenOrigin() { return QStringLiteral("https://appassets.androidplatform.net"); }

  static bool isAppleMusicId(const QString &browseId) { return browseId.startsWith(QStringLiteral("am.")); }

  // apple_music_catalog.cpp
  void home(PageReply done);
  // Apple's page for the artist named exactly [name]; a miss returns an empty page.
  void artistPage(const QString &name, PageReply done);
  // An am.artists, am.albums or am.playlists page.
  void detail(const QString &browseId, PageReply done);
  // Every item on one artist shelf ("See all").
  void artistView(const QString &browseId, const QString &params, PageReply done);
  // Apple's motion artwork for a song or album (catalog.artwork); an empty page when none matches.
  void artwork(const QJsonObject &query, PageReply done);
  // Catalog search grouped into sections like YouTube's; filter is "all", "songs", "albums"...
  void search(const QString &query, const QString &filter, PageReply done);
  // The listener's library as sections keyed like YouTube's: artists, albums, songs, playlists.
  void library(PageReply done);
  // {items}: every playlist in the listener's library.
  void libraryPlaylists(PageReply done);
  // A library write or read for LibraryActions: library.like.*, library.playlist.targets/add/create.
  void libraryAction(const QString &method, const QJsonObject &payload, PageReply done);
  // {ttml} for a track from any service (appleId, isrc or title/artist/album); empty when Apple has none.
  void lyrics(const QJsonObject &track, PageReply done);

signals:
  void changed();

private:
  using TokenReply = std::function<void(const QString &token, const QString &error)>;
  using CallReply = std::function<void(const QJsonValue &result, const QString &error)>;

  void setStatus(const QString &status);
  void setMessage(const QString &message, bool error = false);
  void restoreSession();
  void persistSession();
  void runHelper(const QString &mode, const QByteArray &input, std::function<void(const QJsonObject &)> done);
  void withDeveloperToken(TokenReply done);
  void forgetDeveloperToken();
  // apple_music_catalog.cpp
  void call(const QString &method, const QJsonObject &payload, CallReply done, bool retry = true);
  void configure(std::function<void(const QString &error)> done);
  void finishCall(quint64 requestId, const QJsonValue &result, const QString &error);

  OrchardAccount *m_account;
  QNetworkAccessManager m_network;
  ProviderRuntime *m_runtime;
  QProcess *m_helper{nullptr};
  AuthSessionServer *m_helperServer{nullptr};
  QJsonObject m_helperResult;
  std::function<void(const QJsonObject &)> m_helperDone;
  QString m_userToken;
  QString m_developerToken;
  qint64 m_developerExpiresAt{0};
  QList<TokenReply> m_tokenWaiters;
  // Developer and user token hash the provider session was configured with.
  QString m_configuredFor;
  QHash<quint64, std::function<void(const QJsonValue &, const QString &)>> m_calls;
  QString m_status{QStringLiteral("disconnected")};
  QString m_message;
  bool m_messageIsError{false};
  quint64 m_loginGeneration{0};
};
