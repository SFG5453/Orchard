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

void AuthManager::startProfileResolution() {
  const auto cookies = parseCookieString(m_cookie);
  if (!m_authWebViewActive && !cookies.contains(QStringLiteral("SID")) &&
      !cookies.contains(QStringLiteral("__Secure-3PSID")) &&
      !cookies.contains(QStringLiteral("__Secure-1PSID"))) {
    m_profileLoading = false;
    startLogin();
    return;
  }
  ++m_profileGeneration;
  m_profileLoading = true;
  m_pendingProfile = {};
  setStatus(QStringLiteral("resolving_profile"));
  emit sessionChanged();
  emit userChanged();
  m_profileRequestId = 0;
  if (m_authWebViewActive && m_switchMode) {
    // The chooser lands on www.youtube.com. Its account menu contains the
    // selected channel's profile even when accounts_list rejects the request.
    const quint64 generation = m_profileGeneration;
    emit profileProbeRequested();
    QTimer::singleShot(8500, this, [this, generation] {
      if (m_profileLoading && generation == m_profileGeneration)
        finishProfileResolution();
    });
    return;
  }
#ifdef ORCHARD_NATIVE_COOKIE_AUTH
  if (m_authWebViewActive && !m_switchMode) {
    // accounts_list failures must not undo a completed browser login 
    // or we get something like HTTP 400 FAILED_PRECONDITION.
    const quint64 generation = m_profileGeneration;
    emit profileProbeRequested();
    QTimer::singleShot(5000, this, [this, generation] {
      if (m_profileLoading && generation == m_profileGeneration)
        finishProfileResolution();
    });
    return;
  }
#endif
  if (m_authWebViewActive) {
    const QString token = QString::number(m_profileGeneration);
    emit pageAccountRequested(token, computeAuthorizationHeader(m_cookie));
    QTimer::singleShot(20000, this, [this, token] {
      if (m_profileLoading && token == QString::number(m_profileGeneration))
        failProfileResolution(
            QStringLiteral("YouTube account verification timed out. Please try "
                           "signing in again."));
    });
  } else {
    const QJsonObject publicChannel = publicChannelProfile();
    m_profileRequestId = publicChannel.isEmpty()
                             ? m_youtubeProvider->loadAccountProfile(sessionObject())
                             : m_youtubeProvider->enrichAccountProfile(publicChannel);
  }
}

void AuthManager::finishProfileResolution() {
  if (!m_profileLoading)
    return;
  authDiagnostic(QStringLiteral("profile-complete"));
  m_profileLoading = false;
  m_authWebViewActive = false;
  m_profileRequestId = 0;
  const QString resolvedName =
      m_pendingProfile.value(QStringLiteral("name")).toString().trimmed();
  if (!resolvedName.isEmpty() &&
      resolvedName.compare(QStringLiteral("Signed in"), Qt::CaseInsensitive) !=
          0 &&
      resolvedName.compare(QStringLiteral("YouTube Music"),
                           Qt::CaseInsensitive) != 0) {
    m_userName = resolvedName;
  }
  QString resolvedHandle =
      m_pendingProfile.value(QStringLiteral("handle")).toString().trimmed();
  if (!resolvedHandle.isEmpty() && !resolvedHandle.startsWith(QLatin1Char('@')))
    resolvedHandle.prepend(QLatin1Char('@'));
  if (!resolvedHandle.isEmpty())
    m_userHandle = resolvedHandle;
  const QString resolvedAvatar =
      m_pendingProfile.value(QStringLiteral("avatarUrl")).toString().trimmed();
  if (!resolvedAvatar.isEmpty())
    m_userAvatar = resolvedAvatar;
  persistSecretSession();
  setStatus(QStringLiteral("signed_in"));
  emit userChanged();
  authDiagnostic(QStringLiteral("login-completed-signal"));
  emit loginCompleted();
}

void AuthManager::mergeProfile(const QJsonObject &profile) {
  const auto merge = [this, &profile](const QString &targetKey,
                                      const QStringList &sourceKeys) {
    const QString current =
        m_pendingProfile.value(targetKey).toString().trimmed();
    const bool placeholder =
        current.compare(QStringLiteral("Signed in"), Qt::CaseInsensitive) ==
            0 ||
        current.compare(QStringLiteral("YouTube Music"), Qt::CaseInsensitive) ==
            0 ||
        current.contains(QStringLiteral("subscriber"), Qt::CaseInsensitive);
    if (!current.isEmpty() && !placeholder)
      return;
    for (const QString &sourceKey : sourceKeys) {
      const QString value = profile.value(sourceKey).toString().trimmed();
      if (!value.isEmpty()) {
        m_pendingProfile.insert(targetKey, value);
        return;
      }
    }
  };
  merge(QStringLiteral("name"), {QStringLiteral("name")});
  merge(QStringLiteral("handle"),
        {QStringLiteral("handle"), QStringLiteral("byline")});
  merge(QStringLiteral("avatarUrl"),
        {QStringLiteral("avatarUrl"), QStringLiteral("avatar"),
         QStringLiteral("thumbnail")});
  merge(QStringLiteral("channelId"), {QStringLiteral("channelId")});
  merge(QStringLiteral("channelUrl"), {QStringLiteral("channelUrl")});
}

bool AuthManager::hasCompleteProfile() const {
  const QString name =
      m_pendingProfile.value(QStringLiteral("name")).toString().trimmed();
  const QString handle =
      m_pendingProfile.value(QStringLiteral("handle")).toString().trimmed();
  const QString avatar =
      m_pendingProfile.value(QStringLiteral("avatarUrl")).toString().trimmed();
  return !name.isEmpty() && !handle.isEmpty() && !avatar.isEmpty() &&
         name.compare(QStringLiteral("Signed in"), Qt::CaseInsensitive) != 0 &&
         name.compare(QStringLiteral("YouTube Music"), Qt::CaseInsensitive) !=
             0;
}
