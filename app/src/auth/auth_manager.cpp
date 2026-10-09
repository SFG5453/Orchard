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

AuthManager::AuthManager(YouTubeProvider *youtubeProvider, bool embeddedWebAuth,
                         QObject *parent)
    : QObject(parent), m_youtubeProvider(youtubeProvider),
      m_browserCookies(new BrowserCookieAdapter(this)),
      m_embeddedWebAuth(embeddedWebAuth) {
  connect(m_browserCookies, &BrowserCookieAdapter::errorOccurred, this,
          [this](const QString &message) {
            if (m_authWebViewActive)
              failProfileResolution(message);
          });
  connect(m_browserCookies, &BrowserCookieAdapter::cookiesChanged, this,
          [this] {
            const QString complete =
                normalizeYouTubeAuthCookie(m_browserCookies->cookies());
            const auto browser = parseCookieString(complete);
            const auto saved = parseCookieString(m_cookie);
            // Only repair the same account's session; switching accounts stays
            // in login.
            if (m_cookie.isEmpty() ||
                browser.value(QStringLiteral("SAPISID")).isEmpty() ||
                browser.value(QStringLiteral("SAPISID")) !=
                    saved.value(QStringLiteral("SAPISID")) ||
                complete == m_cookie)
              return;
            m_cookie = complete;
            if (isSignedIn())
              persistSecretSession();
            emit sessionChanged();
          });
  connect(m_youtubeProvider, &YouTubeProvider::resultReady, this,
          [this](quint64 requestId, const QJsonValue &result) {
            if (m_switchValidationRequestId && requestId == m_switchValidationRequestId) {
              finishSwitchedAccount();
              return;
            }
            if (m_switchProfileRequestId && requestId == m_switchProfileRequestId) {
              receiveSwitchedProfile(result.toObject());
              return;
            }
            if (!m_profileLoading || requestId != m_profileRequestId ||
                !result.isObject())
              return;
            mergeProfile(result.toObject());
            if (hasCompleteProfile() || !m_authWebViewActive) {
              finishProfileResolution();
              return;
            }
            const quint64 generation = m_profileGeneration;
            emit profileProbeRequested();
            QTimer::singleShot(5000, this, [this, generation] {
              if (m_profileLoading && generation == m_profileGeneration)
                finishProfileResolution();
            });
          });
  connect(m_youtubeProvider, &YouTubeProvider::requestFailed, this,
          [this](quint64 requestId, const QString &message) {
            if (m_switchValidationRequestId && requestId == m_switchValidationRequestId) {
              failSwitchedAccount(tr("Could not use the selected YouTube channel: %1").arg(message));
              return;
            }
            if (m_switchProfileRequestId && requestId == m_switchProfileRequestId) {
              m_switchProfileRequestId = 0;
              return;
            }
            if (!m_profileLoading || requestId != m_profileRequestId)
              return;
            qWarning().noquote() << "YouTube profile lookup failed:" << message;
#ifdef ORCHARD_NATIVE_COOKIE_AUTH
            if (hasYouTubeSessionCookies(m_cookie)) {
              // profile metadata is best effort once the browser's
              // native cookie session has been captured.
              finishProfileResolution();
              return;
            }
#endif
            if (!m_authWebViewActive) {
              // QtWebView's document.cookie omits HttpOnly credentials. Let
              // the browser restore and validate its own complete session.
              m_profileLoading = false;
              m_profileRequestId = 0;
              startLogin();
              return;
            }
            failProfileResolution(message);
          });
  // Cookie refresh timer, the v4 equivalent of v2's refreshBrowserAuth(). (get
  // it? becuase qml uses a v4 engine im so hilarious)
  connect(&m_cookieRefreshTimer, &QTimer::timeout, this,
          &AuthManager::refreshSession);
  // The helper starts browser authentication explicitly. Restoring the keychain
  // here can race startLogin() and enter signed_in without loginCompleted.
  if (!m_embeddedWebAuth)
    restoreSession();
  if (m_status == QStringLiteral("signed_in") &&
      (m_userName.isEmpty() || m_userHandle.isEmpty() ||
       m_userAvatar.isEmpty())) {
    QTimer::singleShot(0, this, [this] {
      if (m_status == QStringLiteral("signed_in"))
        startProfileResolution();
    });
  }
  // If we restored a signed-in session, kick off the refresh timer and do
  // one immediate check once the event loop is running.
  if (isSignedIn() && !m_embeddedWebAuth) {
    scheduleCookieRefresh();
    QTimer::singleShot(0, this, &AuthManager::refreshSession);
  }
}

QString AuthManager::loginUrl() const {
  return QStringLiteral(
      "https://accounts.google.com/"
      "ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com");
}

QString AuthManager::channelSwitcherUrl() const {
  return QStringLiteral("https://www.youtube.com/channel_switcher");
}

void AuthManager::startLogin() {
  authDiagnostic(
      QStringLiteral("login-request embedded=%1").arg(m_embeddedWebAuth));
  setErrorMessage(QString());
  if (!m_embeddedWebAuth) {
    launchHelper(false);
    return;
  }
  ++m_profileGeneration;
  m_profileLoading = false;
  m_authCaptureState = -1;
  m_browserCookies->start();
  if (!m_authWebViewActive) {
    m_authWebViewActive = true;
    emit statusChanged();
  }
  setStatus(QStringLiteral("starting"));
}

void AuthManager::acceptHelperResult(const QJsonObject &result) {
  const QString cookie = normalizeYouTubeAuthCookie(
      result.value(QStringLiteral("cookie")).toString());
  if (!hasYouTubeLoginCookie(cookie)) {
    setStatus(QStringLiteral("signed_out"));
    setErrorMessage(
        QStringLiteral("The sign-in window returned an invalid session."));
    return;
  }
  ++m_profileGeneration;
  m_profileLoading = false;
  authDiagnostic(QStringLiteral("helper-session-accepted"));
  m_cookie = cookie;
  m_visitorData = result.value(QStringLiteral("visitorData")).toString();
  m_dataSyncId = result.value(QStringLiteral("dataSyncId")).toString();
  m_clientVersion = result.value(QStringLiteral("clientVersion")).toString();
  m_accountIndex =
      qMax(0, result.value(QStringLiteral("accountIndex")).toInt());
  m_userName = result.value(QStringLiteral("userName")).toString();
  m_userEmail = result.value(QStringLiteral("userEmail")).toString();
  m_userAvatar = result.value(QStringLiteral("userAvatar")).toString();
  m_userHandle = result.value(QStringLiteral("userHandle")).toString();
  m_switchProfileRequestId = 0;
  persistSecretSession();
  setStatus(QStringLiteral("signed_in"));
  emit sessionChanged();
  emit userChanged();
  emit loginCompleted();
  requestMissingProfile();
}

QJsonObject AuthManager::publicChannelProfile() const {
  QJsonObject publicChannel;
  if (m_dataSyncId.startsWith(QStringLiteral("UC")) && m_dataSyncId.size() == 24) {
    publicChannel.insert(QStringLiteral("channelId"), m_dataSyncId);
  } else if (m_userHandle.startsWith(QLatin1Char('@'))) {
    // A handle gives the public page a second chance when accounts_list sulks.
    const QString encoded = QString::fromLatin1(
        QUrl::toPercentEncoding(m_userHandle, QByteArray("@")));
    publicChannel.insert(QStringLiteral("channelUrl"),
                         QStringLiteral("https://www.youtube.com/%1").arg(encoded));
  }
  return publicChannel;
}

void AuthManager::requestMissingProfile() {
  if (!isSignedIn() || (!m_userName.isEmpty() && !m_userAvatar.isEmpty()))
    return;
  const QJsonObject publicChannel = publicChannelProfile();
  m_switchProfileRequestId = publicChannel.isEmpty()
                                 ? m_youtubeProvider->loadAccountProfile(sessionObject())
                                 : m_youtubeProvider->enrichAccountProfile(publicChannel);
}

void AuthManager::cancelLogin() {
  authDiagnostic(QStringLiteral("login-cancelled"));
  if (!m_embeddedWebAuth && m_authHelper &&
      m_authHelper->state() != QProcess::NotRunning) {
    m_authSessionServer->close();
    m_authHelper->terminate();
    setStatus(QStringLiteral("signed_out"));
    return;
  }
  if (m_authWebViewActive &&
      (m_status == QStringLiteral("starting") || m_profileLoading)) {
    signOut();
  }
}

void AuthManager::signOut() {
  authDiagnostic(QStringLiteral("sign-out"));
  m_pendingSwitch = {};
  m_switchValidationRequestId = 0;
  m_switchProfileRequestId = 0;
  m_switchingAccount = false;
  m_cookie.clear();
  m_visitorData.clear();
  m_dataSyncId.clear();
  m_accountIndex = 0;
  m_userName.clear();
  m_userEmail.clear();
  m_userAvatar.clear();
  m_userHandle.clear();
  m_clientVersion.clear();
  m_pendingProfile = {};
  m_profileLoading = false;
  m_authWebViewActive = false;
  m_profileRequestId = 0;
  ++m_profileGeneration;
  m_youtubeProvider->cancelAll();
  deleteSecretSession();
  setErrorMessage(QString());

  QSettings settings;
  settings.beginGroup(QStringLiteral("auth"));
  settings.remove(QString());
  settings.endGroup();

  setStatus(QStringLiteral("signed_out"));
  emit sessionChanged();
  emit userChanged();
}

bool AuthManager::setSession(const QString &cookie, const QString &visitorData,
                             const QString &dataSyncId) {
  const QString normalized = normalizeYouTubeAuthCookie(cookie.trimmed());
  if (!hasYouTubeLoginCookie(normalized)) {
    setErrorMessage(
        QStringLiteral("The provided cookie does not contain required YouTube "
                       "credentials (SAPISID or __Secure-3PAPISID)."));
    return false;
  }

  m_cookie = normalized;
  if (!visitorData.isEmpty()) {
    m_visitorData = visitorData.trimmed();
  }
  if (!dataSyncId.isEmpty()) {
    m_dataSyncId = dataSyncId.trimmed();
  }
  setErrorMessage(QString());
  m_authWebViewActive = false;
  startProfileResolution();
  return true;
}

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
      // WebEngine never replays stored cookies, so a switch over the existing
      // login sees no HttpOnly ones. The parent merges in the session it holds.
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

QString AuthManager::computeAuthorizationHeader(const QString &cookie,
                                                const QString &origin) const {
  const QMap<QString, QString> cookies = parseCookieString(cookie);
  QString sapisid = cookies.value(QStringLiteral("SAPISID"));
  if (sapisid.isEmpty()) {
    sapisid = cookies.value(QStringLiteral("__Secure-3PAPISID"));
  }
  if (sapisid.isEmpty()) {
    sapisid = cookies.value(QStringLiteral("APISID"));
  }

  if (sapisid.isEmpty()) {
    return QString();
  }

  const qint64 epochSeconds = QDateTime::currentSecsSinceEpoch();
  QStringList signedParts;

  const auto signCookie = [&](const QString &scheme, const QString &val) {
    const QString source =
        QStringLiteral("%1 %2 %3").arg(epochSeconds).arg(val, origin);
    const QByteArray hash =
        QCryptographicHash::hash(source.toUtf8(), QCryptographicHash::Sha1)
            .toHex();
    return QStringLiteral("%1 %2_%3")
        .arg(scheme)
        .arg(epochSeconds)
        .arg(QString::fromLatin1(hash));
  };

  signedParts.append(signCookie(QStringLiteral("SAPISIDHASH"), sapisid));

  if (cookies.contains(QStringLiteral("__Secure-1PAPISID"))) {
    signedParts.append(
        signCookie(QStringLiteral("SAPISID1PHASH"),
                   cookies.value(QStringLiteral("__Secure-1PAPISID"))));
  }
  if (cookies.contains(QStringLiteral("__Secure-3PAPISID"))) {
    signedParts.append(
        signCookie(QStringLiteral("SAPISID3PHASH"),
                   cookies.value(QStringLiteral("__Secure-3PAPISID"))));
  }

  return signedParts.join(QLatin1Char(' '));
}

// May I has cookie?
bool AuthManager::hasYouTubeLoginCookie(const QString &cookie) {
  const QMap<QString, QString> cookies = parseCookieString(cookie);
  return cookies.contains(QStringLiteral("SAPISID")) ||
         cookies.contains(QStringLiteral("__Secure-3PAPISID")) ||
         cookies.contains(QStringLiteral("__Secure-1PAPISID")) ||
         cookies.contains(QStringLiteral("APISID"));
}

bool AuthManager::hasYouTubeSessionCookies(const QString &cookie) {
  const auto cookies = parseCookieString(cookie);
  return (!cookies.value(QStringLiteral("SAPISID")).isEmpty() ||
          !cookies.value(QStringLiteral("__Secure-3PAPISID")).isEmpty()) &&
         (!cookies.value(QStringLiteral("SID")).isEmpty() ||
          !cookies.value(QStringLiteral("__Secure-1PSID")).isEmpty() ||
          !cookies.value(QStringLiteral("__Secure-3PSID")).isEmpty());
}

QString AuthManager::normalizeYouTubeAuthCookie(const QString &cookie) {
  const QString trimmed = cookie.trimmed();
  const QMap<QString, QString> cookies = parseCookieString(trimmed);
  if (trimmed.isEmpty() || cookies.contains(QStringLiteral("SAPISID")) ||
      !cookies.contains(QStringLiteral("__Secure-3PAPISID"))) {
    return trimmed;
  }
  return QStringLiteral("%1; SAPISID=%2")
      .arg(trimmed, cookies.value(QStringLiteral("__Secure-3PAPISID")));
}

QMap<QString, QString> AuthManager::parseCookieString(const QString &cookie) {
  QMap<QString, QString> result;
  const QStringList parts = cookie.split(QLatin1Char(';'), Qt::SkipEmptyParts);
  for (const QString &rawPart : parts) {
    const QString part = rawPart.trimmed();
    const int separatorIndex = part.indexOf(QLatin1Char('='));
    if (separatorIndex <= 0) {
      continue;
    }
    const QString name = part.left(separatorIndex).trimmed();
    const QString value = part.mid(separatorIndex + 1).trimmed();
    if (!name.isEmpty()) {
      result.insert(name, value);
    }
  }
  return result;
}

void AuthManager::setStatus(const QString &status) {
  if (m_status != status) {
    authDiagnostic(QStringLiteral("status %1 -> %2").arg(m_status, status));
    m_status = status;
    // Keep the cookie refresh timer in sync with the auth lifecycle.
    if (status == QStringLiteral("signed_in"))
      scheduleCookieRefresh();
    else if (status == QStringLiteral("signed_out"))
      stopCookieRefresh();
    emit statusChanged();
  }
}

void AuthManager::setErrorMessage(const QString &error) {
  if (m_errorMessage != error) {
    authDiagnostic(error.isEmpty() ? QStringLiteral("error-cleared")
                                   : QStringLiteral("error-set"));
    m_errorMessage = error;
    emit errorChanged();
  }
}

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
#if defined(ORCHARD_WEBENGINE_COOKIE_ADAPTER) ||                               \
    defined(ORCHARD_NATIVE_COOKIE_AUTH)
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

QJsonObject AuthManager::sessionObject() const {
  return {{QStringLiteral("cookie"), m_cookie},
          {QStringLiteral("visitorData"), m_visitorData},
          {QStringLiteral("dataSyncId"), m_dataSyncId},
          {QStringLiteral("clientVersion"), m_clientVersion},
          {QStringLiteral("accountIndex"), m_accountIndex}};
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

// re-reads the browser cookie store and checks that credentials haven't gone for a smoke break.
// Called on a 15-minute timer and before authenticated network requests.
void AuthManager::refreshSession() {
  if (m_embeddedWebAuth || m_authWebViewActive || m_profileLoading)
    return;
  if (!isSignedIn() || m_cookie.isEmpty())
    return;

  const QString fresh = normalizeYouTubeAuthCookie(m_browserCookies->cookies());
  if (!fresh.isEmpty() && fresh != m_cookie) {
    const auto freshParsed = parseCookieString(fresh);
    const auto savedParsed = parseCookieString(m_cookie);
    // Only absorb cookies from the same account don't silently swap
    // identities.
    if (!freshParsed.value(QStringLiteral("SAPISID")).isEmpty() &&
        freshParsed.value(QStringLiteral("SAPISID")) ==
            savedParsed.value(QStringLiteral("SAPISID"))) {
      m_cookie = fresh;
      persistSecretSession();
      emit sessionChanged();
    }
  }

  // Validate that the stored cookie still has login credentials.
  // If something ate our cookies (browser cleanup, expiry, gremlins),
  // transition to signed_out so the UI can prompt re-login.
  if (!hasYouTubeLoginCookie(m_cookie)) {
    authDiagnostic(QStringLiteral("cookie-refresh-session-expired"));
    setStatus(QStringLiteral("signed_out"));
    setErrorMessage(QStringLiteral(
        "Your YouTube session has expired. Please sign in again."));
    emit cookieRefreshCompleted(false);
    return;
  }

  emit cookieRefreshCompleted(true);
}

// 15 minutes. frequent enough to catch stale cookies before they cause
// playback failures, rare enough to not annoy the event loop.
void AuthManager::scheduleCookieRefresh() {
  if (m_embeddedWebAuth)
    return;
  constexpr int refreshIntervalMs = 15 * 60 * 1000;
  m_cookieRefreshTimer.start(refreshIntervalMs);
}

void AuthManager::stopCookieRefresh() { m_cookieRefreshTimer.stop(); }
