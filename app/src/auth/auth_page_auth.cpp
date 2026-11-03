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

bool AuthManager::handlePageAuth(const QString &authJson) {
  if (!m_authWebViewActive)
    return false;
  QJsonParseError parseError;
  const QJsonDocument doc =
      QJsonDocument::fromJson(authJson.toUtf8(), &parseError);
  if (m_authCaptureState == -1) {
    authDiagnostic(QStringLiteral("page-auth-received valid-json=%1")
                       .arg(parseError.error == QJsonParseError::NoError &&
                            doc.isObject()));
    m_authCaptureState = -2;
  }
  if (parseError.error != QJsonParseError::NoError || !doc.isObject()) {
    return false;
  }

  const QJsonObject obj = doc.object();
  const QString previousCookie = m_cookie;
  const QString previousDataSyncId = m_dataSyncId;
  const int previousAccountIndex = m_accountIndex;
  const QString cookie = obj.value(QStringLiteral("cookie")).toString();
  const QString visitorData =
      obj.value(QStringLiteral("visitorData")).toString();
  const QString delegatedSessionId =
      obj.value(QStringLiteral("delegatedSessionId")).toString();
  const QString dataSyncId = obj.value(QStringLiteral("dataSyncId")).toString();
  const QJsonValue accountIndexValue =
      obj.value(QStringLiteral("accountIndex"));
  const QString name = obj.value(QStringLiteral("name")).toString();
  const QString email = obj.value(QStringLiteral("email")).toString();
  const QString avatar = obj.value(QStringLiteral("avatar")).toString();
  const QString handle = obj.value(QStringLiteral("handle")).toString();
  const QString clientVersion =
      obj.value(QStringLiteral("clientVersion")).toString();

  if (!name.isEmpty()) {
    m_userName = name;
  }
  if (!email.isEmpty()) {
    m_userEmail = email;
  }
  if (!avatar.isEmpty()) {
    m_userAvatar = avatar;
  }
  if (!handle.isEmpty()) {
    m_userHandle = handle.startsWith(QLatin1Char('@'))
                       ? handle
                       : QStringLiteral("@%1").arg(handle);
  }
  // Only YouTube Music's own version suits the WEB_REMIX requests that follow.
  if (!clientVersion.isEmpty() &&
      obj.value(QStringLiteral("origin")).toString() == QStringLiteral("https://music.youtube.com")) {
    m_clientVersion = clientVersion;
  }

  const QString computedDelegatedId =
      delegatedSessionIdFromPageAuth(dataSyncId, delegatedSessionId);
  if (!delegatedSessionId.isEmpty() || !dataSyncId.isEmpty()) {
    m_dataSyncId = computedDelegatedId;
  }
  if (!visitorData.isEmpty()) {
    m_visitorData = visitorData;
  }
  bool accountIndexValid = false;
  const int accountIndex =
      accountIndexValue.isDouble()
          ? accountIndexValue.toInt(-1)
          : accountIndexValue.toString().toInt(&accountIndexValid);
  if ((accountIndexValue.isDouble() || accountIndexValid) &&
      accountIndex >= 0) {
    m_accountIndex = accountIndex;
  }
  if (!cookie.isEmpty()) {
    QString normalized = normalizeYouTubeAuthCookie(cookie);
    const QString complete =
        normalizeYouTubeAuthCookie(m_browserCookies->cookies());
    const auto nativeCookies = parseCookieString(complete);
    if (!nativeCookies.value(QStringLiteral("SAPISID")).isEmpty() &&
        nativeCookies.value(QStringLiteral("SAPISID")) ==
            parseCookieString(normalized).value(QStringLiteral("SAPISID")))
      normalized = complete;
#ifdef ORCHARD_NATIVE_COOKIE_AUTH
    // The native cookie snapshot arrives asynchronously. Never verify or save
    // document.cookie alone: it excludes the HttpOnly session credentials.
    const int captureState = (hasYouTubeLoginCookie(normalized) ? 1 : 0) |
                             (normalized == complete ? 2 : 0) |
                             (hasYouTubeSessionCookies(complete) ? 4 : 0);
    if (captureState != m_authCaptureState) {
      m_authCaptureState = captureState;
      authDiagnostic(
          QStringLiteral(
              "page-auth signing=%1 native-match=%2 native-session=%3")
              .arg(bool(captureState & 1))
              .arg(bool(captureState & 2))
              .arg(bool(captureState & 4)));
    }
    if (normalized != complete || !hasYouTubeSessionCookies(normalized)) {
      // A switch over the existing login may see no HttpOnly cookies from the
      // browser. The parent merges in the session it holds.
      if (!m_switchMode || !hasYouTubeLoginCookie(normalized))
        return false;
      normalized = mergeCookies(normalized, complete);
    }
#endif
    if (hasYouTubeLoginCookie(normalized)) {
      if (m_profileLoading && normalized == previousCookie &&
          m_dataSyncId == previousDataSyncId &&
          m_accountIndex == previousAccountIndex)
        return true;
      m_cookie = normalized;
      setErrorMessage(QString());
      startProfileResolution();
      return true;
    }
  }

  return false;
}

void AuthManager::failProfileResolution(const QString &message) {
  signOut();
  setErrorMessage(message);
}

void AuthManager::submitPageAccount(const QString &token,
                                    const QString &responseJson) {
  if (!m_authWebViewActive || !m_profileLoading ||
      token != QString::number(m_profileGeneration))
    return;
  const QJsonDocument document = QJsonDocument::fromJson(responseJson.toUtf8());
  const QJsonObject response = document.object();
  const int status = response.value(QStringLiteral("status")).toInt();
  if (!document.isObject() || status != 200 ||
      !response.value(QStringLiteral("data")).isObject()) {
    QString reason;
    if (!document.isObject())
      reason = QStringLiteral("invalid browser response");
    else if (response.value(QStringLiteral("failure")).toString() ==
             QStringLiteral("timeout"))
      reason = QStringLiteral("request timed out");
    else if (status == 0)
      reason = QStringLiteral("network request failed");
    else if (status != 200) {
      reason = QStringLiteral("HTTP %1").arg(status);
      const QString apiStatus = response.value(QStringLiteral("data"))
                                    .toObject()
                                    .value(QStringLiteral("error"))
                                    .toObject()
                                    .value(QStringLiteral("status"))
                                    .toString();
      // Only expose known enum values, never arbitrary server text or account
      // data.
      const QStringList knownStatuses = {QStringLiteral("INVALID_ARGUMENT"),
                                         QStringLiteral("FAILED_PRECONDITION"),
                                         QStringLiteral("UNAUTHENTICATED"),
                                         QStringLiteral("PERMISSION_DENIED"),
                                         QStringLiteral("RESOURCE_EXHAUSTED")};
      if (knownStatuses.contains(apiStatus))
        reason += QStringLiteral(": %1").arg(apiStatus);
    } else
      reason = QStringLiteral("invalid account response");
    // Never log the response body: it may contain account details or
    // credentials.
    qWarning().noquote() << "YouTube account verification failed:" << reason;
    failProfileResolution(
        QStringLiteral(
            "YouTube could not verify your account (%1). Please try again.")
            .arg(reason));
    return;
  }
  m_profileRequestId = m_youtubeProvider->invoke(
      QStringLiteral("account.parse"), response.value(QStringLiteral("data")));
}

void AuthManager::submitPageProfile(const QString &profileJson) {
  if (!m_profileLoading || !m_authWebViewActive)
    return;
  const QJsonDocument document = QJsonDocument::fromJson(profileJson.toUtf8());
  if (!document.isObject())
    return;
  mergeProfile(document.object());
  if (hasCompleteProfile()) {
    finishProfileResolution();
    return;
  }
  // Keep probing the chooser page; public enrichment can fail before its menu
  // has finished painting the selected channel's name and portrait.
  if (m_switchMode)
    return;
  const QJsonObject profile = m_pendingProfile;
  if (!profile.value(QStringLiteral("channelId")).toString().isEmpty() ||
      !profile.value(QStringLiteral("channelUrl")).toString().isEmpty()) {
    m_profileRequestId = m_youtubeProvider->enrichAccountProfile(profile);
  }
}

QString
AuthManager::delegatedSessionIdFromPageAuth(const QString &dataSyncId,
                                            const QString &delegatedSessionId) {
  const QString explicitId = delegatedSessionId.trimmed();
  if (!explicitId.isEmpty()) {
    return explicitId;
  }

  const QString normalized = dataSyncId.trimmed();
  const int separatorIndex = normalized.indexOf(QStringLiteral("||"));
  if (separatorIndex <= 0 || normalized.mid(separatorIndex + 2).isEmpty()) {
    return QString();
  }
  return normalized.left(separatorIndex);
}
