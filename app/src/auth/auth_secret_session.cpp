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

#include "auth_manager.h"
#include "auth_diagnostics.h"
#include "auth_session_channel.h"
#include "browser_cookie_adapter.h"
#include "providers/youtube/youtube_provider.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTimer>
#include <QUrl>

// I don't feel like messing with sqlite, and this is an interesting project
// Credit to https://github.com/frankosterfeld/qtkeychain/
#include <qtkeychain/keychain.h>

namespace {
constexpr auto keychainService = "dev.sfg.orchard";
constexpr auto keychainEntry = "youtube-session";
} // namespace

void AuthManager::restoreSession() {
  QSettings settings;
  settings.beginGroup(QStringLiteral("auth"));
  const QString legacyCookie =
      settings.value(QStringLiteral("cookie")).toString();
  const QString legacyVisitorData =
      settings.value(QStringLiteral("visitorData")).toString();
  const QString legacyDataSyncId =
      settings.value(QStringLiteral("dataSyncId")).toString();
  const int legacyAccountIndex =
      settings.value(QStringLiteral("accountIndex"), 0).toInt();
  const QString legacyClientVersion =
      settings.value(QStringLiteral("clientVersion")).toString();
  const QString legacyUserName =
      settings.value(QStringLiteral("userName")).toString();
  const QString legacyUserEmail =
      settings.value(QStringLiteral("userEmail")).toString();
  const QString legacyUserAvatar =
      settings.value(QStringLiteral("userAvatar")).toString();
  const QString legacyUserHandle =
      settings.value(QStringLiteral("userHandle")).toString();
  settings.remove(QStringLiteral("cookie"));
  settings.remove(QStringLiteral("visitorData"));
  settings.remove(QStringLiteral("dataSyncId"));
  settings.remove(QStringLiteral("accountIndex"));
  settings.remove(QStringLiteral("clientVersion"));
  settings.remove(QStringLiteral("userName"));
  settings.remove(QStringLiteral("userEmail"));
  settings.remove(QStringLiteral("userAvatar"));
  settings.remove(QStringLiteral("userHandle"));
  settings.endGroup();
  settings.sync();

  if (!legacyCookie.isEmpty() && hasYouTubeLoginCookie(legacyCookie)) {
    m_cookie = legacyCookie;
    m_visitorData = legacyVisitorData;
    m_dataSyncId = legacyDataSyncId;
    m_accountIndex = qMax(0, legacyAccountIndex);
    m_clientVersion = legacyClientVersion;
    m_userName = legacyUserName;
    m_userEmail = legacyUserEmail;
    m_userAvatar = legacyUserAvatar;
    m_userHandle = legacyUserHandle;
    m_status = QStringLiteral("signed_in");
    persistSecretSession();
    return;
  }

  restoreSecretSession();
}

void AuthManager::restoreSecretSession() {
  m_profileLoading = true;
  m_status = QStringLiteral("restoring");
  const quint64 generation = ++m_profileGeneration;
  auto *job = new QKeychain::ReadPasswordJob(
      QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setInsecureFallback(false);
  connect(
      job, &QKeychain::Job::finished, this,
      [this, generation](QKeychain::Job *baseJob) {
        if (generation != m_profileGeneration) {
          authDiagnostic(QStringLiteral("stale-keychain-result-ignored"));
          return;
        }
        authDiagnostic(
            QStringLiteral("keychain-result code=%1").arg(baseJob->error()));
        auto *readJob = static_cast<QKeychain::ReadPasswordJob *>(baseJob);
        m_profileLoading = false;
        if (readJob->error() == QKeychain::NoError) {
          const QJsonDocument document =
              QJsonDocument::fromJson(readJob->textData().toUtf8());
          const QJsonObject secret = document.object();
          const QString cookie =
              secret.value(QStringLiteral("cookie")).toString();
          if (hasYouTubeLoginCookie(cookie)) {
            m_cookie = cookie;
            m_visitorData =
                secret.value(QStringLiteral("visitorData")).toString();
            m_dataSyncId =
                secret.value(QStringLiteral("dataSyncId")).toString();
            m_accountIndex =
                qMax(0, secret.value(QStringLiteral("accountIndex")).toInt());
            m_clientVersion =
                secret.value(QStringLiteral("clientVersion")).toString();
            m_userName = secret.value(QStringLiteral("userName")).toString();
            m_userEmail = secret.value(QStringLiteral("userEmail")).toString();
            m_userAvatar =
                secret.value(QStringLiteral("userAvatar")).toString();
            m_userHandle =
                secret.value(QStringLiteral("userHandle")).toString();
            // The keychain read finishes after QML has created its
            // bindings. Notify the profile properties as well as status.
            emit userChanged();
#ifdef ORCHARD_NATIVE_COOKIE_AUTH
            if (!hasYouTubeSessionCookies(m_cookie)) {
              // Older sessions saved document.cookie without HttpOnly
              // credentials. Opening the existing login profile initializes its
              // cookie store.
              startLogin();
              return;
            }
#endif
            setStatus(QStringLiteral("signed_in"));
            emit sessionChanged();
            if (m_userName.isEmpty() || m_userHandle.isEmpty() ||
                m_userAvatar.isEmpty()) {
              startProfileResolution();
            }
            return;
          }
        } else if (readJob->error() != QKeychain::EntryNotFound) {
          qWarning().noquote()
              << "Could not read the YouTube session from the OS keychain:"
              << readJob->errorString();
        }
        setStatus(QStringLiteral("signed_out"));
      });
  job->start();
}

void AuthManager::persistSecretSession() {
  if (m_embeddedWebAuth || !hasYouTubeLoginCookie(m_cookie))
    return;
  const QJsonObject secret{{QStringLiteral("cookie"), m_cookie},
                           {QStringLiteral("visitorData"), m_visitorData},
                           {QStringLiteral("dataSyncId"), m_dataSyncId},
                           {QStringLiteral("accountIndex"), m_accountIndex},
                           {QStringLiteral("clientVersion"), m_clientVersion},
                           {QStringLiteral("userName"), m_userName},
                           {QStringLiteral("userEmail"), m_userEmail},
                           {QStringLiteral("userAvatar"), m_userAvatar},
                           {QStringLiteral("userHandle"), m_userHandle}};
  auto *job = new QKeychain::WritePasswordJob(
      QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setTextData(
      QString::fromUtf8(QJsonDocument(secret).toJson(QJsonDocument::Compact)));
  job->setInsecureFallback(false);
  connect(job, &QKeychain::Job::finished, this,
          [](QKeychain::Job *finishedJob) {
            if (finishedJob->error() != QKeychain::NoError) {
              qWarning().noquote()
                  << "Could not store the YouTube session in the OS keychain:"
                  << finishedJob->errorString();
            }
          });
  job->start();
}

void AuthManager::deleteSecretSession() {
  if (m_embeddedWebAuth)
    return;
  auto *job = new QKeychain::DeletePasswordJob(
      QString::fromLatin1(keychainService), this);
  job->setKey(QString::fromLatin1(keychainEntry));
  job->setInsecureFallback(false);
  connect(
      job, &QKeychain::Job::finished, this, [](QKeychain::Job *finishedJob) {
        if (finishedJob->error() != QKeychain::NoError &&
            finishedJob->error() != QKeychain::EntryNotFound) {
          qWarning().noquote()
              << "Could not remove the YouTube session from the OS keychain:"
              << finishedJob->errorString();
        }
      });
  job->start();
}
